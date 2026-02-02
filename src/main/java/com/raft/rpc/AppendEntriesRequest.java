package com.raft.rpc;

import com.raft.core.LogEntry;
import java.util.List;

public record AppendEntriesRequest<T>(
    long term,
    String leaderId,
    long prevLogIndex,
    long prevLogTerm,
    List<LogEntry<T>> entries,
    long leaderCommit
){}