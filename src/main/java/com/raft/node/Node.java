package com.raft.node;

import com.raft.core.LogEntry;
import com.raft.core.Role;
import com.raft.core.Snapshot;
import com.raft.core.Network;
import com.raft.rpc.*;
import com.raft.core.Storage;
import com.raft.core.WalStorage;
import com.google.gson.Gson;
import com.raft.core.PersistentState;
import com.raft.core.RaftMessageReceiver;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
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

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

public class Node<T> implements RaftMessageReceiver{

    // Metrics
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final Timer commitTimer = Timer.builder("raft.commit.latency").register(registry);
    private final Counter proposalCounter = registry.counter("raft.proposals.total");   

    private volatile boolean isSnapshotting = false;

    private final String nodeID;
    private final List<String> peers;
    private final Network network;
    private ExecutorService vThreadExecutor;
    private final ReentrantLock lock;
    private final Random random;
    private volatile boolean running;
    private final Storage<T> storage;

    private final AtomicLong lastElectionResetTime;
    private final int MIN_TIMEOUT_MS = 600;
    private final int MAX_TIMEOUT_MS = 1200;
    private final int heartbeatInterval = 150;
    private final Map<String, Long> clientSession = new ConcurrentHashMap<>();
    private int electionTimeout;
    private final ByteArrayOutputStream snapshotBuffer = new ByteArrayOutputStream();

    private long currentTerm;
    private int preVotesReceived;
    private String votedFor;
    private final List<LogEntry<T>> log;

    private Role currentRole;
    private int votesReceived;

    private Map<String, Integer> nextIndex;
    private Map<String, Integer> matchIndex;
    private volatile long commitIndex = -1;
    private volatile long lastApplied = 0;

    private long lastIncludedIndex = -1;
    private long lastIncludedTerm = 0;

    private volatile String currentLeaderID;
    private volatile boolean isRemovedFromCluster = false;

    
    private final Map<String, List<String>> stateMachine = new ConcurrentHashMap<>();

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

    /**
     * Synchronizes the node's current in-memory state with the persistent storage layer.
     * <p>This helper method serves as a wrapper around the {@link Storage#save} operation, 
     * ensuring that the most critical components of the Raft state—{@code currentTerm}, 
     * {@code votedFor}, and the command {@code log}—are written to non-volatile memory. 
     * Invoking this method is mandatory before responding to RPCs or transitioning states 
     * to maintain the "Stable Storage" guarantee required for cluster safety.</p>
     */
    private void persist(){
        storage.save(currentTerm, votedFor, log);
    }

    /**
     * Triggers the log compaction process by creating a snapshot of the current state machine.
     * <p>This method implements the Raft log compaction mechanism. It captures the state of the 
     * application up to the {@code lastApplied} index and persists it to stable storage. Once 
     * the snapshot is successfully saved, the in-memory log is pruned by removing all entries 
     * up to the snapshot index, significantly reducing memory consumption and recovery time. 
     * The process is thread-safe, protected by the node's main lock, and concludes by 
     * persisting the updated metadata.</p>
     *
     * <p><b>Constraints:</b>
     * <ul>
     * <li>The process exits early if the {@code lastApplied} index has not advanced beyond 
     * the previously existing snapshot.</li>
     * <li>The resulting log will only contain entries with an index greater than 
     * {@code snapshotIndex}.</li>
     * </ul>
     * </p>
     */
    public void takeSnapshot() {
        if (isSnapshotting) return;

        long snapshotIndex;
        long snapshotTerm;
        Snapshot newSnap;

        lock.lock();
        try {
            if (isSnapshotting) return;
            if (lastApplied <= lastIncludedIndex) return;

            snapshotIndex = lastApplied - 1;
            LogEntry<T> entry = getEntry(snapshotIndex);
            if (entry == null) return;
            snapshotTerm = entry.term();

            isSnapshotting = true;
            System.out.println("Node " + nodeID + " capturing snapshot state at index " + snapshotIndex);
            
            newSnap = new Snapshot(snapshotIndex, snapshotTerm, 
                new ConcurrentHashMap<>(stateMachine), 
                new ConcurrentHashMap<>(clientSession));
        } finally {
            lock.unlock();
        }

        try {
            storage.saveSnapshot(newSnap);

            lock.lock();
            try {
                if (snapshotIndex <= lastIncludedIndex) return; 

                int localCutIndex = getLocalIndex(snapshotIndex); 
                
                if (localCutIndex >= 0 && localCutIndex < log.size()) {
                    List<LogEntry<T>> remaining = new ArrayList<>(log.subList(localCutIndex + 1, log.size()));
                    log.clear();
                    log.addAll(remaining);
                } else {
                    log.clear();
                }

                lastIncludedIndex = snapshotIndex;
                lastIncludedTerm = snapshotTerm;

                persist(); 
            } finally {
                lock.unlock();
            }
        } finally {
            isSnapshotting = false;
        }
    }

    /**
     * Initiates the lifecycle of the Raft node, transitioning it to an active state.
     * <p>This method performs the following startup procedures:
     * <ul>
     * <li>Sets the {@code running} flag to prevent multiple concurrent starts.</li>
     * <li>Initializes a virtual thread executor (Project Loom) to handle asynchronous tasks 
     * with low overhead.</li>
     * <li>Resets the election timer to the current system time to avoid immediate 
     * unnecessary elections.</li>
     * <li>Spawns the background election loop to monitor leader heartbeats and 
     * trigger new elections as needed.</li>
     * </ul>
     * If the node is already running, the call returns immediately without further action.</p>
     */
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

    /**
     * Gracefully terminates the Raft node's operations and shuts down background processes.
     * <p>This method transitions the node to an inactive state by performing the following steps:
     * <ul>
     * <li>Updates the {@code running} flag to {@code false}, which halts any ongoing loops 
     * that check this status (e.g., heartbeats or elections).</li>
     * <li>Immediately shuts down the virtual thread executor, attempting to stop all 
     * active tasks and preventing new ones from being scheduled.</li>
     * <li>Releases the main lock to allow the node to be safely restarted or garbage collected.</li>
     * </ul>
     * If the node is already stopped, the method returns without taking any action.</p>
     */
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

    /**
     * The primary background loop responsible for monitoring election timeouts.
     * <p>This method runs continuously while the node is active, checking at frequent intervals (20ms) 
     * whether the time elapsed since the last leader contact or election reset has exceeded the 
     * randomized election timeout. When a timeout is detected, the node initiates the 
     * <b>Pre-Vote</b> phase to determine if it should transition to a Candidate state.</p>
     * * 
     * * <p>By using a tight polling loop and a randomized timeout, the node ensures high 
     * responsiveness to leader failures while minimizing the risk of split votes during 
     * simultaneous elections.</p>
     *
     * @see #startPreVote()
     * @see #lastElectionResetTime
     */
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

    /**
     * Initiates the Pre-Vote phase to assess the node's eligibility for becoming a candidate.
     * <p>The Pre-Vote phase is a safety optimization that prevents unnecessary term inflation. 
     * Before incrementing its {@code currentTerm}, the node sends a hypothetical request 
     * to its peers to see if they would grant a vote based on its current log completeness. 
     * If a majority (quorum) indicates they would vote for the node, it officially 
     * transitions to the Candidate state and starts a real election.</p>
     * * 
     *
     * <p><b>Execution Steps:</b>
     * <ol>
     * <li>Validates that the node is still part of the cluster and is not already the leader.</li>
     * <li>Resets the election timer and initializes the local vote count (self-vote).</li>
     * <li>Calculates the required quorum based on the current peer list.</li>
     * <li>Asynchronously broadcasts {@link PreVoteRequest} to all peers using virtual threads.</li>
     * </ol>
     * </p>
     *
     * @see #handlePreVoteResponse(PreVoteResponse)
     * @see #startElection()
     */
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
                        .exceptionally(ex -> {
    System.err.println("RPC Error during Peer Communication: " + ex.getMessage());
    return null;
})
                );
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Processes a Pre-Vote request from another node to evaluate its candidacy.
     * <p>The receiver provides a "not-binding" vote based on its own state without modifying 
     * its persistent term or voting record. This method implements the core safety logic 
     * of the Pre-Vote phase: a node is only considered eligible if its term is higher than 
     * the receiver's current term and its log is at least as up-to-date as the receiver's.</p>
     *
     * <p><b>Granting Rules:</b>
     * <ul>
     * <li>The vote is rejected if {@code nextTerm} is not strictly greater than {@code currentTerm}.</li>
     * <li>The vote is granted only if the candidate's log is "up-to-date". Log comparison is 
     * defined by two conditions:
     * <ol>
     * <li>If the logs have different last terms, the log with the higher term is more up-to-date.</li>
     * <li>If the logs end in the same term, the longer log (higher index) is more up-to-date.</li>
     * </ol>
     * </li>
     * </ul>
     * </p>
     * 
     *
     * @param request The {@link PreVoteRequest} containing the candidate's hypothetical term 
     * and log metadata.
     * @return A {@link PreVoteResponse} indicating the receiver's current term and whether 
     * it would grant its vote.
     * @throws RuntimeException If the node is currently stopped.
     */
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

    /**
     * Processes the response from a peer during the Pre-Vote phase.
     * <p>This callback is executed when a peer returns a {@link PreVoteResponse}. It serves two 
     * critical roles in the Raft safety cycle:
     * <ol>
     * <li><b>Term Synchronization:</b> If the peer's term is higher than the current node's 
     * term, the node immediately steps down to the Follower state and updates its term to 
     * match the cluster.</li>
     * <li><b>Quorum Collection:</b> If the peer grants the vote, the node increments its 
     * count. Once a majority (quorum) is reached, the node transitions to the 
     * <b>Candidate</b> state and triggers a formal election.</li>
     * </ol>
     * The method is synchronized via the node's main lock to ensure thread safety during 
     * state transitions.</p>
     *
     * @param response The {@link PreVoteResponse} containing the peer's current term and 
     * its decision on whether to grant the hypothetical vote.
     */
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

    /**
     * Initiates a formal election to become the cluster leader.
     * <p>This method marks the transition from Follower (or Candidate) to a new election 
     * cycle. Following the Raft protocol, the node increments its current term, votes 
     * for itself, and broadcasts {@code RequestVote} RPCs to all other nodes in the cluster. 
     * The election continues until the node wins the election, another node establishes 
     * leadership, or the election timeout elapses without a winner.</p>
     *
     * <p><b>Lifecycle Actions:</b>
     * <ul>
     * <li>Transitions {@code currentRole} to {@link Role#CANDIDATE}.</li>
     * <li>Increments {@code currentTerm} and persists the change to stable storage.</li>
     * <li>Votes for itself ({@code votedFor = nodeID}) and initializes the vote counter to 1.</li>
     * <li>Resets the election timer to start a new randomized timeout period.</li>
     * <li>Dispatches asynchronous {@link RequestVoteRequest} calls to all cluster peers.</li>
     * </ul>
     * </p>
     *
     * @see #handleVoteResponse(RequestVoteResponse)
     * @see #resetElectionTimer()
     */
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
                        .exceptionally(ex -> {
    System.err.println("RPC Error during Peer Communication: " + ex.getMessage());
    return null;
}) 
                );
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Maintains the leader's authority by managing the periodic heartbeat broadcast loop.
     * <p>This method runs continuously as long as the node maintains its {@link Role#LEADER} status 
     * and is operational. It ensures that {@code AppendEntries} RPCs (acting as heartbeats) 
     * are dispatched to all followers at a regular interval. These heartbeats are crucial 
     * for preventing followers from timing out and initiating unnecessary new elections.</p>
     *
     * <p><b>Loop Characteristics:</b>
     * <ul>
     * <li><b>Timing Precision:</b> Calculates execution time ({@code elapsed}) to subtract 
     * it from the {@code heartbeatInterval}, ensuring a consistent heartbeat frequency 
     * regardless of processing overhead.</li>
     * <li><b>Role Sensitivity:</b> The loop terminates automatically if the node steps 
     * down to a Follower or Candidate role.</li>
     * <li><b>Interruption Handling:</b> Respects thread interrupts to allow for a 
     * clean shutdown of the leader's background tasks.</li>
     * </ul>
     * </p>
     *
     * @see #sendHearthbeats()
     */
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

    /**
     * Dispatches synchronization RPCs to all peers to maintain leadership and replicate logs.
     * <p>This method implements the leader's responsibility to keep followers' logs consistent 
     * with its own. For each peer, the leader determines the appropriate type of communication 
     * based on the follower's progress relative to the leader's discarded log entries (snapshots):</p>
     *
     * <ul>
     * <li><b>AppendEntries:</b> Sent if the follower's required log entries are still present 
     * in the leader's active log. This serves as both a heartbeat and a replication mechanism.</li>
     * <li><b>InstallSnapshot:</b> Sent if the follower's {@code nextIndex} has been eclipsed 
     * by a snapshot, meaning the leader no longer possesses the specific log entries 
     * needed to bring the follower up to speed via standard replication.</li>
     * </ul>
     *
     * @see #sendAppendEntriesToPeer(String, int)
     * @see #sendSnapshotToPeer(String)
     */
    void sendHearthbeats() {
        lock.lock();
        try {
            if (currentRole != Role.LEADER) return;

            persist();

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

    /**
     * Initiates the transmission of a state machine snapshot to a lagging follower.
     * <p>This method is invoked when a follower's log is so far behind that the 
     * leader has already discarded the necessary log entries through compaction. 
     * To bring the follower back into synchronization, the leader serializes its 
     * current {@link Snapshot} and begins the transfer process. Due to potential 
     * snapshot size, the data is typically handled in chunks to avoid network 
     * congestion and RPC timeout issues.</p>
     *
     * @param peerID The unique identifier of the follower requiring the snapshot.
     * @see #serializeSnapshotState()
     * @see #sendSnapshotChunk(String, byte[], int)
     */
    private void sendSnapshotToPeer(String peerID) {
        byte[] snapshotBytes = serializeSnapshotState();
        sendSnapshotChunk(peerID, snapshotBytes, 0);
    }

    /**
     * Transmits a snapshot to a follower node in incremental chunks.
     * <p>This method implements the data transfer logic for the {@code InstallSnapshot} RPC. 
     * Since snapshots can be significantly larger than a single network packet, they are 
     * split into segments (default 4KB). This approach ensures reliable delivery, 
     * prevents memory exhaustion, and avoids blocking the RPC channel for long periods.</p>
     *
     * <p><b>Execution Flow:</b>
     * <ol>
     * <li>Calculates the current chunk boundary and extracts the corresponding byte range.</li>
     * <li>Constructs an {@link InstallSnapshotRequest} containing the metadata, current 
     * offset, the data chunk, and a {@code done} flag indicating if this is the final segment.</li>
     * <li>Asynchronously dispatches the request via the virtual thread executor.</li>
     * <li>Upon successful delivery, if the snapshot is not complete and the leader's 
     * term hasn't changed, recursively invokes itself to send the next chunk.</li>
     * </ol>
     * </p>
     *
     * @param peerID        The identifier of the node receiving the snapshot.
     * @param snapshotBytes The full byte array of the serialized state machine snapshot.
     * @param offset        The current starting byte position for the transmission.
     */
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
                .exceptionally(ex -> {
    System.err.println("RPC Error during Peer Communication: " + ex.getMessage());
    return null;
})
        );
    }

    /**
     * Processes the response from a follower after an InstallSnapshot RPC has been sent.
     * <p>This method updates the leader's internal tracking of the follower's progress. 
     * If the snapshot was successfully accepted, the follower's log is now synchronized up to 
     * the snapshot's last included index. Like all Raft RPC responses, it also serves as a 
     * term check to ensure the leader is still authoritative within the cluster.</p>
     *
     * <p><b>State Updates:</b>
     * <ul>
     * <li><b>Term Validation:</b> If the follower's term is higher, the leader immediately 
     * steps down to Follower status to maintain cluster safety.</li>
     * <li><b>Index Tracking:</b> Upon successful snapshot installation, the follower's 
     * {@code matchIndex} is updated to {@code lastIncludedIndex}, and the {@code nextIndex} 
     * is set to the subsequent entry. This allows standard log replication to resume 
     * from that point forward.</li>
     * </ul>
     * </p>
     * 
     * @param peerID   The identifier of the follower that sent the response.
     * @param response The {@link InstallSnapshotResponse} containing the follower's 
     * current term and acknowledgment.
     */
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

    /**
     * Dispatches an AppendEntries RPC to a specific peer to replicate log entries or serve as a heartbeat.
     * <p>This method prepares the consistency check metadata ({@code prevLogIndex} and {@code prevLogTerm}) 
     * required by the Raft protocol to ensure the follower's log matches the leader's. If the follower is 
     * behind, the leader includes all log entries starting from the follower's {@code nextIndex} up to 
     * the leader's latest entry. If no new entries are available, the request acts as a simple heartbeat 
     * to maintain leadership.</p>
     * 
     * <p><b>Implementation Details:</b>
     * <ul>
     * <li><b>Log Indexing:</b> Uses {@link #getTermForIndex} and {@link #getLocalIndex} to translate 
     * between global Raft indices and the current pruned in-memory log.</li>
     * <li><b>Batching:</b> Collects all entries from the identified starting point to the end of the log 
     * for efficient replication.</li>
     * <li><b>Asynchrony:</b> Executes the network call via the virtual thread executor and handles the 
     * result in {@link #handleAppendEntriesResponse}.</li>
     * </ul>
     * </p>
     *
     * @param peerID  The identifier of the target follower node.
     * @param nextIdx The index of the first log entry that the leader believes the follower needs.
     */
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
                .thenAccept(response -> handleAppendEntriesResponse(peerID, response, request))
                .exceptionally(ex -> {
    System.err.println("RPC Error during Peer Communication: " + ex.getMessage());
    return null;
}) 
        );
    }

    /**
     * Processes a RequestVote RPC from a candidate node to decide whether to grant a vote.
     * <p>This method implements the core safety requirements of the Raft election process. 
     * A vote is granted only if the candidate's term is current and its log is at least 
     * as up-to-date as the receiver's log. If the request comes from a candidate with a 
     * higher term, the receiver immediately updates its own term and reverts to the 
     * Follower state.</p>
     * * 
     * * <p><b>Voting Criteria:</b>
     * <ul>
     * <li><b>Term Check:</b> Rejects votes if {@code request.term < currentTerm}. 
     * If {@code request.term > currentTerm}, the node updates its term and resets its vote.</li>
     * <li><b>Candidate Check:</b> The node must not have already voted for a different 
     * candidate in the current term.</li>
     * <li><b>Log Completeness:</b> The candidate's log must be "up-to-date" compared to 
     * the receiver's log (comparing last entry term, then last entry index).</li>
     * </ul>
     * </p>
     *
     * @param request The {@link RequestVoteRequest} containing candidate metadata and log state.
     * @return A {@link RequestVoteResponse} indicating if the vote was granted and the receiver's current term.
     * @throws RuntimeException If the node is not in a running state.
     */
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

    /**
     * Processes the outcome of a RequestVote RPC from a peer.
     * <p>This method handles the asynchronous results of an election cycle. It manages 
     * the transition of the node's state based on the feedback from the cluster:
     * <ol>
     * <li><b>Term Advancement:</b> If a peer reports a higher term, the candidate 
     * immediately realizes its state is obsolete and reverts to a Follower.</li>
     * <li><b>Vote Tabulation:</b> If the node is still a candidate and the vote is granted, 
     * it increments the vote tally.</li>
     * <li><b>Election Victory:</b> Once the number of gathered votes reaches a majority 
     * (quorum), the node transitions to the Leader role.</li>
     * </ol>
     * The operation is performed under a lock to ensure that vote counting and role 
     * transitions are atomic and consistent across multiple concurrent RPC responses.</p>
     *
     * @param response The {@link RequestVoteResponse} received from a cluster peer.
     * @see #becomeLeader()
     */
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

    /**
     * Transitions the node to the Leader state after winning an election.
     * <p>This method initializes the data structures required to manage log replication 
     * and cluster synchronization. According to the Raft protocol, a new leader must 
     * re-initialize the {@code nextIndex} and {@code matchIndex} for all followers 
     * to ensure that consistency checks can begin from the most recent known log entry.</p>
     *
     * <p><b>State Transitions:</b>
     * <ul>
     * <li>Updates {@code currentRole} to {@link Role#LEADER}.</li>
     * <li>Resets replication trackers:
     * <ul>
     * <li><b>nextIndex:</b> Initialized to the index immediately following the 
     * leader's last known log entry (including any snapshotted entries).</li>
     * <li><b>matchIndex:</b> Initialized to -1 (or 0) for all peers, as the 
     * leader does not yet know which indices are safely replicated on followers.</li>
     * </ul>
     * </li>
     * <li>Launches the {@link #runHearthbeatLoop()} in a virtual thread to establish authority 
     * and prevent followers from timing out.</li>
     * </ul>
     * </p>
     * 
     */
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

    /**
     * Processes log replication and heartbeat requests (AppendEntries RPC) from the cluster leader.
     * <p>This method implements the core receiver-side logic of the Raft replication algorithm. 
     * It ensures that the follower's log remains a consistent prefix of the leader's log and 
     * manages state transitions, election timer resets, and command commitment. The method 
     * also handles specific configuration changes, such as node removal.</p>
     *
     * <p><b>Execution Logic:</b>
     * <ol>
     * <li><b>Term Validation:</b> Rejects requests from leaders with a stale term. If the 
     * leader's term is current or newer, the node acknowledges the leader and transitions 
     * to the Follower role.</li>
     * <li><b>Consistency Check:</b> Verifies that the local log contains an entry at 
     * {@code prevLogIndex} with a matching {@code prevLogTerm}. If not, the request 
     * is rejected to force the leader to retry with an earlier index.</li>
     * <li><b>Log Reconciliation:</b> Appends new entries from the request. If an existing 
     * entry conflicts with a new one (same index but different term), it deletes the 
     * existing entry and all that follow it before appending the new ones.</li>
     * <li><b>Commitment:</b> Advances the local {@code commitIndex} to match the leader's 
     * commit point (capped by the local log size) and triggers {@link #applyLog()} to 
     * execute commands.</li>
     * </ol>
     * </p>
     * 
     *
     * @param request The {@link AppendEntriesRequest} containing the leader's term, log 
     * entries, and commitment metadata.
     * @return An {@link AppendEntriesResponse} indicating success or failure and the 
     * receiver's current term.
     * @throws RuntimeException If the node is not currently running.
     */
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
            boolean logChanged = false;

            for (LogEntry<T> entry : newEntries) {
                if (indexToInsert < log.size()) {
                    LogEntry<T> existingEntry = log.get((int) indexToInsert);
                    if (existingEntry.term() != entry.term()) {
                        log.subList((int) indexToInsert, log.size()).clear();
                        log.add(entry);
                        logChanged = true;
                    }
                } else {
                    log.add(entry);
                    logChanged = true;
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

            if (logChanged) persist();

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

    /**
     * Processes the response from a follower after an AppendEntries RPC.
     * <p>This method manages the leader's view of each follower's log progress. It handles 
     * both successful replications and consistency failures, adjusting the transmission 
     * strategy accordingly. This feedback loop is the primary mechanism Raft uses to 
     * bring all cluster logs into a synchronized, identical state.</p>
     *
     * <p><b>Response Handling:</b>
     * <ul>
     * <li><b>Term Conflict:</b> If the follower reports a higher term, the leader 
     * immediately step downs to Follower status to maintain cluster safety.</li>
     * <li><b>Success:</b> The follower's {@code nextIndex} and {@code matchIndex} are 
     * advanced based on the number of entries successfully appended. The leader 
     * then attempts to advance the global {@code commitIndex}.</li>
     * <li><b>Consistency Failure:</b> If the follower rejected the request (likely due 
     * to a log mismatch), the leader decrements {@code nextIndex} for that peer. 
     * This triggers a retry with an earlier portion of the log during the next 
     * heartbeat or replication cycle.</li>
     * </ul>
     * </p>
     * 
     *
     * @param peerID         The identifier of the follower that sent the response.
     * @param response       The {@link AppendEntriesResponse} containing the term and success status.
     * @param numEntriesSent The number of log entries that were included in the original request.
     */
    private void handleAppendEntriesResponse(String peerID, AppendEntriesResponse response, AppendEntriesRequest<T> request) {
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

                int match = (int) (request.prevLogIndex() + request.entries().size());
                
                if (match > matchIndex.getOrDefault(peerID, -1)){
                    matchIndex.put(peerID, match);
                    nextIndex.put(peerID, match +1);
                    updateCommitIndex();
                }
                updateCommitIndex();
            } else {
                int currentNext = nextIndex.get(peerID);
                if (currentNext > 0) {
                    nextIndex.put(peerID, Math.max(1, (int) request.prevLogIndex()));
                    vThreadExecutor.submit(() -> sendAppendEntriesToPeer(peerID, nextIndex.get(peerID)));
                }
            }
        } finally {
            lock.unlock();
        }
    }

    public boolean propose(String clientID, long sequenceNum, T command) {
        proposalCounter.increment();
        Timer.Sample sample = Timer.start(registry);
        
        long indexAwaiting;
        lock.lock();
        try {
            if (currentRole != Role.LEADER) return false;

            if (clientSession.getOrDefault(clientID, -1L) >= sequenceNum){
                return true;
            }

            LogEntry<T> entry = new LogEntry<>(currentTerm, clientID, sequenceNum, command);
            log.add(entry);
            indexAwaiting = lastIncludedIndex + log.size();
            //persist();
        } finally {
            lock.unlock(); 
        }
        
        //vThreadExecutor.submit(this::sendHearthbeats);

        boolean success = waitForCommit(indexAwaiting);
        if (success) {
            sample.stop(commitTimer);
        }
        return success;
    }

private boolean waitForCommit(long index) {
    long start = System.currentTimeMillis();
    while (System.currentTimeMillis() - start < 10000) { 
        if (getCommitIndex() >= index) {
            return true;
        }
        try { 
            Thread.sleep(10); 
        } catch (InterruptedException e) { 
            Thread.currentThread().interrupt();
            break; 
        }
    }
    return false;
}

    /**
     * Updates the leader's commit index by identifying the highest log entry replicated on a majority of nodes.
     * <p>This method implements the Leader Commitment Rule from the Raft paper. The leader cannot 
     * commit an entry simply because it is stored on a majority of servers; it must also be 
     * from the leader's current term to protect against the "Replication from Previous Terms" 
     * safety hazard.</p>
     * * 
     * * <p><b>Commitment Logic:</b>
     * <ol>
     * <li><b>Quorum Calculation:</b> Collects the {@code matchIndex} from all peers plus the 
     * leader's own last log index. By sorting these indices and selecting the median 
     * (index at {@code size/2}), the leader identifies the highest index {@code N} 
     * replicated on at least a majority of the cluster.</li>
     * <li><b>Safety Constraint (Term):</b> Verification that {@code log[N].term == currentTerm}. 
     * This ensures that the leader only advances the {@code commitIndex} for entries 
     * created during its own term, which implicitly commits prior entries due to the 
     * Log Matching Property.</li>
     * <li><b>Application:</b> Once a valid {@code N} is found, the {@code commitIndex} 
     * is updated, and {@link #applyLog()} is called to push these committed commands 
     * to the state machine.</li>
     * </ol>
     * </p>
     * * 
     *
     * @see #applyLog()
     * @see #matchIndex
     */
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

    /**
     * Executes committed log entries by applying them to the local state machine.
     * <p>This method ensures that every log entry between the {@code lastApplied} index and 
     * the {@code commitIndex} is processed in order. By following this sequence, the 
     * Raft node guarantees that the state machine remains consistent with the rest 
     * of the cluster. Once a command is applied, the {@code lastApplied} index is 
     * incremented to reflect the node's progress.</p>
     *
     * <p><b>Execution Process:</b>
     * <ul>
     * <li>Iterates through the log starting from the next unapplied entry.</li>
     * <li>Retrieves the entry using {@link #getEntry}, which accounts for log pruning 
     * and snapshots.</li>
     * <li>Dispatches the command to the appropriate execution handler (e.g., 
     * {@link #applyCommand} for String-based operations).</li>
     * <li>Continues until the {@code lastApplied} index catches up to the 
     * {@code commitIndex} or an entry is unavailable.</li>
     * </ul>
     * </p>
     */
    private void applyLog() {
        boolean appliedAny = false;
        while (lastApplied <= commitIndex) {
            LogEntry<T> entry = getEntry(lastApplied);
            if (entry == null) break;
            
            if (entry.command() instanceof String cmd) {
                applyCommand(cmd);
            } else {
                System.out.println("NODE " + nodeID + " EXECUTED GENERIC: " + entry.command());
            }

            clientSession.put(entry.clientID(), entry.sequenceNum());

            lastApplied++;
            appliedAny = true;
        }

        if (appliedAny && log.size() > 60000){
            vThreadExecutor.submit(this::takeSnapshot);
        }
    }

    /**
     * Interprets and executes a committed command string, updating the state machine or cluster configuration.
     * <p>This method acts as the final stage of the Raft pipeline, where a consensually agreed-upon 
     * operation is actually performed. It distinguishes between two primary types of commands:
     * <ol>
     * <li><b>Application Data:</b> Operations like {@code SEND}, which update the internal 
     * message-room state machine.</li>
     * <li><b>Membership Changes:</b> Operations like {@code CONF_ADD_SERVER} or 
     * {@code CONF_REMOVE_SERVER}, which dynamically modify the cluster topology.</li>
     * </ol>
     * The method ensures that if the node is currently a leader, its replication tracking 
     * structures ({@code nextIndex} and {@code matchIndex}) remain synchronized with the 
     * updated peer list.</p>
     *
     * <p><b>Safety and Membership Logic:</b>
     * <ul>
     * <li><b>Self-Removal:</b> If a node detects its own removal from the cluster via a 
     * {@code CONF_REMOVE_SERVER} command, it must step down and disable its election 
     * capabilities to avoid disrupting the remaining majority.</li>
     * <li><b>Concurrency:</b> Uses thread-safe collections like {@link CopyOnWriteArrayList} 
     * to ensure state machine integrity during concurrent reads/writes.</li>
     * </ul>
     * </p>
     * 
     */
    private void applyCommand(String command) {
        try {
            if (command.startsWith("SEND ")) {
                int firstSpace = command.indexOf(' ', 5);
                if (firstSpace != -1) {
                    String room = command.substring(5, firstSpace).trim();
                    String message = command.substring(firstSpace + 1).trim();
                    
                    stateMachine.computeIfAbsent(room, k -> new CopyOnWriteArrayList<>()).add(message);
                    //System.out.println("💬 NODE " + nodeID + " added a message in room [" + room + "]");
                }
            } else if (command.startsWith("CONF_ADD_SERVER=")) {
                String newPeer = command.substring(16).trim();
                if (!peers.contains(newPeer) && !newPeer.equals(nodeID)) {
                    peers.add(newPeer);
                    //System.out.println("🔧 NODE " + nodeID + " ADDED PEER: " + newPeer);
                    
                    if (currentRole == Role.LEADER) {
                        nextIndex.putIfAbsent(newPeer, (int) (lastIncludedIndex + log.size() + 1));
                        matchIndex.putIfAbsent(newPeer, -1);
                    }
                }
            } else if (command.startsWith("CONF_REMOVE_SERVER=")) {
                String oldPeer = command.substring(19).trim();
                peers.remove(oldPeer);
                //System.out.println("🔧 NODE " + nodeID + " REMOVED PEER: " + oldPeer);
                
                if (currentRole == Role.LEADER) {
                    nextIndex.remove(oldPeer);
                    matchIndex.remove(oldPeer);
                }
                
                if (oldPeer.equals(nodeID)) {
                    //System.out.println("NODE " + nodeID + " removed from cluster. Stepping down.");
                    currentRole = Role.FOLLOWER;
                    isRemovedFromCluster = true;
                }
            }
        } catch (Exception e) {
            System.err.println("Error applying command: " + command);
        }
    }

    /**
     * Executes a linearizable read-only request to retrieve messages from a specific room.
     * <p>This method implements the <b>ReadIndex</b> optimization for linearizable reads. 
     * To ensure the result reflects the most recent committed state without the overhead 
     * of adding a new entry to the log, the leader follows these steps:</p>
     *
     * <ol>
     * <li><b>Leader Redirection:</b> If this node is not the leader, it forwards the 
     * request to the known leader. If no leader is known, it rejects the request.</li>
     * <li><b>ReadIndex Capture:</b> The leader records its current {@code commitIndex} 
     * as the {@code readIndex}.</li>
     * <li><b>Leadership Confirmation:</b> The leader heartbeats a majority of the 
     * cluster to verify it hasn't been deposed (avoiding stale reads from a 
     * partitioned former leader).</li>
     * <li><b>Application Sync:</b> The leader waits until its {@code lastApplied} 
     * index catches up to the {@code readIndex} to ensure all prior writes are 
     * visible to this read.</li>
     * <li><b>State Retrieval:</b> Reads the data directly from the state machine.</li>
     * </ol>
     * 
     * @param room The identifier of the message room to query.
     * @return A JSON-serialized list of messages in the room.
     * @throws RuntimeException If leadership cannot be confirmed, no leader is 
     * known, or the thread is interrupted during the synchronization wait.
     */
    public String get(String room) {
        if (getRole() != Role.LEADER) {
            String leader = currentLeaderID;
            if (leader == null) 
                throw new RuntimeException("Service Unavailable: NO Leader Known");
            
            try{
                return network.sendClientGet(leader, room).join();
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

        List<String> messages = stateMachine.get(room);
        if (messages == null || messages.isEmpty()) {
            return "[]";
        }
        return new Gson().toJson(messages);
    }
    
    /**
     * Resets the election timer with a new randomized timeout value.
     * <p>This method is crucial for cluster stability and preventing "split vote" scenarios. 
     * By assigning a random duration between {@code MIN_TIMEOUT_MS} and {@code MAX_TIMEOUT_MS}, 
     * it ensures that nodes do not time out and start elections simultaneously, which would 
     * otherwise lead to repeated failed election cycles where no candidate can gather a quorum.</p>
     *
     * <p><b>Usage Contexts:</b>
     * <ul>
     * <li><b>Initialization:</b> When a node first starts.</li>
     * <li><b>Leader Contact:</b> Upon receiving a valid {@code AppendEntries} or 
     * {@code InstallSnapshot} RPC from the current leader.</li>
     * <li><b>Candidacy:</b> When a node grants a vote to another candidate or 
     * starts its own election cycle.</li>
     * </ul>
     * </p>
     */
    public void resetElectionTimer() {
        this.electionTimeout = MIN_TIMEOUT_MS + random.nextInt(MAX_TIMEOUT_MS - MIN_TIMEOUT_MS);
        this.lastElectionResetTime.set(System.currentTimeMillis());
    }
    
    /**
     * Retrieves the current role of the node within the Raft cluster in a thread-safe manner.
     * <p>The node can be in one of three states: <b>Follower</b>, <b>Candidate</b>, or <b>Leader</b>. 
     * Since the role can be transitioned by various background tasks (such as election timeouts, 
     * incoming heartbeats, or quorum discovery), this getter uses the node's primary lock 
     * to ensure the returned value is consistent with the rest of the internal state.</p>
     *
     * @return The current {@link Role} of the node.
     */
    public Role getRole() {
        lock.lock();
        try { return currentRole; } finally { lock.unlock(); }
    }

    /**
     * Retrieves the current term of the node in a thread-safe manner.
     * <p>In the Raft protocol, terms act as a logical clock, allowing nodes to detect 
     * obsolete information such as stale leaders or candidates. Terms are monotonically 
     * increasing integers, and each node stores its {@code currentTerm} on stable storage 
     * to ensure consistency across restarts.</p>
     *
     * <p><b>Usage in Protocol:</b>
     * <ul>
     * <li><b>Request Validation:</b> If a node receives a request with a term smaller than 
     * its current term, it rejects the request.</li>
     * <li><b>State Transition:</b> If a node's current term is smaller than a term 
     * in an incoming request, the node updates its term and reverts to the Follower state.</li>
     * </ul>
     * </p>
     *
     * @return The current logical epoch (term) of the node.
     */
    public long getTerm() {
        lock.lock();
        try { return currentTerm; } finally { lock.unlock(); }
    }

    /**
     * Retrieves the unique identifier of this node within the Raft cluster.
     * <p>The Node ID is a critical component of the cluster configuration, used by peers to 
     * distinguish between different servers. It is included in every RPC (such as 
     * {@code RequestVote} and {@code AppendEntries}) to identify the sender and is used 
     * by followers to keep track of the current leader's identity.</p>
     *
     * <p><b>Protocol Significance:</b>
     * <ul>
     * <li><b>Election Safety:</b> Used in the {@code votedFor} field to ensure a node 
     * votes for only one candidate per term.</li>
     * <li><b>Leadership:</b> Used by clients and followers to identify the target 
     * for redirected requests or log replication.</li>
     * <li><b>Configuration:</b> Serves as the key in maps like {@code nextIndex} and 
     * {@code matchIndex} on the leader node.</li>
     * </ul>
     * </p>
     *
     * @return The unique string identifier assigned to this node.
     */
    public String getNodeID() {
        lock.lock();
        try { return nodeID; } finally { lock.unlock(); }
    }

    /**
     * Provides a point-in-time, thread-safe snapshot of the local unpruned log entries.
     * <p>This method creates a shallow copy of the active {@link LogEntry} list. It is primarily 
     * used for diagnostics, state machine synchronization, or generating manual snapshots. 
     * By returning a new {@code ArrayList}, it prevents external callers from inadvertently 
     * modifying the node's internal log or causing a {@link ConcurrentModificationException} 
     * if the log is truncated or appended to during iteration.</p>
     *
     * <p><b>Implementation Notes:</b>
     * <ul>
     * <li><b>Scope:</b> This copy contains only the entries currently held in memory. It 
     * does <i>not</i> include entries that have been discarded following a successful 
     * snapshot (indices prior to {@code lastIncludedIndex}).</li>
     * <li><b>Thread Safety:</b> The operation is wrapped in the node's primary lock to 
     * ensure the list is not modified while the copy is being performed.</li>
     * <li><b>Memory Usage:</b> While the list itself is new, the {@link LogEntry} 
     * instances within the list are shared. Since log entries are typically immutable 
     * in Raft, this is both safe and efficient.</li>
     * </ul>
     * </p>
     *
     * @return A new {@link List} containing the current sequence of uncompacted {@link LogEntry} objects.
     */
    public List<LogEntry<T>> getLogCopy() {
        lock.lock();
        try { return new ArrayList<>(log); } finally { lock.unlock(); }
    }

    /**
     * Retrieves the index of the highest log entry known to be committed.
     * <p>The {@code commitIndex} represents the boundary of "safe" data in the Raft log. 
     * An entry is considered committed once the leader has successfully replicated it 
     * on a majority of the cluster. This index is volatile and is rebuilt after a 
     * restart by communicating with the cluster leader or through state machine recovery.</p>
     *
     * <p><b>Protocol Role:</b>
     * <ul>
     * <li><b>Application Trigger:</b> When the {@code commitIndex} advances beyond the 
     * {@code lastApplied} index, the node executes the corresponding commands in its 
     * state machine.</li>
     * <li><b>Follower Updates:</b> Followers update their {@code commitIndex} based on the 
     * {@code leaderCommit} value sent in {@code AppendEntries} RPCs.</li>
     * <li><b>Read Safety:</b> Used to determine the "Read Index" for linearizable read 
     * operations, ensuring clients see the most recent committed state.</li>
     * </ul>
     * </p>
     *
     * @return The current logical commit index.
     */
    public long getCommitIndex() {
        return commitIndex;
    }

    /**
     * Retrieves the index of the highest log entry currently applied to the state machine.
     * <p>The {@code lastApplied} index tracks the execution progress of the node's 
     * deterministic state machine. While {@code commitIndex} represents the entries 
     * guaranteed to be safe and permanent across the cluster, {@code lastApplied} 
     * indicates which of those entries have actually been processed by the local 
     * application logic (e.g., updating a database or a key-value store).</p>
     *
     * <p><b>Execution Dynamics:</b>
     * <ul>
     * <li><b>Sequence:</b> {@code lastApplied} is always less than or equal to 
     * {@code commitIndex}. The node continuously works to bridge this gap in the 
     * {@link #applyLog()} loop.</li>
     * <li><b>Linearizability:</b> Read-only operations wait for {@code lastApplied} 
     * to catch up to a specific {@code readIndex} to ensure the client observes 
     * the most recent committed changes.</li>
     * <li><b>Volatility:</b> Like {@code commitIndex}, this value is volatile and 
     * starts at 0 (or the snapshot index) upon node restart, as the state machine 
     * is typically rebuilt from the log or a snapshot.</li>
     * </ul>
     * </p>
     *
     * @return The current logical index of the last command executed by the state machine.
     */
    public long getLastApplied() {
        return lastApplied;
    }

    /**
     * Translates a global Raft log index into the corresponding local index within the in-memory log list.
     * <p>Because Raft logs are periodically compacted into snapshots, the in-memory {@code log} list 
     * does not start at index 0 in the global sequence. This helper method performs the necessary 
     * coordinate transformation by subtracting the {@code lastIncludedIndex} (the index of the last 
     * entry contained within the latest snapshot) and the 1-based offset required by the Raft protocol.</p>
     *
     * <p><b>Index Transformation Formula:</b>
     * <br>The local index $i$ is derived from the global Raft index $R$ using:
     * $$i = R - \text{lastIncludedIndex} - 1$$
     * </p>
     *
     * <p><b>Mathematical Context:</b>
     * <ul>
     * <li><b>Global Index (R):</b> The monotonically increasing index defined by the Raft protocol.</li>
     * <li><b>lastIncludedIndex:</b> The index of the most recent entry replaced by a snapshot.</li>
     * <li><b>Local Index:</b> The physical offset within the {@code List<LogEntry<T>> log} object.</li>
     * </ul>
     * </p>
     *
     * @param raftLogIndex The absolute 1-based Raft index of the log entry.
     * @return The 0-based index used to access the entry in the local {@code log} list.
     * @see #getEntry(long)
     */
    private int getLocalIndex(long raftLogIndex) {
        return (int) (raftLogIndex - lastIncludedIndex - 1);
    }

    /**
     * Retrieves a specific log entry from the in-memory log using its global Raft index.
     * <p>This helper method bridges the gap between the absolute Raft log index (which is 
     * monotonically increasing and 1-based) and the local storage list. It accounts for 
     * log compaction by utilizing {@link #getLocalIndex(long)} to find the relative 
     * offset of the entry after previous entries have been snapshotted and discarded.</p>
     *
     * <p><b>Retrieval Logic:</b>
     * <ul>
     * <li><b>Index Mapping:</b> Converts the {@code raftLogIndex} to a local list index.</li>
     * <li><b>Bounds Checking:</b> Ensures the requested index exists within the current 
     * memory-resident log fragment. If the index refers to an entry that has been 
     * snapshotted (local index < 0) or an entry not yet received (local index >= size), 
     * it returns {@code null}.</li>
     * <li><b>Data Access:</b> Returns the {@link LogEntry} if the bounds check passes.</li>
     * </ul>
     * </p>
     *
     * @param raftLogIndex The absolute 1-based index of the entry in the global Raft log.
     * @return The {@link LogEntry} at the specified index, or {@code null} if the entry 
     * is not currently held in memory.
     */
    private LogEntry<T> getEntry(long raftLogIndex) {
        int localIdx = getLocalIndex(raftLogIndex);
        if (localIdx < 0 || localIdx >= log.size()) {
            return null; 
        }
        return log.get(localIdx);
    }

    /**
     * Retrieves the term associated with a specific log index, accounting for snapshots.
     * <p>This helper method is fundamental for the Raft consistency check (the 
     * {@code prevLogTerm} in {@code AppendEntries}). It resolves the term for any given 
     * index by checking three potential sources:
     * <ol>
     * <li><b>The Beginning of Time:</b> Returns 0 if the index is -1 (pre-log state).</li>
     * <li><b>The Snapshot Boundary:</b> Returns the {@code lastIncludedTerm} if the 
     * index exactly matches the last entry processed into a snapshot.</li>
     * <li><b>The Active Log:</b> Retrieves the entry from the in-memory log and returns 
     * its term.</li>
     * </ol>
     * If the index is not found in any of these locations (e.g., it has been pruned 
     * or doesn't exist yet), it returns 0.</p>
     *
     * [Image of Raft log structure with terms and indices across snapshots]
     *
     * @param index The absolute 1-based Raft log index.
     * @return The term associated with the entry at that index, or 0 if no entry is found.
     * @see #getEntry(long)
     */
    private long getTermForIndex(long index) {
        if (index == -1) return 0;
        if (index == lastIncludedIndex) {
            return lastIncludedTerm;
        }
        LogEntry<T> entry = getEntry(index);
        return (entry != null) ? entry.term() : 0;
    }

    /**
     * Calculates the absolute Raft index of the latest entry in the node's log.
     * <p>In a system with log compaction, the total log is split between a persistent 
     * <b>Snapshot</b> and an in-memory <b>Suffix</b>. This method reconstructs the 
     * logical "Last Log Index" by adding the number of entries currently in the 
     * memory-resident list to the index of the last entry that was compressed 
     * into the most recent snapshot.</p>
     *
     * <p><b>Index Composition:</b>
     * <ul>
     * <li><b>lastIncludedIndex:</b> The index of the final entry included in the 
     * last snapshot (0 if no snapshot exists).</li>
     * <li><b>log.size():</b> The count of uncompacted entries added since the 
     * last snapshot was taken.</li>
     * </ul>
     * </p>
     * 
     *
     * @return The 1-based absolute index of the most recent log entry.
     * @see #getLastLogTerm()
     * @see #compactLog()
     */
    private long getLastLogIndex() {
        return lastIncludedIndex + log.size();
    }

    /**
     * Retrieves the term associated with the absolute last entry in the node's log.
     * <p>In the Raft protocol, the pair ({@code lastLogIndex}, {@code lastLogTerm}) 
     * defines the "up-to-dateness" of a node's log. This information is used during 
     * election cycles to ensure that a candidate's log is at least as complete as 
     * the voter's log before a vote is granted.</p>
     *
     * 
     *
     * <p><b>Resolution Strategy:</b>
     * <ul>
     * <li><b>Snapshot State:</b> If the in-memory log suffix is empty, the term of the 
     * last known entry is recovered from the {@code lastIncludedTerm} of the 
     * latest snapshot.</li>
     * <li><b>Active Log State:</b> If there are uncompacted entries in memory, the 
     * term is extracted directly from the final element in the {@code log} list.</li>
     * </ul>
     * </p>
     *
     * @return The term of the highest-indexed entry in the entire log (including snapshots).
     * @see #getLastLogIndex()
     * @see #lastIncludedTerm
     */
    private long getLastLogTerm() {
        if (log.isEmpty()) {
            return lastIncludedTerm;
        }
        return log.get(log.size() - 1).term();
    }

    /**
     * Processes a snapshot installation request from the leader.
     * <p>This method is invoked when a follower's log lags so far behind the leader that the 
     * necessary log entries have already been discarded and replaced by a snapshot. 
     * The follower receives the snapshot in chunks, reconstructs its state machine, 
     * and truncates its log accordingly.</p>
     *
     * <p><b>Execution Workflow:</b>
     * <ol>
     * <li><b>Term Validation:</b> Rejects requests from stale leaders. If the leader's 
     * term is newer, the node updates its term and reverts to the Follower role.</li>
     * <li><b>Chunk Buffering:</b> Resets the internal {@code snapshotBuffer} at offset 0 
     * and appends incoming data. If the {@code done} flag is false, it acknowledges 
     * the chunk and waits for more.</li>
     * <li><b>State Restoration:</b> Once the full snapshot is received, it deserializes 
     * the data to overwrite the current state machine and client session mapping.</li>
     * <li><b>Log Reconciliation:</b> 
     * <ul>
     * <li>If the snapshot contains a prefix of the local log, the matching entries 
     * are discarded, but subsequent entries are kept.</li>
     * <li>If the snapshot is entirely new or spans past the local log, the 
     * entire local log is cleared.</li>
     * </ul>
     * </li>
     * <li><b>Watermark Updates:</b> Updates {@code lastIncludedIndex}, {@code lastIncludedTerm}, 
     * and advances {@code lastApplied} and {@code commitIndex} to reflect the snapshot boundary.</li>
     * <li><b>Persistence:</b> Saves the new snapshot and metadata to stable storage.</li>
     * </ol>
     * </p>
     *
     * @param request The {@link InstallSnapshotRequest} containing chunk data and metadata.
     * @return An {@link InstallSnapshotResponse} used by the leader to track the follower's term.
     * @throws RuntimeException If the node is not currently running.
     */
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

    /**
     * Verifies that the current node still maintains a majority quorum as the cluster leader.
     * <p>This method implements the leadership confirmation step required for <b>linearizable reads</b>. 
     * Even if a node believes it is the leader, a network partition could have occurred, 
     * leading to the election of a new leader. To prevent "stale reads," the node sends a 
     * round of heartbeats (empty {@code AppendEntries} requests) to its peers and must 
     * receive acknowledgments from a majority before considering its leadership "confirmed."</p>
     *
     * <p><b>Verification Process:</b>
     * <ol>
     * <li><b>Immediate Check:</b> If the node is already aware it is not the leader, 
     * it returns {@code false} immediately.</li>
     * <li><b>Parallel Heartbeats:</b> Dispatches heartbeat RPCs to all peers using 
     * virtual threads to maximize concurrency and minimize latency.</li>
     * <li><b>Quorum Tabulation:</b> Uses an {@link AtomicInteger} to count successful 
     * responses. The node starts with 1 vote (itself). Once the count reaches a 
     * majority ($$N/2 + 1$$), the {@code CompletableFuture} is completed with {@code true}.</li>
     * <li><b>Term Safety:</b> If any peer responds with a higher term, the node 
     * acknowledges it is no longer the valid leader and completes with {@code false}.</li>
     * <li><b>Timeout Guard:</b> A watchdog task ensures the read request doesn't hang 
     * indefinitely if a quorum cannot be reached, failing the confirmation after 
     * half of an election timeout.</li>
     * </ol>
     * </p>
     *
     * @return A {@link CompletableFuture<Boolean>} that resolves to {@code true} if 
     * leadership is confirmed by a majority, or {@code false} otherwise.
     */
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
                    .exceptionally(ex -> {
    System.err.println("RPC Error during Peer Communication: " + ex.getMessage());
    return null;
})
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

    /**
     * Transforms the current volatile state machine and client sessions into a byte array for snapshotting.
     * <p>This method captures a consistent point-in-time image of the node's application state. 
     * To ensure the integrity of the snapshot during the serialization process, it creates 
     * defensive copies of the {@code stateMachine} and {@code clientSession} maps. This 
     * prevents {@link java.util.ConcurrentModificationException} and ensures that 
     * ongoing operations do not result in a corrupted or partial snapshot.</p>
     *
     * <p><b>Data Components Serialized:</b>
     * <ul>
     * <li><b>State Machine:</b> The actual application data (e.g., the message rooms 
     * and their contents).</li>
     * <li><b>Client Sessions:</b> The mapping of client IDs to their last processed 
     * sequence numbers, which is essential for maintaining linearizability and 
     * idempotency after a state restoration.</li>
     * </ul>
     * </p>
     * 
     *
     * @return A {@code byte[]} representing the serialized state of the node.
     * @throws RuntimeException if an I/O error occurs during the serialization process.
     * @see #deserializeAndApplySnapshotState(byte[])
     */
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

    /**
     * Restores the node's state machine and client sessions from a serialized byte array.
     * <p>This method performs the inverse operation of {@link #serializeSnapshotState()}. 
     * It is invoked during a node restart or when a follower receives an 
     * {@code InstallSnapshot} RPC from the leader. By overwriting the current 
     * volatile state with the snapshot data, the node instantly catches up to a 
     * specific point in the cluster's history without replaying thousands of 
     * individual log entries.</p>
     *
     * <p><b>Restoration Logic:</b>
     * <ol>
     * <li><b>Stream Initialization:</b> Wraps the byte array in a {@link ByteArrayInputStream} 
     * to facilitate object-level deserialization.</li>
     * <li><b>Type Reconstruction:</b> Uses {@link ObjectInputStream} to extract the 
     * {@code stateMachine} and {@code clientSession} maps. The {@code @SuppressWarnings("unchecked")} 
     * annotation is used here as Java serialization cannot verify generic types at runtime.</li>
     * <li><b>State Overwrite:</b> Clears the current in-memory maps and populates them 
     * with the parsed data, ensuring the node's state perfectly matches the snapshot.</li>
     * </ol>
     * </p>
     *
     * @param bytes The serialized byte array containing the state machine and session metadata.
     * @throws RuntimeException If deserialization fails due to version mismatches, 
     * invalid data, or class-not-found errors.
     */
    @SuppressWarnings("unchecked")
    private void deserializeAndApplySnapshotState(byte[] bytes) {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
             ObjectInputStream ois = new ObjectInputStream(bais)) {
            
            Map<String, List<String>> parsedStateMachine = (Map<String, List<String>>) ois.readObject();
            Map<String, Long> parsedClientSession = (Map<String, Long>) ois.readObject();
            
            this.stateMachine.clear();
            this.stateMachine.putAll(parsedStateMachine);
            
            this.clientSession.clear();
            this.clientSession.putAll(parsedClientSession);
            
        } catch (Exception e) {
            throw new RuntimeException("Error deserializing snapshot state", e);
        }
    }
    
    /**
     * Initiates the process of adding a new server to the Raft cluster configuration.
     * <p>This method implements the <b>Single-Server Membership Change</b> protocol. 
     * To ensure cluster safety and prevent split-brain scenarios where two different 
     * majorities could exist simultaneously, Raft requires that configuration changes 
     * are processed one at a time. This node must be the leader to propose such a change.</p>
     *
     * <p><b>Execution Safety Checks:</b>
     * <ol>
     * <li><b>Leadership Verification:</b> Only the current leader can modify the 
     * cluster membership.</li>
     * <li><b>Duplicate Prevention:</b> If the peer is already part of the cluster, 
     * the request is treated as a success to maintain idempotency.</li>
     * <li><b>Single-Server Restriction:</b> Scans the uncommitted portion of the log 
     * (from {@code commitIndex + 1} to the end) for any existing {@code CONF_} commands. 
     * If a pending change is found, this request is rejected to satisfy the 
     * "one-at-a-time" membership change rule.</li>
     * </ol>
     * </p>
     *
     * <p><b>Implementation Details:</b>
     * Once validated, the command is formatted as a system string and passed to 
     * {@link #propose(String, long, Object)} to be replicated across the cluster. 
     * The node will officially join the cluster only after this log entry is committed 
     * and applied to the state machines of a majority of nodes.</p>
     *
     * @param newPeerID The unique identifier of the server to be added to the cluster.
     * @return {@code true} if the configuration change was successfully proposed; 
     * {@code false} if this node is not the leader or a change is already pending.
     */
    public boolean addServer(String newPeerID) {
        lock.lock();
        try {
            if (currentRole != Role.LEADER) return false;
            if (peers.contains(newPeerID)) return true;

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

    /**
     * Initiates the process of removing a server from the Raft cluster configuration.
     * <p>Like {@link #addServer(String)}, this method follows the <b>Single-Server Membership Change</b> 
     * protocol to ensure cluster safety. By allowing only one configuration change (addition or removal) 
     * to be pending at any given time, the algorithm guarantees that the old and new majorities 
     * will always overlap, preventing the formation of two disjoint quorums.</p>
     *
     * <p><b>Execution Safety Checks:</b>
     * <ol>
     * <li><b>Leadership Verification:</b> The request is rejected if this node is not the 
     * current leader, as configuration changes must flow through the replicated log.</li>
     * <li><b>Idempotency:</b> If the target server is already absent from the peer list, 
     * the method returns {@code true} without proposing a new entry.</li>
     * <li><b>Pending Change Check:</b> The uncommitted log suffix (from {@code commitIndex + 1} 
     * to the end of the log) is scanned. If any entry starting with {@code CONF_} is found, 
     * the request is denied to prevent concurrent membership changes.</li>
     * </ol>
     * </p>
     *
     * <p><b>Self-Removal Handling:</b>
     * If a leader removes itself, it will continue to lead until the {@code CONF_REMOVE_SERVER} 
     * entry is committed. Once applied via {@link #applyCommand(String)}, the node will 
     * transition to a Follower and disable its election capabilities.</p>
     *
     * @param peerID The unique identifier of the server to be removed.
     * @return {@code true} if the removal was successfully proposed to the log; 
     * {@code false} if the node is not the leader or a change is already in progress.
     */
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

    /**
     * Returns a thread-safe copy of the current cluster membership list.
     * <p>This method provides a snapshot of the known peers in the Raft cluster. Since the 
     * cluster configuration can change dynamically through {@link #addServer(String)} 
     * and {@link #removeServer(String)}, the list is protected by the node's primary 
     * lock and returned as a new {@link ArrayList} to prevent {@link ConcurrentModificationException} 
     * in the caller.</p>
     *
     * <p><b>Context in Raft:</b>
     * <ul>
     * <li><b>Quorum Calculation:</b> The size of this list (plus the local node) 
     * determines the majority threshold ($$N/2 + 1$$) for elections and log commitment.</li>
     * <li><b>Replication:</b> The leader uses this list to maintain communication 
     * channels and replication state (nextIndex/matchIndex) for each peer.</li>
     * <li><b>Dynamic Membership:</b> This list is updated only when configuration 
     * change commands are committed and applied to the state machine.</li>
     * </ul>
     * </p>
     * 
     * @return A new {@link List} containing the unique identifiers of all current peer nodes.
     */
    public List<String> getPeers() {
        lock.lock();
        try { return new ArrayList<>(peers); } finally { lock.unlock(); }
    }

    /**
     * Retrieves the unique identifier of the node currently recognized as the cluster leader.
     * <p>In the Raft protocol, followers and candidates use this ID to redirect client 
     * requests and to synchronize their logs. This field is volatile and is updated 
     * whenever a node receives a valid {@code AppendEntries} or {@code InstallSnapshot} 
     * RPC from a leader with a term greater than or equal to its own.</p>
     *
     * <p><b>Protocol Utility:</b>
     * <ul>
     * <li><b>Client Redirection:</b> Allows the node to tell clients where to send 
     * write requests or linearizable reads if it is not the current leader.</li>
     * <li><b>Election Suppression:</b> While a valid {@code currentLeaderID} is known 
     * and heartbeats are being received, the node will not transition to the 
     * Candidate state.</li>
     * <li><b>Snapshot Transfers:</b> Identifies the source of incoming state 
     * machine snapshots.</li>
     * </ul>
     * </p>
     * 
     * @return The unique string ID of the current leader, or {@code null} if the 
     * leader is unknown (e.g., during an active election).
     */
    public String getCurrentLeaderID() {
        lock.lock();
        try { 
            return currentLeaderID; 
        } finally { 
            lock.unlock(); 
        }
    }

    public void printPerformanceStats() {
        System.out.println("=== Performance Report for Node " + nodeID + " ===");
        System.out.println("Proposals: " + proposalCounter.count());
        System.out.println("Avg Latency: " + commitTimer.mean(java.util.concurrent.TimeUnit.MILLISECONDS) + " ms");
        System.out.println("Max Latency: " + commitTimer.max(java.util.concurrent.TimeUnit.MILLISECONDS) + " ms");
    }

    public long getTotalLogSize() {
        lock.lock();
        try {
            return lastIncludedIndex + log.size() + 1;
        } finally {
            lock.unlock();
        }
    }
}