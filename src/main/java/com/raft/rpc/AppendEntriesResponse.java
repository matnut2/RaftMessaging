package com.raft.rpc;
/**
 * Response to an {@link AppendEntriesRequest} RPC.
 * 
 * <p>This message is sent by a follower to the leader after processing an {@link AppendEntriesRequest}. It tells the leader whether the follower accepted the log entries (or hearthbeat) and whether the leader is still considered a valid leader.</p>
 * 
 * <p>If {@code term} is greater than the leader's current term, the leader must step down, becasue a more recent term has been observed in the cluster.<p>
 * 
 * <p>If {@code success} is {@code false}, it means that the follower's log does not contain an entry matching the {@code prevLogIndex} and {@code prevLogTerm} provided by the leader. In that case, the leader will retry an {@link AppendEntriesRequest} with earlier log indices. 
 */
public record AppendEntriesResponse(
    long term,
    boolean success
){}