package com.raft.rpc;

public record InstallSnapshotRequest(
    long term,
    String leaderId,
    long lastIncludedIndex,
    long lastIncludedTerm,
    int offset,
    byte[] data,
    boolean done
) {}