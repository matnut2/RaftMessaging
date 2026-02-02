package com.raft.rpc;

public record RequestVoteResponse(
    long term,
    boolean voteGranted
){}