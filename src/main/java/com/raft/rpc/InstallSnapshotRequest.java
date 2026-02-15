package com.raft.rpc;

import java.util.Map;

public record InstallSnapshotRequest(
    long term,
    String leaderId,
    long lastIncludedIndex,
    long lastIncludedTerm,
    Map<String, String> data
) {}