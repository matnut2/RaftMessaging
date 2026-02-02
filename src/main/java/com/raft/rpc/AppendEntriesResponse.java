package com.raft.rpc;

public record AppendEntriesResponse(
    long term,
    boolean success
){}