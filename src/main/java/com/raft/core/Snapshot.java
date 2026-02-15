package com.raft.core;

import java.io.Serializable;
import java.util.Map;

public record Snapshot(
    long lastIncludedIndex,
    long lastIncludedTerm,
    Map<String, String> data // Lo stato della tua applicazione (StateMachine)
) implements Serializable {}