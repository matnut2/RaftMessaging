package com.raft.core;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public record PersistentState<T>(
    long term,
    String votedFor,
    List<LogEntry<T>> log
) implements Serializable {
    public static <T> PersistentState<T> empty() {
        return new PersistentState<>(0, null, new ArrayList<>());
    }
}