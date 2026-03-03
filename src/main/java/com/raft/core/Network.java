package com.raft.core;

import com.raft.rpc.AppendEntriesRequest;
import com.raft.rpc.AppendEntriesResponse;
import com.raft.rpc.InstallSnapshotRequest;
import com.raft.rpc.InstallSnapshotResponse;
import com.raft.rpc.RequestVoteRequest;
import com.raft.rpc.RequestVoteResponse;
import com.raft.rpc.PreVoteRequest;
import com.raft.rpc.PreVoteResponse;

import java.util.concurrent.CompletableFuture;

public interface Network {
    /**
     * Sends a RequestVote RPC to a specific target node.
     * @param targetNodeId The ID of the receiving node.
     * @param request The RPC payload.
     * @return A Future containing the response.
     */
    CompletableFuture<RequestVoteResponse> sendRequestVote(String targetNodeID, RequestVoteRequest request);
    
    /**
     * Sends an AppendEntries RPC (log replication or heartbeat).
     * @param targetNodeId The ID of the receiving node.
     * @param request The RPC payload.
     * @return A Future containing the response.
     */
    CompletableFuture<AppendEntriesResponse> sendAppendEntries(String targetNodeID, AppendEntriesRequest request);

    /**
     * Defines the asynchronous contract for sending an AppendEntries RPC to a cluster member.
     * <p>In the Raft consensus algorithm, this method is primarily used by the leader to 
     * replicate log entries and to serve as a heartbeat mechanism to maintain leadership. 
     * Implementations should handle the underlying transport (e.g., HTTP, gRPC, or local simulation) 
     * and return a {@link CompletableFuture} to avoid blocking the main execution thread.</p>
     *
     * @param targetNodeID The unique identifier of the destination node.
     * @param request      The {@link AppendEntriesRequest} containing the leader's term, 
     * the log entries to store (empty for heartbeats), and consistency indices.
     * @return A {@link CompletableFuture} that will yield an {@link AppendEntriesResponse} 
     * indicating whether the replication was successful or if a term mismatch occurred.
     */
    CompletableFuture<InstallSnapshotResponse> sendInstallSnapshot(String targetNodeID, InstallSnapshotRequest request);

    /**
     * Defines the asynchronous contract for performing a read-only query (GET) against a node's state machine.
     * <p>This method allows clients or other nodes to retrieve the current value associated with a specific 
     * key. While Raft usually requires read requests to be processed by the leader to ensure linearizability, 
     * this interface provides the transport mechanism to reach any specific node in the cluster.</p>
     *
     * @param targetNodeId The unique identifier of the node to be queried.
     * @param key          The specific key or resource identifier whose value is being requested.
     * @return A {@link CompletableFuture} that will yield the result string from the state machine, 
     * or an error if the node is unreachable or the key does not exist.
     */
    CompletableFuture<String> sendClientGet(String targetNodeId, String key);

    /**
     * Defines the asynchronous contract for sending a PreVote RPC to a cluster member.
     * <p>This method is used during the optional Pre-Vote phase of the Raft algorithm. Before 
     * incrementing its term and transitioning to the Candidate state, a node uses this RPC 
     * to check if it has a high enough log index and term to be granted a vote. This 
     * preventative measure helps avoid unnecessary term increases and cluster disruptions 
     * caused by nodes that have been partitioned from the majority.</p>
     *
     * @param targetNodeID The unique identifier of the node being queried for a pre-vote.
     * @param request      The {@link PreVoteRequest} containing the hypothetical next term 
     * and the sender's current log state.
     * @return A {@link CompletableFuture} that will yield a {@link PreVoteResponse} 
     * indicating if the target node would vote for the sender.
     */
    CompletableFuture<PreVoteResponse> sendPreVote(String targetNodeID, PreVoteRequest request);
} 
