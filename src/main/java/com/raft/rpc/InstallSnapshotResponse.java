package com.raft.rpc;

public record InstallSnapshotResponse(
    long term
) {}