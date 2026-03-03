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

    /**
     * Persists the core Raft state to disk using Java Serialization.
     * <p>This method saves the current term, the candidate voted for, and the entire log 
     * into a single persistent file. It is called whenever these values change to ensure 
     * that the node can recover its state after a crash, maintaining the safety 
     * properties of the consensus algorithm.</p>
     *
     * @param currentTerm The latest term the server has seen.
     * @param votedFor    The candidate identifier that received a vote in the current term (or {@code null}).
     * @param log         The full list of log entries to be persisted.
     * @throws RuntimeException If an I/O error occurs during the serialization process.
     */
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

    /**
     * Loads the persisted Raft state from disk using Java Serialization.
     * <p>This method reads the {@link PersistentState} object from the storage file, which includes 
     * the current term, the last voted-for candidate, and the replicated log. If the file does 
     * not exist, it returns an empty state to initialize the node.</p>
     *
     * @return The {@link PersistentState} containing the recovered term, vote, and log entries.
     * @throws RuntimeException If a critical error occurs during file access or deserialization, 
     * preventing the node from recovering its previous state.
     */
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

    /**
     * Persists a complete state machine snapshot to disk using Java Serialization.
     * <p>This method saves the {@link Snapshot} object, which contains the compacted state of the 
     * state machine and client session metadata, into a dedicated snapshot file. 
     * It ensures that the node can recover its state efficiently after a crash without 
     * replaying the entire log from the beginning of time.</p>
     *
     * @param snapshot The {@link Snapshot} object containing the last included index, term, 
     * state machine data, and client session information.
     * @throws RuntimeException If an I/O error occurs during the serialization process, 
     * preventing the snapshot from being correctly stored.
     */
    @Override
    public synchronized void saveSnapshot(Snapshot snapshot) {
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(snapshotFile))) {
            oos.writeObject(snapshot);
            oos.flush();
        } catch (IOException e) {
            throw new RuntimeException("Failed to save snapshot", e);
        }
    }

    /**
     * Loads the persisted state machine snapshot from disk using Java Serialization.
     * <p>This method attempts to read the {@link Snapshot} object from the dedicated snapshot file. 
     * If the file exists, it recovers the compacted state, including the last included index, 
     * last included term, and the state machine data. If the file is missing or corrupted, 
     * it returns {@code null}, signaling that the node must recover its state through 
     * the log or a new snapshot from the leader.</p>
     *
     * @return The recovered {@link Snapshot} object, or {@code null} if no snapshot file exists 
     * or an error occurs during deserialization.
     */
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