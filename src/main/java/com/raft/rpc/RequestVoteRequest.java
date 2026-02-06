package com.raft.rpc;

/**
 * RPC message used by a follower node to request voted suring a leader election.
 * 
 * <p>When a server transitions to a candidate state, it sends this request to all other nodes in the cluster to gather votes for the current term.
 * A node grants its vote if the candidate's term is at least as up-to-date as its own and the candidate's log is at least as complete as the receiverès log.</p>
 * 
 * <p>The {@code lastLogIndex} and {@code lastlogTerm} fields are used to determine log allignment, ensuring that a node with an outdated log cannot become leader and overwrite commited entries.</p>
 */
public record RequestVoteRequest(
    long term,
    String candidateId,
    long lastLogIndex,
    long lastLogTerm
){}