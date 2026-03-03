package com.raft.rpc;

/**
 * RPC message used by the leader to send chunks of a snapshot to a lagging follower.
 * <p>This request is triggered when the leader has already discarded the log entries 
 * required to bring a follower up to date. Instead of sending individual log entries, 
 * the leader transmits its entire state machine snapshot in chunks.</p>
 * @param term              The leader's current term.
 * @param leaderId          The leader's identifier, allowing followers to redirect clients.
 * @param lastIncludedIndex The snapshot replaces all entries up through and including this index.
 * @param lastIncludedTerm  The term of the {@code lastIncludedIndex}.
 * @param offset            The byte offset where the current chunk is positioned in the snapshot file.
 * @param data              The raw bytes of the snapshot chunk starting at {@code offset}.
 * @param done              {@code true} if this is the final chunk of the snapshot, {@code false} otherwise.
 */
public record InstallSnapshotRequest(
    long term,
    String leaderId,
    long lastIncludedIndex,
    long lastIncludedTerm,
    int offset,
    byte[] data,
    boolean done
) {}