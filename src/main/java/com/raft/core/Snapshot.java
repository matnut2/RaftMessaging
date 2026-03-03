package com.raft.core;

import java.util.List;
import java.util.Map;

public record Snapshot(
    long lastIncludedIndex,
    long lastIncludedTerm,
    Map<String, List<String>> data,
    Map<String, Long> clientSessions
) {}