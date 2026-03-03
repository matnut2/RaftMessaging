package com.raft.core;

import com.raft.node.Node;
import com.raft.rpc.AppendEntriesRequest;
import com.raft.rpc.AppendEntriesResponse;
import com.raft.rpc.InstallSnapshotRequest;
import com.raft.rpc.InstallSnapshotResponse;
import com.raft.rpc.PreVoteRequest;
import com.raft.rpc.PreVoteResponse;
import com.raft.rpc.RequestVoteRequest;
import com.raft.rpc.RequestVoteResponse;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class InMemoryNetwork implements Network {

    private final Map<String, Node<?>> nodes = new ConcurrentHashMap<>();
    private final boolean simulateLatency;
    private final Random random = new Random();

    public InMemoryNetwork(boolean simulateLatency) {
        this.simulateLatency = simulateLatency;
    }

    /**
     * Registers a new Raft node within the local simulation or registry.
     * <p>This method adds a {@link Node} instance to an internal mapping, keyed by its 
     * unique node identifier. It is typically used during cluster initialization or 
     * configuration updates to ensure the network layer or management utility can 
     * resolve and interact with all participating members.</p>
     *
     * @param node The Raft {@link Node} instance to be added to the registry.
     */
    public void addNode(Node<?> node) {
        nodes.put(node.getNodeID(), node);
    }

    /**
     * Simulates the transmission of a RequestVote RPC to a target node within a local environment.
     * <p>Unlike a real network implementation, this method retrieves the target node from a local 
     * registry and invokes its handler directly. It includes optional latency simulation to 
     * mimic real-world network conditions and returns the result asynchronously to maintain 
     * consistency with the {@link Network} interface.</p>
     *
     * @param targetNodeId The unique identifier of the peer node to receive the vote request.
     * @param request      The {@link RequestVoteRequest} containing the candidate's term and log state.
     * @return A {@link CompletableFuture} that resolves to the {@link RequestVoteResponse} from the 
     * target node, or fails if the node is unreachable or an error occurs during processing.
     */
    @Override
    public CompletableFuture<RequestVoteResponse> sendRequestVote(String targetNodeId, RequestVoteRequest request) {
        try {
            if (simulateLatency) simulateNetworkDelay();
        
            var target = nodes.get(targetNodeId);
            
            if (target == null) {
                return CompletableFuture.failedFuture(new RuntimeException("Node unreachable"));
            }
        
            @SuppressWarnings("rawtypes")
            com.raft.node.Node rawTarget = (com.raft.node.Node) target;
            
            RequestVoteResponse response = rawTarget.handleRequestVote(request);

            return CompletableFuture.completedFuture(response);

        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public CompletableFuture<AppendEntriesResponse> sendAppendEntries(String targetNodeId, AppendEntriesRequest request) {
        try {
            if (simulateLatency) simulateNetworkDelay();

            Node<?> target = nodes.get(targetNodeId);
            if (target == null) {
                return CompletableFuture.failedFuture(new RuntimeException("Node unreachable"));
            }

            @SuppressWarnings("unchecked")
            Node<Object> typedTarget = (Node<Object>) target;
            
            AppendEntriesResponse response = typedTarget.handleAppendEntries(request);
            return CompletableFuture.completedFuture(response);

        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * Simulates the transmission of an AppendEntries RPC to a target node within a local environment.
     * <p>This method replicates the behavior of a leader sending log entries or heartbeats to a follower. 
     * It retrieves the target node from the local registry, optionally introduces a simulated 
     * network delay, and directly invokes the follower's message handler. The operation is 
     * wrapped in a {@link CompletableFuture} to remain compatible with asynchronous network 
     * expectations.</p>
     *
     * @param targetNodeId The unique identifier of the peer node that should receive the entries.
     * @param request      The {@link AppendEntriesRequest} containing the leader's term, log 
     * information, and commit index.
     * @return A {@link CompletableFuture} that resolves to the {@link AppendEntriesResponse} 
     * from the target node, or fails if the node is missing from the registry.
     */
    @Override
    public CompletableFuture<InstallSnapshotResponse> sendInstallSnapshot(String targetNodeID, InstallSnapshotRequest request){
        try{
            if (simulateLatency) simulateNetworkDelay();

            Node<?> target = nodes.get(targetNodeID);
            if (target == null){
                return CompletableFuture.failedFuture(new RuntimeException("Node Unreachable"));
            }

            @SuppressWarnings("unchecked")
            Node<Object> typedTarget = (Node<Object>) target;

            InstallSnapshotResponse response = typedTarget.handleInstallSnapshot(request);
            return CompletableFuture.completedFuture(response);
        }
        catch (Exception e){
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * Simulates a client-side read (GET) operation targeting a specific node in a local cluster.
     * <p>This method bypasses actual network protocols by performing a direct lookup in the local 
     * node registry. It optionally simulates network latency before querying the target node's 
     * state machine for the value associated with the provided key. This is useful for testing 
     * client interactions and linearizability within a controlled simulation.</p>
     *
     * @param targetNodeId The unique identifier of the node to be queried (ideally the current leader).
     * @param key          The key or identifier for the specific data being requested from the state machine.
     * @return A {@link CompletableFuture} containing the result string from the state machine, 
     * or a failure if the node cannot be found or the operation fails.
     */
    @Override
    public CompletableFuture<String> sendClientGet(String targetNodeId, String key){
        try{
            if (simulateLatency) simulateNetworkDelay();

            Node<?> target = (Node<?>) nodes.get(targetNodeId);

            if (target == null)
                return CompletableFuture.failedFuture(new RuntimeException("Node Unreachable"));

            @SuppressWarnings("rawtypes")
            Node rawTarget = (Node) target;

            String result = rawTarget.get(key);
                return CompletableFuture.completedFuture(result);

            } 
            catch (Exception e) {
                return CompletableFuture.failedFuture(e);
        }
    }
    
    /**
     * Simulates the transmission of a PreVote RPC to a target node within a local environment.
     * <p>This method replicates the Pre-Vote phase by performing a direct method call on a 
     * registered node instance. It allows a node to test the waters for a potential election 
     * without actual network overhead, while still respecting optional latency simulations. 
     * This is critical for testing the prevention of term inflation in partitioned scenarios 
     * within a local simulation context.</p>
     *
     * @param targetNodeId The unique identifier of the peer node being asked for a pre-vote.
     * @param request      The {@link PreVoteRequest} containing the hypothetical term and log metadata.
     * @return A {@link CompletableFuture} that resolves to the {@link PreVoteResponse} from the 
     * target node, or fails if the node is not found in the local registry.
     */
    @Override
    public CompletableFuture<PreVoteResponse> sendPreVote(String targetNodeId, PreVoteRequest request) {
        try {
            if (simulateLatency) simulateNetworkDelay();
        
            Node<?> target = nodes.get(targetNodeId);
            if (target == null) {
                return CompletableFuture.failedFuture(new RuntimeException("Node unreachable"));
            }
        
            @SuppressWarnings("unchecked")
            Node<Object> typedTarget = (Node<Object>) target;
            
            PreVoteResponse response = typedTarget.handlePreVote(request);
            return CompletableFuture.completedFuture(response);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * Introduces a random artificial delay to simulate network latency.
     * <p>This method pauses the current thread for a duration between 5 and 20 milliseconds. 
     * It is used within the local simulation to mimic the non-instantaneous nature of 
     * real-world network communication, helping to uncover potential race conditions 
     * and timing issues in the Raft implementation.</p>
     *
     * @throws RuntimeException if the thread is interrupted during the sleep period, 
     * though the interrupt status is restored.
     */
    private void simulateNetworkDelay() {
        try {
            
            
            int delay = 5 + random.nextInt(15);
            TimeUnit.MILLISECONDS.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

}