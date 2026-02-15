package com.raft.core;

import java.util.List;

public interface Storage<T> {
    void save(long currentTerm, String votedFor, List<LogEntry<T>> log);
    PersistentState<T> load();

    void saveSnapshot(Snapshot snapshot);
    Snapshot loadSnapshot();
}