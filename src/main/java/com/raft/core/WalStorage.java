package com.raft.core;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class WalStorage<T> implements Storage<T> {
    private final File metaFile;
    private final File walFile;
    private final File snapshotFile;
    private final Gson gson;
    private final Type logEntryType;

    private int persistedLogSize = 0;

    public WalStorage(String nodeId, Type commandType) {
        this.metaFile = new File("raft_node_" + nodeId + ".meta");
        this.walFile = new File("raft_node_" + nodeId + ".wal");
        this.snapshotFile = new File("raft_node_" + nodeId + ".snapshot");
        this.gson = new Gson();
        this.logEntryType = TypeToken.getParameterized(LogEntry.class, commandType).getType();
    }

    @Override
    public synchronized void save(long currentTerm, String votedFor, List<LogEntry<T>> log) {
        try (FileWriter writer = new FileWriter(metaFile)) {
            MetaData meta = new MetaData(currentTerm, votedFor);
            gson.toJson(meta, writer);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save metadata", e);
        }

        try {
            if (log.size() >= persistedLogSize) {
                try (BufferedWriter writer = new BufferedWriter(new FileWriter(walFile, true))) {
                    for (int i = persistedLogSize; i < log.size(); i++) {
                        writer.write(gson.toJson(log.get(i), logEntryType));
                        writer.newLine();
                    }
                }
            } else {
                try (BufferedWriter writer = new BufferedWriter(new FileWriter(walFile, false))) {
                    for (LogEntry<T> entry : log) {
                        writer.write(gson.toJson(entry, logEntryType));
                        writer.newLine();
                    }
                }
            }
            persistedLogSize = log.size();
        } catch (IOException e) {
            throw new RuntimeException("Failed to write to WAL", e);
        }
    }

    @Override
    public PersistentState<T> load() {
        long term = 0;
        String votedFor = null;
        List<LogEntry<T>> log = new ArrayList<>();

        if (metaFile.exists()) {
            try (FileReader reader = new FileReader(metaFile)) {
                MetaData meta = gson.fromJson(reader, MetaData.class);
                if (meta != null) {
                    term = meta.term;
                    votedFor = meta.votedFor;
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to load metadata", e);
            }
        }

        if (walFile.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(walFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.trim().isEmpty()) {
                        LogEntry<T> entry = gson.fromJson(line, logEntryType);
                        log.add(entry);
                    }
                }
                persistedLogSize = log.size();
            } catch (IOException e) {
                throw new RuntimeException("Failed to load WAL", e);
            }
        }

        return new PersistentState<>(term, votedFor, log);
    }

    @Override
    public synchronized void saveSnapshot(Snapshot snapshot) {
        try (FileWriter writer = new FileWriter(snapshotFile)) {
            gson.toJson(snapshot, writer);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save snapshot", e);
        }
    }

    @Override
    public Snapshot loadSnapshot() {
        if (!snapshotFile.exists()) return null;
        try (FileReader reader = new FileReader(snapshotFile)) {
            return gson.fromJson(reader, Snapshot.class);
        } catch (IOException e) {
            return null;
        }
    }

    private static class MetaData {
        long term;
        String votedFor;
        MetaData(long term, String votedFor) {
            this.term = term;
            this.votedFor = votedFor;
        }
    }
}