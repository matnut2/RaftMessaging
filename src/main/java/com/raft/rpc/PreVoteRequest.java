package com.raft.rpc;

/**
 * RPC message used by a node during the Pre-Vote phase to prevent unnecessary term increases.
 * <p>Before transitioning to the Candidate state and incrementing its term, a node sends this 
 * request to its peers to verify if it is likely to win an election. This mechanism protects 
 * the cluster from disruptive servers that have been partitioned and increased their terms.</p>
 * @param nextTerm     The term the node will transition to if the Pre-Vote is successful.
 * @param candidateId  The identifier of the node requesting the Pre-Vote.
 * @param lastLogIndex The index of the candidate's last log entry.
 * @param lastLogTerm  The term of the candidate's last log entry.
 */
public record PreVoteRequest(
    long nextTerm,
    String candidateId,
    long lastLogIndex,
    long lastLogTerm
) {}