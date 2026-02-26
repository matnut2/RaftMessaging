package com.raft.rpc;

public record PreVoteResponse(
    long term,
    boolean voteGranted
) {}