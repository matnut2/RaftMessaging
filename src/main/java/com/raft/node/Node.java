package com.raft.node;

import com.raft.core.LogEntry;
import com.raft.core.Role;
import com.raft.core.Snapshot;
import com.raft.core.Network;
import com.raft.rpc.*;
import com.raft.core.Storage;
import com.raft.core.WalStorage;
import com.raft.core.FileStorage;
import com.raft.core.PersistentState;
import com.raft.core.RaftMessageReceiver;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.concurrent.CopyOnWriteArrayList;

public class Node<T> implements RaftMessageReceiver{
    private final String nodeID;
    private final List<String> peers;
    private final Network network;
    private ExecutorService vThreadExecutor;
    private final ReentrantLock lock;
    private final Random random;
    private volatile boolean running;
    private final Storage<T> storage;

    private final AtomicLong lastElectionResetTime;
    private final int MIN_TIMEOUT_MS = 1500;
    private final int MAX_TIMEOUT_MS = 3000;
    private final int heartbeatInterval = 500;
    private final Map<String, Long> clientSession = new ConcurrentHashMap<>();
    private final int electionTimeout;
    private final ByteArrayOutputStream snapshotBuffer = new ByteArrayOutputStream();

    private long currentTerm;
    private int preVotesReceived;
    private String votedFor;
    private final List<LogEntry<T>> log;

    private Role currentRole;
    private int votesReceived;

    private Map<String, Integer> nextIndex;
    private Map<String, Integer> matchIndex;
    private long commitIndex = -1;
    private long lastApplied = 0;

    private long lastIncludedIndex = -1;
    private long lastIncludedTerm = 0;

    private volatile String currentLeaderID;
    private volatile boolean isRemovedFromCluster = false;

    
    private final Map<String, String> stateMachine = new ConcurrentHashMap<>();

    public Node(String nodeID, List<String> peers, Network network) {
        this.nodeID = nodeID;
        this.peers = new CopyOnWriteArrayList<>(peers);
        this.network = network;
        this.lock = new ReentrantLock();
        this.random = new Random();

        this.storage = new WalStorage<T>(nodeID, String.class);
        
        Snapshot snap = storage.loadSnapshot();
        
        if (snap != null){
            this.lastIncludedIndex = snap.lastIncludedIndex();
            this.lastIncludedTerm = snap.lastIncludedTerm();
            this.stateMachine.putAll(snap.data());

            this.lastApplied = lastIncludedIndex+1;
            this.commitIndex = lastIncludedIndex;
        }
        
        
        PersistentState<T> state = storage.load();
        this.currentTerm = state.term();
        this.votedFor = state.votedFor();
        this.log = new ArrayList<>(state.log());

        this.currentRole = Role.FOLLOWER;
        this.electionTimeout = MIN_TIMEOUT_MS + random.nextInt(MAX_TIMEOUT_MS - MIN_TIMEOUT_MS);

        this.lastElectionResetTime = new AtomicLong(System.currentTimeMillis());

        this.running = false;
    }

    private void persist(){
        storage.save(currentTerm, votedFor, log);
    }

    public void takeSnapshot() {
    lock.lock();
    try {
        if (lastApplied <= lastIncludedIndex) return;

        long snapshotIndex = lastApplied-1;
        
        LogEntry<T> entry = getEntry(snapshotIndex);
        if (entry == null) return;
        long snapshotTerm = entry.term();

        System.out.println("Node " + nodeID + " taking snapshot at index " + snapshotIndex);
        Snapshot newSnap = new Snapshot(snapshotIndex, snapshotTerm, new ConcurrentHashMap<>(stateMachine), new ConcurrentHashMap<>(clientSession));
        storage.saveSnapshot(newSnap);
        int localCutIndex = getLocalIndex(snapshotIndex); 
        List<LogEntry<T>> remaining = new ArrayList<>(log.subList(localCutIndex + 1, log.size()));
        
        log.clear();
        log.addAll(remaining);

        lastIncludedIndex = snapshotIndex;
        lastIncludedTerm = snapshotTerm;

        persist(); 

    } finally {
        lock.unlock();
    }
}

    public void start() {
        lock.lock();
        try {
            if (running) return;
            this.running = true;
            this.vThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
            this.lastElectionResetTime.set(System.currentTimeMillis());

            vThreadExecutor.submit(this::runElectionLoop);
            System.out.println("Node " + nodeID + " STARTED (Reborn).");
        } finally {
            lock.unlock();
        }
    }

    public void stop() {
        lock.lock();
        try {
            if (!running) return;
            this.running = false;

            if (vThreadExecutor != null) {
                vThreadExecutor.shutdownNow();
            }
            System.out.println("Node " + nodeID + " STOPPED (Crash).");
        } finally {
            lock.unlock();
        }
    }

    private void runElectionLoop() {
        while (running) {
            try {
                
                Thread.sleep(20);
            } catch (InterruptedException e) {
                if (!running) break;
            }

            long elapsed = System.currentTimeMillis() - lastElectionResetTime.get();
            if (elapsed >= electionTimeout) {
                startPreVote();
            }
        }
    }

    private void startPreVote() {
        lock.lock();
        try {
            if (isRemovedFromCluster) return;
            if (currentRole == Role.LEADER) return;

            System.out.println("Node " + nodeID + " timeout. Starting Pre-Vote for term " + (currentTerm + 1));
            
            preVotesReceived = 1; 
            resetElectionTimer(); 

            int quorum = (peers.size() + 1) / 2 + 1;
            if (preVotesReceived >= quorum) {
                preVotesReceived = 0;
                startElection();
                return;
            }
            
            long nextTerm = currentTerm + 1;
            long lastLogIdx = getLastLogIndex(); 
            long lastLogTerm = getLastLogTerm();

            PreVoteRequest request = new PreVoteRequest(nextTerm, nodeID, lastLogIdx, lastLogTerm);

            for (String peerID : peers) {
                vThreadExecutor.submit(() -> 
                    network.sendPreVote(peerID, request)
                        .thenAccept(this::handlePreVoteResponse)
                        .exceptionally(ex -> null) 
                );
            }
        } finally {
            lock.unlock();
        }
    }

    public PreVoteResponse handlePreVote(PreVoteRequest request) {
        if (!running) throw new RuntimeException("Node is down");
        lock.lock();
        try {
            if (request.nextTerm() <= currentTerm) {
                return new PreVoteResponse(currentTerm, false);
            }

            boolean logIsUpToDate = false;
            long myLastLogIndex = getLastLogIndex();
            long myLastLogTerm = getLastLogTerm();

            if (request.lastLogTerm() > myLastLogTerm) {
                logIsUpToDate = true;
            } else if (request.lastLogTerm() == myLastLogTerm && request.lastLogIndex() >= myLastLogIndex) {
                logIsUpToDate = true;
            }

            return new PreVoteResponse(currentTerm, logIsUpToDate);
        } finally {
            lock.unlock();
        }
    }

    private void handlePreVoteResponse(PreVoteResponse response) {
        if (response == null) return; 

        lock.lock();
        try {
            if (currentRole == Role.LEADER) return;

            if (response.term() > currentTerm) {
                currentRole = Role.FOLLOWER;
                currentTerm = response.term();
                votedFor = null;
                persist();
                return;
            }

            if (response.voteGranted()) {
                preVotesReceived++;
                int quorum = (peers.size() + 1) / 2 + 1;
                if (preVotesReceived >= quorum) {
                    preVotesReceived = 0; 
                    startElection();
                }
            }
        } finally {
            lock.unlock();
        }
    }

    private void startElection() {
        lock.lock();
        try {
            if (isRemovedFromCluster) return;
            if (currentRole == Role.LEADER) return;

            currentLeaderID = null;

            System.out.println("Node " + nodeID + " timeout. Starting election for term " + (currentTerm + 1));

            currentRole = Role.CANDIDATE;
            currentTerm += 1;
            votedFor = nodeID;
            votesReceived = 1;

            resetElectionTimer();

            
            long lastLogIdx = log.size() - 1;
            long lastLogTerm = 0;
            if (lastLogIdx >= 0) lastLogTerm = log.get((int) lastLogIdx).term();

            RequestVoteRequest request = new RequestVoteRequest(currentTerm, nodeID, lastLogIdx, lastLogTerm);

            for (String peerID : peers) {
                vThreadExecutor.submit(() -> 
                    network.sendRequestVote(peerID, request)
                        .thenAccept(this::handleVoteResponse)
                        .exceptionally(ex -> null) 
                );
            }
        } finally {
            lock.unlock();
        }
    }

    private void runHearthbeatLoop() {
        while (currentRole == Role.LEADER && running) {
            long start = System.currentTimeMillis();

            sendHearthbeats();

            long elapsed = System.currentTimeMillis() - start;
            long sleepTime = heartbeatInterval - elapsed;

            if (sleepTime > 0) {
                try {
                    Thread.sleep(sleepTime);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void sendHearthbeats() {
    lock.lock();
    try {
        if (currentRole != Role.LEADER) return;

        for (String peerID : peers) {
            int nextIdx = nextIndex.getOrDefault(peerID, 1);
            
            if (nextIdx <= lastIncludedIndex) {
                sendSnapshotToPeer(peerID);
            } else {
                sendAppendEntriesToPeer(peerID, nextIdx);
            }
        }
    } finally {
        lock.unlock();
    }
}


private void sendSnapshotToPeer(String peerID) {
        byte[] snapshotBytes = serializeSnapshotState();
        sendSnapshotChunk(peerID, snapshotBytes, 0);
    }

    private void sendSnapshotChunk(String peerID, byte[] snapshotBytes, int offset) {
        int CHUNK_SIZE = 4096; 
        int length = Math.min(CHUNK_SIZE, snapshotBytes.length - offset);
        byte[] chunk = new byte[length];
        System.arraycopy(snapshotBytes, offset, chunk, 0, length);
        
        boolean done = (offset + length) >= snapshotBytes.length;

        InstallSnapshotRequest request = new InstallSnapshotRequest(
            currentTerm, nodeID, lastIncludedIndex, lastIncludedTerm,
            offset, chunk, done
        );

        vThreadExecutor.submit(() -> 
            network.sendInstallSnapshot(peerID, request)
                .thenAccept(response -> {
                    handleInstallSnapshotResponse(peerID, response);
                    if (response != null && response.term() == currentTerm && !done) {
                        sendSnapshotChunk(peerID, snapshotBytes, offset + length);
                    }
                })
                .exceptionally(ex -> null)
        );
    }
    private void handleInstallSnapshotResponse(String peerID, InstallSnapshotResponse response) {
        if (response == null) return;
        lock.lock();
        try {
            if (currentRole != Role.LEADER) return;

            if (response.term() > currentTerm) {
                currentTerm = response.term();
                currentRole = Role.FOLLOWER;
                votedFor = null;
                persist();
                return;
            }

            nextIndex.put(peerID, (int)lastIncludedIndex + 1);
            matchIndex.put(peerID, (int)lastIncludedIndex);
            
        } finally {
            lock.unlock();
        }
    }

    private void sendAppendEntriesToPeer(String peerID, int nextIdx) {
        long prevLogIndex = nextIdx - 1;
        long prevLogTerm = getTermForIndex(prevLogIndex);

        List<LogEntry<T>> entriesToSend = new ArrayList<>();

        int localStartIndex = getLocalIndex(nextIdx);
        
        if (localStartIndex >= 0 && localStartIndex < log.size()) {
            entriesToSend.addAll(log.subList(localStartIndex, log.size()));
        }

        AppendEntriesRequest<T> request = new AppendEntriesRequest<>(
            currentTerm, nodeID, prevLogIndex, prevLogTerm, entriesToSend, commitIndex
        );

        vThreadExecutor.submit(() -> 
            network.sendAppendEntries(peerID, request)
                .thenAccept(response -> handleAppendEntriesResponse(peerID, response, entriesToSend.size()))
                .exceptionally(ex -> null) 
        );
    }

    public RequestVoteResponse handleRequestVote(RequestVoteRequest request) {
        if (!running) throw new RuntimeException("Node is down");

        lock.lock();
        try {
            if (request.term() > currentTerm) {
                currentTerm = request.term();
                currentRole = Role.FOLLOWER;
                votedFor = null;
                persist();
            }
            if (request.term() < currentTerm) {
                return new RequestVoteResponse(currentTerm, false);
            }

            boolean canVote = (votedFor == null || votedFor.equals(request.candidateId()));
            boolean logIsUpToDate = false;
            long lastLogIndex = log.size() - 1 + lastIncludedIndex + 1;
            long lastLogTerm = 0;
            if (log.size() > 0) {
                 lastLogTerm = log.get(log.size() - 1).term();
            } else {
                 lastLogTerm = lastIncludedTerm;
            }

            if (request.lastLogTerm() > lastLogTerm) {
                logIsUpToDate = true;
            } else if (request.lastLogTerm() == lastLogTerm && request.lastLogIndex() >= lastLogIndex) {
                logIsUpToDate = true;
            }

            boolean voteGranted = false;
            if (canVote && logIsUpToDate) {
                votedFor = request.candidateId();
                voteGranted = true;
                resetElectionTimer(); 
                persist();
            }

            return new RequestVoteResponse(currentTerm, voteGranted);
        } finally {
            lock.unlock();
        }
    }

    private void handleVoteResponse(RequestVoteResponse response) {
        if (response == null) return; 

        lock.lock();
        try {
            if (response.term() > currentTerm) {
                currentRole = Role.FOLLOWER;
                currentTerm = response.term();
                votedFor = null;
                return;
            }

            if (currentRole == Role.CANDIDATE && response.voteGranted()) {
                votesReceived += 1;
                int quorum = (peers.size() + 1) / 2 + 1;
                if (votesReceived >= quorum) {
                    becomeLeader();
                }
            }
        } finally {
            lock.unlock();
        }
    }

    private void becomeLeader() {
        if (currentRole == Role.LEADER) return;

        currentRole = Role.LEADER;
        System.out.println("NODE " + nodeID + " BECAME LEADER (Term " + currentTerm + ")");

        nextIndex = new ConcurrentHashMap<>();
        matchIndex = new ConcurrentHashMap<>();

        int absoluteNextIndex = (int) (lastIncludedIndex + log.size() + 1);

        for (String peerID : peers) {
            nextIndex.put(peerID, absoluteNextIndex);            matchIndex.put(peerID, -1);
        }

        vThreadExecutor.submit(this::runHearthbeatLoop);
    }

    public AppendEntriesResponse handleAppendEntries(AppendEntriesRequest<?> request) {
        if (!running) throw new RuntimeException("Node is down");

        lock.lock();
        try {
            if (request.term() < currentTerm) {
                return new AppendEntriesResponse(currentTerm, false);
            }

            if (request.term() >= currentTerm) {
                currentTerm = request.term();
                currentRole = Role.FOLLOWER;
                votedFor = null;
                currentLeaderID = request.leaderId();
            }

            resetElectionTimer();

            long prevLogIndex = request.prevLogIndex();
            long prevLogTerm = request.prevLogTerm();

            if (prevLogIndex > -1 && log.size() <= prevLogIndex) {
                return new AppendEntriesResponse(currentTerm, false);
            }

            if (prevLogIndex > -1) {
                LogEntry<T> entryAtPrev = log.get((int) prevLogIndex);
                if (entryAtPrev.term() != prevLogTerm) {
                    return new AppendEntriesResponse(currentTerm, false);
                }
            }

            @SuppressWarnings("unchecked")
            List<LogEntry<T>> newEntries = (List<LogEntry<T>>) (List<?>) request.entries();
            long indexToInsert = prevLogIndex + 1;

            for (LogEntry<T> entry : newEntries) {
                if (indexToInsert < log.size()) {
                    LogEntry<T> existingEntry = log.get((int) indexToInsert);
                    if (existingEntry.term() != entry.term()) {
                        log.subList((int) indexToInsert, log.size()).clear();
                        log.add(entry);
                    }
                    persist();;
                } else {
                    log.add(entry);
                }

                if (entry.command() instanceof String cmd) {
                    if (cmd.startsWith("CONF_REMOVE_SERVER=")) {
                        String target = cmd.substring(19).trim();
                        if (target.equals(nodeID)) {
                            System.out.println("NODE " + nodeID + " detected removal from incoming log. Disabling elections.");
                            isRemovedFromCluster = true;
                            currentRole = Role.FOLLOWER;
                        }
                    }
                }
                
                indexToInsert++;
            }

            if (request.leaderCommit() > commitIndex) {
                long lastNewIndex = log.size() - 1;
                commitIndex = Math.min(request.leaderCommit(), lastNewIndex);
                applyLog();
            }

            return new AppendEntriesResponse(currentTerm, true);
        } finally {
            lock.unlock();
        }
    }

    private void handleAppendEntriesResponse(String peerID, AppendEntriesResponse response, int numEntriesSent) {
        if (response == null) return; 

        lock.lock();
        try {
            if (currentRole != Role.LEADER) return;

            if (response.term() > currentTerm) {
                currentTerm = response.term();
                currentRole = Role.FOLLOWER;
                votedFor = null;
                return;
            }

            if (response.success()) {
                int oldNext = nextIndex.get(peerID);
                int newNext = oldNext + numEntriesSent;
                nextIndex.put(peerID, newNext);
                matchIndex.put(peerID, newNext - 1);
                updateCommitIndex();
            } else {
                int currentNext = nextIndex.get(peerID);
                if (currentNext > 0) {
                    nextIndex.put(peerID, currentNext - 1);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    public boolean propose(String clientID, long sequenceNum, T command) {
        lock.lock();
        try {
            if (currentRole != Role.LEADER) return false;

            if (clientSession.getOrDefault(clientID, -1L) >= sequenceNum){
                return true;
            }

            LogEntry<T> entry = new LogEntry<>(currentTerm, clientID, sequenceNum, command);
            log.add(entry);
            persist();
            return true;
        } finally {
            lock.unlock();
        }
    }

    public void updateCommitIndex() {
        List<Integer> indexes = new ArrayList<>();
        indexes.add((int) (lastIncludedIndex + log.size())); 
        indexes.addAll(matchIndex.values());
        indexes.sort(Integer::compareTo);
        
        int commitThreshold = indexes.size() / 2;
        int N = indexes.get(commitThreshold);

        if (N > commitIndex && N <= (lastIncludedIndex + log.size())) {
            LogEntry<T> entry = getEntry(N);
            if (entry != null && entry.term() == currentTerm) {
                commitIndex = N;
                applyLog();
            }
        }
    }

    
    private void applyLog() {
        while (lastApplied <= commitIndex) {
            LogEntry<T> entry = getEntry(lastApplied);
            if (entry == null) break;
            
            if (entry.command() instanceof String cmd) {
                applyCommand(cmd);
            } else {
                System.out.println("NODE " + nodeID + " EXECUTED GENERIC: " + entry.command());
            }
            lastApplied++;
        }
    }

    private void applyCommand(String command) {
        try {
            if (command.startsWith("SET ")) {
                String[] parts = command.substring(4).split("=");
                if (parts.length == 2) {
                    stateMachine.put(parts[0].trim(), parts[1].trim());
                    System.out.println("✅ NODE " + nodeID + " APPLIED DB: " + parts[0] + "=" + parts[1]);
                }
            } else if (command.startsWith("DEL ")) {
                String key = command.substring(4).trim();
                stateMachine.remove(key);
                
            } else if (command.startsWith("CONF_ADD_SERVER=")) {
                String newPeer = command.substring(16).trim();
                if (!peers.contains(newPeer) && !newPeer.equals(nodeID)) {
                    peers.add(newPeer);
                    System.out.println("🔧 NODE " + nodeID + " ADDED PEER: " + newPeer);
                    
                    if (currentRole == Role.LEADER) {
                        nextIndex.putIfAbsent(newPeer, (int) (lastIncludedIndex + log.size() + 1));
                        matchIndex.putIfAbsent(newPeer, -1);
                    }
                }
            } else if (command.startsWith("CONF_REMOVE_SERVER=")) {
                String oldPeer = command.substring(19).trim();
                peers.remove(oldPeer);
                System.out.println("🔧 NODE " + nodeID + " REMOVED PEER: " + oldPeer);
                
                if (currentRole == Role.LEADER) {
                    nextIndex.remove(oldPeer);
                    matchIndex.remove(oldPeer);
                }
                
                if (oldPeer.equals(nodeID)) {
                    System.out.println("NODE " + nodeID + " removed from cluster. Stepping down.");
                    currentRole = Role.FOLLOWER;
                    isRemovedFromCluster = true;
                }
            }
            
        } catch (Exception e) {
            System.err.println("Error applying command: " + command);
        }
    }

    
    public String get(String key) {
        if (getRole() != Role.LEADER) {
            String leader = currentLeaderID;

            if (leader == null) 
                throw new RuntimeException("Service Unavailable: NO Leader Known");
            
            try{
                return network.sendClientGet(leader, key).join();
            }
            catch (Exception e){
                throw new RuntimeException("Error forwarding request to leader " + leader, e);
            }
        }

        lock.lock();
        long readIndex = commitIndex;
        lock.unlock();

        boolean isLeader = confirmLeadership().join();

        if (!isLeader) {
            throw new RuntimeException("Read failed: lost leadership or quorum unreachable");
        }

        while (getLastApplied() < readIndex) {
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for apply");
            }
        }

        return stateMachine.get(key);
    }
    
    public void resetElectionTimer() {
        this.lastElectionResetTime.set(System.currentTimeMillis());
    }
    public Role getRole() {
        lock.lock();
        try { return currentRole; } finally { lock.unlock(); }
    }
    public long getTerm() {
        lock.lock();
        try { return currentTerm; } finally { lock.unlock(); }
    }
    public String getNodeID() {
        lock.lock();
        try { return nodeID; } finally { lock.unlock(); }
    }
    public List<LogEntry<T>> getLogCopy() {
        lock.lock();
        try { return new ArrayList<>(log); } finally { lock.unlock(); }
    }
    public long getCommitIndex() {
        lock.lock();
        try { return commitIndex; } finally { lock.unlock(); }
    }
    public long getLastApplied() {
        lock.lock();
        try { return lastApplied; } finally { lock.unlock(); }
    }

    private int getLocalIndex(long raftLogIndex) {
        return (int) (raftLogIndex - lastIncludedIndex - 1);
    }

    private LogEntry<T> getEntry(long raftLogIndex) {
        int localIdx = getLocalIndex(raftLogIndex);
        if (localIdx < 0 || localIdx >= log.size()) {
            return null; 
        }
        return log.get(localIdx);
    }

    private long getTermForIndex(long index) {
        if (index == -1) return 0;
        if (index == lastIncludedIndex) {
            return lastIncludedTerm;
        }
        LogEntry<T> entry = getEntry(index);
        return (entry != null) ? entry.term() : 0;
    }

    private long getLastLogIndex() {
        return lastIncludedIndex + log.size();
    }

    private long getLastLogTerm() {
        if (log.isEmpty()) {
            return lastIncludedTerm;
        }
        return log.get(log.size() - 1).term();
    }

    public InstallSnapshotResponse handleInstallSnapshot(InstallSnapshotRequest request) {
        if (!running) throw new RuntimeException("Node is down");
        lock.lock();

        try {
            if (request.term() < currentTerm) {
                return new InstallSnapshotResponse(currentTerm);
            }

            if (request.term() > currentTerm) {
                currentTerm = request.term();
                currentRole = Role.FOLLOWER;
                votedFor = null;
                currentLeaderID = request.leaderId();
                persist();
            }

            resetElectionTimer();

            if (request.offset() == 0) {
                snapshotBuffer.reset();
            }

            snapshotBuffer.write(request.data(), 0, request.data().length);

            if (!request.done()) {
                return new InstallSnapshotResponse(currentTerm);
            }

            if (request.lastIncludedIndex() <= lastIncludedIndex) {
                return new InstallSnapshotResponse(currentTerm);
            }

            System.out.println("Node " + nodeID + " installing full snapshot from Leader. LastIndex: " + request.lastIncludedIndex());
            
            deserializeAndApplySnapshotState(snapshotBuffer.toByteArray());

            this.lastIncludedIndex = request.lastIncludedIndex();
            this.lastIncludedTerm = request.lastIncludedTerm();

            int localCutIndex = getLocalIndex(request.lastIncludedIndex());

            if (localCutIndex >= 0 && localCutIndex < log.size()) {
                List<LogEntry<T>> remaining = new ArrayList<>(log.subList(localCutIndex + 1, log.size()));
                log.clear();
                log.addAll(remaining);
            } else {
                log.clear();
            }

            this.lastApplied = request.lastIncludedIndex() + 1;
            this.commitIndex = Math.max(commitIndex, request.lastIncludedIndex());

            Snapshot newSnap = new Snapshot(lastIncludedIndex, lastIncludedTerm, new ConcurrentHashMap<>(stateMachine), new ConcurrentHashMap<>(clientSession));
            storage.saveSnapshot(newSnap);
            persist();

            return new InstallSnapshotResponse(currentTerm);
        } finally {
            lock.unlock();
        }
    }

    private CompletableFuture<Boolean> confirmLeadership() {
        if (currentRole != Role.LEADER) {
            return CompletableFuture.completedFuture(false);
        }

        AtomicInteger acks = new AtomicInteger(1); 
        int quorum = (peers.size() + 1) / 2 + 1;
        CompletableFuture<Boolean> result = new CompletableFuture<>();

        AppendEntriesRequest<T> heartbeat = new AppendEntriesRequest<>(
            currentTerm, nodeID, getLastLogIndex(), getLastLogTerm(), new ArrayList<>(), commitIndex
        );

        for (String peer : peers) {
            vThreadExecutor.submit(() -> 
                network.sendAppendEntries(peer, heartbeat)
                    .thenAccept(response -> {
                        if (response != null) {
                            if (response.term() == currentTerm) {
                                if (acks.incrementAndGet() >= quorum) {
                                    result.complete(true);
                                }
                            } else if (response.term() > currentTerm) {
                                result.complete(false);
                            }
                        }
                    })
                    .exceptionally(e -> null)
            );
        }
        
        vThreadExecutor.submit(() -> {
            try {
                Thread.sleep(electionTimeout / 2);
                if (!result.isDone()) {
                    result.complete(false);
                }
            } catch (InterruptedException e) { /* ignore */ }
        });

        return result;
    }

    private byte[] serializeSnapshotState() {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(new ConcurrentHashMap<>(stateMachine));
            oos.writeObject(new ConcurrentHashMap<>(clientSession));
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Error serializing snapshot state", e);
        }
    }

    @SuppressWarnings("unchecked")
    private void deserializeAndApplySnapshotState(byte[] bytes) {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
             ObjectInputStream ois = new ObjectInputStream(bais)) {
            
            Map<String, String> parsedStateMachine = (Map<String, String>) ois.readObject();
            Map<String, Long> parsedClientSession = (Map<String, Long>) ois.readObject();
            
            this.stateMachine.clear();
            this.stateMachine.putAll(parsedStateMachine);
            
            this.clientSession.clear();
            this.clientSession.putAll(parsedClientSession);
            
        } catch (Exception e) {
            throw new RuntimeException("Error deserializing snapshot state", e);
        }
    }

    public boolean addServer(String newPeerID) {
        lock.lock();
        try {
            if (currentRole != Role.LEADER) return false;
            if (peers.contains(newPeerID)) return true;

            // Restrizione Single-Server Change: controlla l'assenza di cambi configurazione pendenti
            for (long i = commitIndex + 1; i <= lastIncludedIndex + log.size(); i++) {
                LogEntry<T> entry = getEntry(i);
                if (entry != null && entry.command() instanceof String cmd) {
                    if (cmd.startsWith("CONF_")) {
                        System.out.println("Cannot add server: another configuration change is pending.");
                        return false; 
                    }
                }
            }

            @SuppressWarnings("unchecked")
            T cmd = (T) ("CONF_ADD_SERVER=" + newPeerID);
            return propose("SYSTEM_CONFIG", System.currentTimeMillis(), cmd);
        } finally {
            lock.unlock();
        }
    }

    public boolean removeServer(String peerID) {
        lock.lock();
        try {
            if (currentRole != Role.LEADER) return false;
            if (!peers.contains(peerID)) return true;

            for (long i = commitIndex + 1; i <= lastIncludedIndex + log.size(); i++) {
                LogEntry<T> entry = getEntry(i);
                if (entry != null && entry.command() instanceof String cmd) {
                    if (cmd.startsWith("CONF_")) {
                        System.out.println("Cannot remove server: another configuration change is pending.");
                        return false;
                    }
                }
            }

            @SuppressWarnings("unchecked")
            T cmd = (T) ("CONF_REMOVE_SERVER=" + peerID);
            return propose("SYSTEM_CONFIG", System.currentTimeMillis(), cmd);
        } finally {
            lock.unlock();
        }
    }

    public List<String> getPeers() {
        lock.lock();
        try { return new ArrayList<>(peers); } finally { lock.unlock(); }
    }
}


