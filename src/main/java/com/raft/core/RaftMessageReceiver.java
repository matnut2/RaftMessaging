package com.raft.core;

import com.raft.rpc.*;

public interface RaftMessageReceiver {
    PreVoteResponse handlePreVote(PreVoteRequest reques);
    RequestVoteResponse handleRequestVote(RequestVoteRequest request);
    AppendEntriesResponse handleAppendEntries(AppendEntriesRequest<?> request);
    InstallSnapshotResponse handleInstallSnapshot(InstallSnapshotRequest request);   
}
