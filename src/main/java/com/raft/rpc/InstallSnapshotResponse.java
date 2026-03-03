package com.raft.rpc;

/**
 * RPC message sent in response to an {@link InstallSnapshotRequest}.
 * <p>This response allows the leader to update its knowledge of the follower's state
 * and ensures that the term of the leader is still current.</p>
 * @param term The current term of the receiver, used by the leader to update itself or step down.
 */
public record InstallSnapshotResponse(
    long term
) {}