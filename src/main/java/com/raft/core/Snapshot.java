package com.raft.core;

import java.util.List;
import java.util.Map;

/**
 * Represents a point-in-time state of the Raft state machine and associated metadata.
 * <p>A snapshot is created to allow the compaction of the persistent log. It captures the 
 * entire state of the application at a specific index, along with the session information 
 * required to maintain linearizability for client requests. Once a snapshot is persisted, 
 * all log entries preceding {@code lastIncludedIndex} can be safely discarded.</p>
 *
 * @param lastIncludedIndex The index of the last entry in the log that the snapshot replaces.
 * @param lastIncludedTerm  The term of the last entry in the log that the snapshot replaces.
 * @param data              The actual state machine data, represented as a mapping of keys 
 * to their respective lists of values (e.g., message history in a room).
 * @param clientSessions    A mapping of client identifiers to their last processed sequence 
 * numbers or timestamps, used to detect and ignore duplicate requests.
 */
public record Snapshot(
    long lastIncludedIndex,
    long lastIncludedTerm,
    Map<String, List<String>> data,
    Map<String, Long> clientSessions
) {}