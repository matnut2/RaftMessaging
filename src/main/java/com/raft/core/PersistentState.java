package com.raft.core;

import java.util.ArrayList;
import java.util.List;

public record PersistentState<T>(
    long term,
    String votedFor,
    List<LogEntry<T>> log
) {
    /**
     * Creates an empty persistent state for a new Raft node.
     * <p>This factory method initializes the stable storage components required by the 
     * Raft algorithm before any consensus activity occurs. It sets the current term to 
     * {@code 0}, the voted-for candidate to {@code null}, and provides a new, 
     * empty list for the log entries.</p>
     *
     * @param <T> The type of the commands stored within the log entries.
     * @return A new {@link PersistentState} instance with default initial values.
     */
    public static <T> PersistentState<T> empty() {
        return new PersistentState<>(0, null, new ArrayList<>());
    }
}