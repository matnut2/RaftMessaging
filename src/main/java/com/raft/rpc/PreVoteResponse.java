package com.raft.rpc;

/**
 * RPC message sent in response to a {@link PreVoteRequest}.
 * <p>This response informs the candidate whether the receiver would grant its vote in a 
 * hypothetical next term. A vote is granted only if the candidate's log is at least 
 * as up-to-date as the receiver's log and the receiver has not heard from a valid 
 * leader within the election timeout period.</p>
 *
 * @param term        The current term of the receiver, used by the candidate to update itself.
 * @param voteGranted {@code true} if the receiver would grant its vote for the hypothetical election, {@code false} otherwise.
 */
public record PreVoteResponse(
    long term,
    boolean voteGranted
) {}