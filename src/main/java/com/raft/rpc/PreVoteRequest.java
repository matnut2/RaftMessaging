package com.raft.rpc;

public record PreVoteRequest(
    long nextTerm,
    String candidateId,
    long lastLogIndex,
    long lastLogTerm
) {}