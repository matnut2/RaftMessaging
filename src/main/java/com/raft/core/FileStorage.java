package com.raft.core;

import java.io.*;
import java.util.List;

public class FileStorage<T> implements Storage<T> {
    private final File file;
    private final File snapshotFile;

    public FileStorage(String nodeId) {
        this.file = new File("raft_node_" + nodeId + ".dat");
        this.snapshotFile = new File("raft_node_" + nodeId + ".snapshot");
    }

    @Override
    public synchronized void save(long currentTerm, String votedFor, List<LogEntry<T>> log) {
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(file))) {
            PersistentState<T> state = new PersistentState<>(currentTerm, votedFor, log);
            oos.writeObject(state);
            oos.flush();
        } catch (IOException e) {
            throw new RuntimeException("CRITICAL: Failed to persist state", e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public PersistentState<T> load() {
        if (!file.exists()) {
            return PersistentState.empty();
        }
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(file))) {
            return (PersistentState<T>) ois.readObject();
        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException("CRITICAL: Failed to load state", e);
        }
    }

    @Override
    public synchronized void saveSnapshot(Snapshot snapshot) {
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(snapshotFile))) {
            oos.writeObject(snapshot);
            oos.flush();
        } catch (IOException e) {
            throw new RuntimeException("Failed to save snapshot", e);
        }
    }

    @Override
    public Snapshot loadSnapshot() {
        if (!snapshotFile.exists()) return null;
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(snapshotFile))) {
            return (Snapshot) ois.readObject();
        } catch (IOException | ClassNotFoundException e) {
            return null;
        }
    }
}