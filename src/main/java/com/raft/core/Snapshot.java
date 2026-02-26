package com.raft.core;

import java.util.Map;

public record Snapshot(
    long lastIncludedIndex,
    long lastIncludedTerm,
    Map<String, String> data,
    Map<String, Long> clientSessions
) {}