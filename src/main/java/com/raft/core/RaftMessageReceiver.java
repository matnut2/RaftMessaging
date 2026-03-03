package com.raft.core;

import com.raft.rpc.*;

public interface RaftMessageReceiver {
    /**
     * Processes an incoming PreVote RPC to determine if a vote would be granted in a hypothetical election.
     * <p>This method implements the server-side logic of the Pre-Vote phase. The node evaluates 
     * the request without incrementing its own term or updating its persistent state. A vote 
     * is typically granted if the sender's log is at least as up-to-date as the receiver's 
     * and the receiver has not heard from a valid leader within the election timeout period.</p>
     *
     * @param request The {@link PreVoteRequest} containing the candidate's hypothetical term 
     * and log metadata.
     * @return A {@link PreVoteResponse} indicating whether the vote would be granted and 
     * the receiver's current term.
     */
    PreVoteResponse handlePreVote(PreVoteRequest reques);

    /**
     * Processes an incoming RequestVote RPC from a candidate node.
     * <p>This method implements the core voting logic of the Raft consensus algorithm. 
     * A node decides whether to grant its vote based on the candidate's term and 
     * log completeness. The vote is granted only if the candidate's term is greater 
     * than or equal to the receiver's current term, and if the candidate's log is 
     * at least as up-to-date as the receiver's log to ensure safety.</p>
     *
     * @param request The {@link RequestVoteRequest} containing the candidate's term, 
     * identity, and information about its last log entry.
     * @return A {@link RequestVoteResponse} indicating whether the vote was granted 
     * and providing the receiver's current term for the candidate's synchronization.
     */
    RequestVoteResponse handleRequestVote(RequestVoteRequest request);

    /**
     * Processes an incoming AppendEntries RPC from the cluster leader.
     * <p>This method handles log replication, heartbeats, and consistency checks. It verifies 
     * the leader's term, checks for a matching log entry at the {@code prevLogIndex}, and 
     * appends new entries while resolving any conflicts. It also updates the local commit 
     * index based on the leader's commit progress, ensuring that the state machine stays 
     * synchronized with the cluster majority.</p>
     *
     * @param request The {@link AppendEntriesRequest} containing the leader's term, log 
     * metadata, the list of entries to replicate, and the leader's current commit index.
     * @return An {@link AppendEntriesResponse} containing the receiver's current term and 
     * a success flag indicating if the log matching and appending were successful.
     */
    AppendEntriesResponse handleAppendEntries(AppendEntriesRequest<?> request);

    /**
     * Processes an incoming InstallSnapshot RPC from the cluster leader.
     * <p>This method is invoked when the leader has already discarded the log entries 
     * necessary to bring a follower up to date and must instead send a complete snapshot 
     * of the state machine. The follower processes the snapshot chunks, updates its 
     * persistent state, and resets its state machine to match the snapshot, ensuring it 
     * can resume participating in the consensus process.</p>
     *
     * @param request The {@link InstallSnapshotRequest} containing the leader's term, 
     * the last included index and term of the snapshot, and the raw data chunk.
     * @return An {@link InstallSnapshotResponse} providing the receiver's current term 
     * to the leader for synchronization.
     */
    InstallSnapshotResponse handleInstallSnapshot(InstallSnapshotRequest request);   
}
