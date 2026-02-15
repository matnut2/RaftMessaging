package com.raft.core;

import com.raft.rpc.AppendEntriesRequest;
import com.raft.rpc.AppendEntriesResponse;
import com.raft.rpc.InstallSnapshotRequest;
import com.raft.rpc.InstallSnapshotResponse;
import com.raft.rpc.RequestVoteRequest;
import com.raft.rpc.RequestVoteResponse;

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

    CompletableFuture<InstallSnapshotResponse> sendInstallSnapshot(String targetNodeID, InstallSnapshotRequest request);

    } 
