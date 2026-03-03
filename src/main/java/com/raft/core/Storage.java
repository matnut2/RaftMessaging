package com.raft.core;

import java.util.List;

public interface Storage<T> {
    /**
     * Persists the node's stable state to non-volatile storage.
     * <p>According to the Raft paper, this state must be updated on stable storage 
     * before responding to RPCs. This ensures that if a node crashes and restarts, 
     * it does not violate safety properties (e.g., by voting for a different 
     * candidate in the same term).</p>
     *
     * @param currentTerm The latest term the server has seen (initialized to 0 on 
     * first boot, increases monotonically).
     * @param votedFor    The candidate identifier that received a vote in the 
     * current term (or {@code null} if none).
     * @param log         The sequence of {@link LogEntry} objects containing 
     * commands and their associated terms.
     */
    void save(long currentTerm, String votedFor, List<LogEntry<T>> log);

    /**
     * Recovers the persisted stable state from storage.
     * <p>This method is typically called during node initialization to restore 
     * the node's progress and identity within the cluster after a shutdown 
     * or failure.</p>
     *
     * @return A {@link PersistentState} object containing the restored term, 
     * vote, and log entries.
     */
    PersistentState<T> load();

    /**
     * Persists a snapshot of the state machine.
     * <p>When the log grows too large, the node creates a snapshot to capture 
     * the current application state. Saving a snapshot allows the storage 
     * implementation to discard older log entries that have already been 
     * applied to the state machine, saving disk space.</p>
     *
     * @param snapshot The {@link Snapshot} object containing the state data and 
     * metadata (last included index and term).
     */
    void saveSnapshot(Snapshot snapshot);

    /**
     * Loads the most recent snapshot from storage.
     * <p>This is used during node startup or when a leader sends an 
     * {@code InstallSnapshot} RPC to a follower that is too far behind 
     * to be updated via standard log replication.</p>
     *
     * @return The latest {@link Snapshot} available, or {@code null} if no 
     * snapshot has been created yet.
     */
    Snapshot loadSnapshot();
}