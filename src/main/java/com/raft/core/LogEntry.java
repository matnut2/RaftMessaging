package com.raft.core;

import java.io.Serializable;

/**
 * Single entry of the replicated log.
 * 
 * <p>Each {@code LogEntry} represents a state machine command together with the term in which it was created by the leader. the term is used to detect inconstistencies between the logs and to guarantee that once an entry is committed, it will never be overwritten by a leader from an older term.</p>
 * 
 * <p>Log entries are replicated to followers through the AppendEntries RPC and are applied to the state machine only aftwe they become committed.</p>
 * 
 * <p>This class implements {@link Serializable} so that log entries can be transmitted over the network and persisted to stable storage.</p>
 * 
 * @param <T> Type of the command carried by this log entry
 */
public record LogEntry<T>(long term, T command) implements Serializable {}