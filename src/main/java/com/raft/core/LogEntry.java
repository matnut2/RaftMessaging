package com.raft.core;

import java.io.Serializable;

public record LogEntry<T>(long term, T command) implements Serializable {}