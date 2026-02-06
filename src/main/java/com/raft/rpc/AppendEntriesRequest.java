package com.raft.rpc;

import com.raft.core.LogEntry;

import java.util.List;
/**
 * RPC message used by the leader to replicate log entries and to maintain authority over the cluster (hearhbeat)
 * <p>This request is sent periodically by the leader to all followers.
 * It server two distinc purposes:
 * <ul>
 *  <li> Replicating new {@link LogEntry} objects into followers' logs.</li>
 *  <li> Acting as a hearthbeat to prevent followers from starting a new election. </li>
 * </ul>
 * 
 * <p>The receiver uses {@code preLogIndex} and {@code prevLogTerm} to check weather its log matches the leader's log at the point where new entries should be appended. If the logs do not match, the request is rejected and the leader will retry with an earlier index.</p>
 * 
 * @param <T> Type of command or payload stored inside each {@link LogEntry}.
 */
public record AppendEntriesRequest<T>(
    long term,
    String leaderId,
    long prevLogIndex,
    long prevLogTerm,
    List<LogEntry<T>> entries,
    long leaderCommit
){}