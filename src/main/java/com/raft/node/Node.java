package com.raft.node;

import com.raft.core.LogEntry;
import com.raft.core.Role;
import com.raft.core.Snapshot;
import com.raft.core.Network;
import com.raft.rpc.*;
import com.raft.core.Storage;
import com.raft.core.FileStorage;
import com.raft.core.PersistentState;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Node<T> {
    private final String nodeID;
    private final List<String> peers;
    private final Network network;
    private ExecutorService vThreadExecutor;
    private final ReentrantLock lock;
    private final Random random;
    private volatile boolean running;
    private final Storage<T> storage;

    private final AtomicLong lastElectionResetTime;
    private final int MIN_TIMEOUT_MS = 150;
    private final int MAX_TIMEOUT_MS = 300;
    private final int heartbeatInterval = 50;
    private final int electionTimeout;

    private long currentTerm;
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

    
    private final Map<String, String> stateMachine = new ConcurrentHashMap<>();

    public Node(String nodeID, List<String> peers, Network network) {
        this.nodeID = nodeID;
        this.peers = peers;
        this.network = network;
        this.lock = new ReentrantLock();
        this.random = new Random();

        this.storage = new FileStorage<T>(nodeID);
        
        Snapshot snap = storage.loadSnapshot();
        
        if (snap != null){
            this.lastIncludedIndex = snap.lastIncludedIndex();
            this.lastIncludedTerm = snap.lastIncludedTerm();
            this.stateMachine.putAll(snap.data());
            this.lastApplied = lastIncludedIndex;
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

        long snapshotIndex = lastApplied;
        
        LogEntry<T> entry = getEntry(snapshotIndex);
        if (entry == null) return;
        long snapshotTerm = entry.term();

        System.out.println("Node " + nodeID + " taking snapshot at index " + snapshotIndex);
        Snapshot newSnap = new Snapshot(snapshotIndex, snapshotTerm, new ConcurrentHashMap<>(stateMachine));
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
                startElection();
            }
        }
    }

    private void startElection() {
        lock.lock();
        try {
            if (currentRole == Role.LEADER) return;

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
                int prevLogIndex = nextIndex.getOrDefault(peerID, 0) - 1;
                long prevLogTerm = 0;

                if (prevLogIndex >= 0 && prevLogIndex < log.size()) {
                    prevLogTerm = log.get(prevLogIndex).term();
                }

                List<LogEntry<T>> entriesToSend = new ArrayList<>();
                int nextIdx = nextIndex.get(peerID);

                if (nextIdx < log.size()) {
                    entriesToSend.addAll(log.subList(nextIdx, log.size()));
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
        } finally {
            lock.unlock();
        }
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

            
            long myLastLogIndex = log.size() - 1;
            long myLastLogTerm = 0;
            if (myLastLogIndex >= 0) {
                myLastLogTerm = log.get((int) myLastLogIndex).term();
            }

            boolean logIsUpToDate = false;
            if (request.lastLogTerm() > myLastLogTerm) {
                logIsUpToDate = true;
            } else if (request.lastLogTerm() == myLastLogTerm && request.lastLogIndex() >= myLastLogIndex) {
                logIsUpToDate = true;
            }
            

            boolean voteGranted = false;

            if ((votedFor == null || votedFor.equals(request.candidateId())) && logIsUpToDate) {
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

        for (String peerID : peers) {
            nextIndex.put(peerID, log.size());
            matchIndex.put(peerID, -1);
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

    public boolean propose(T command) {
        lock.lock();
        try {
            if (currentRole != Role.LEADER) return false;
            LogEntry<T> entry = new LogEntry<>(currentTerm, command);
            log.add(entry);
            persist();
            return true;
        } finally {
            lock.unlock();
        }
    }

    public void updateCommitIndex() {
        List<Integer> indexes = new ArrayList<>();
        indexes.add(log.size() - 1);
        indexes.addAll(matchIndex.values());
        indexes.sort(Integer::compareTo);
        int commitThreshold = indexes.size() / 2;
        int N = indexes.get(commitThreshold);

        if (N > commitIndex && N < log.size()) {
            LogEntry<T> entry = log.get(N);
            if (entry.term() == currentTerm) {
                commitIndex = N;
                applyLog();
            }
        }
    }

    
    private void applyLog() {
        while (lastApplied <= commitIndex && lastApplied < log.size()) {
            if (log.isEmpty()) break;
            LogEntry<T> entry = log.get((int) lastApplied);
            
            
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
            }
        } catch (Exception e) {
            System.err.println("Error applying command: " + command);
        }
    }

    
    public String get(String key) {
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

}