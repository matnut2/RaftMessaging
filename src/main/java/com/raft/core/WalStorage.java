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
    // 1. Salvataggio dei metadati (sovrascrittura)
    try (FileWriter writer = new FileWriter(metaFile)) {
        MetaData meta = new MetaData(currentTerm, votedFor);
        gson.toJson(meta, writer);
    } catch (IOException e) {
        throw new RuntimeException("Failed to save metadata", e);
    }

    // 2. Scrittura ottimizzata del WAL (Write-Ahead Log)
    try {
        if (log.size() > persistedLogSize) {
            // Aggiunge solo le nuove entry usando un buffer da 32KB
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(walFile, true), 32768)) {
                for (int i = persistedLogSize; i < log.size(); i++) {
                    writer.write(gson.toJson(log.get(i), logEntryType));
                    writer.newLine();
                }
                writer.flush();
            }
        } else if (log.size() < persistedLogSize) {
            // Riscrive interamente il log in caso di troncamento (es. cambio leader)
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(walFile, false), 32768)) {
                for (LogEntry<T> entry : log) {
                    writer.write(gson.toJson(entry, logEntryType));
                    writer.newLine();
                }
                writer.flush();
            }
        }
        persistedLogSize = log.size();
    } catch (IOException e) {
        throw new RuntimeException("Failed to write to WAL", e);
    }
}
    /**
     * Loads the Raft node's persistent state from the file system during initialization.
     * <p>This method reconstructs the node's stable state by performing two sequential operations:
     * <ol>
     * <li><b>Metadata Recovery:</b> Reads the {@code metaFile} to restore the {@code currentTerm} 
     * and {@code votedFor} fields. If the file is missing, it defaults to term 0 and no vote.</li>
     * <li><b>Log Reconstruction:</b> Reads the Write-Ahead Log (WAL) file line-by-line. Each line 
     * is deserialized from JSON into a {@link LogEntry}. The internal {@code persistedLogSize} 
     * is synchronized with the number of entries loaded to ensure future appends work correctly.</li>
     * </ol>
     * This ensures that the node can resume its role in the cluster without violating safety 
     * properties after a restart.</p>
     *
     * @return A {@link PersistentState} object containing the fully reconstructed term, 
     * voting history, and command log.
     * @throws RuntimeException if an I/O error occurs or if the stored JSON data is malformed.
     */
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

    /**
     * Persists a state machine snapshot to a dedicated storage file.
     * <p>This method performs a full serialization of the {@link Snapshot} object, 
     * including the state data, client session information, and the indices 
     * representing the point in time the snapshot was taken. By overwriting the 
     * existing snapshot file, it ensures that the most recent stable state of 
     * the application is available for quick recovery or for synchronizing 
     * slow followers.</p>
     *
     * @param snapshot The {@link Snapshot} instance to be written to disk.
     * @throws RuntimeException If an I/O error occurs during the file writing process.
     */
    @Override
    public synchronized void saveSnapshot(Snapshot snapshot) {
        try (FileWriter writer = new FileWriter(snapshotFile)) {
            gson.toJson(snapshot, writer);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save snapshot", e);
        }
    }

    /**
     * Retrieves the most recent state machine snapshot from persistent storage.
     * <p>This method attempts to locate and deserialize the snapshot file. If the file exists, 
     * it reconstructs the {@link Snapshot} object, allowing the node to restore its state 
     * machine and session data without replaying the entire command log. If the file is 
     * missing, it returns {@code null}, indicating that no compaction has occurred or 
     * no previous state was recorded.</p>
     *
     * @return The restored {@link Snapshot} object, or {@code null} if the snapshot file 
     * does not exist or an error occurs during reading.
     */
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