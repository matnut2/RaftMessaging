package com.raft.rpc;

/**
 * Response to a {@link RequestVoteRequest} in the leader election protocol.
 * 
 * <p>This message is senty by a node after evaluating a candidate's request for a vote. It communicates wether he vote was granted and wheather the candidate is still operating in a valid term.</p>
 * 
 * <p>If {@code term} is greater than the candidate's current term, the candidate must step down and revert to follower state, since a more recent term has been observed.</p>
 * 
 * <p>If {@code voteGranted} is {@code true}, the receiver has cast its vote for the candidate in this term. A candidate becomes leader once it collects a majrity of such responses.</p> 
 */
public record RequestVoteResponse(
    long term,
    boolean voteGranted
){}