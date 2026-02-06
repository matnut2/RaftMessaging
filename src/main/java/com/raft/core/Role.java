package com.raft.core;

/**
 * Role of a server in the consensus protocol.
 *
 * <p>At any time, each node in a Raft cluster is in exactly one of these roles. The role determines how the node behaves, which RPCs it sends, and how it reacts to messages from other nodes.</p>
 *
 * <ul>
 *   <li>{@link #FOLLOWER}: passive state. The node responds to RPCs from
 *       leaders and candidates but does not initiate actions on its own.</li>
 *   <li>{@link #CANDIDATE}: election state. The node has started a new term
 *       and is attempting to become leader by requesting votes from others.</li>
 *   <li>{@link #LEADER}: active state. The node is responsible for replicating
 *       log entries, sending heartbeats, and driving the state machine forward.</li>
 * </ul>
 *
 * <p>Transitions between these roles are governed by timeouts, RPC results, and term comparisons as defined by the Raft protocol.</p>
 */
public enum Role {
    FOLLOWER,
    CANDIDATE,
    LEADER
}