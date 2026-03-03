package com.raft.node;

import com.raft.core.InMemoryNetwork;
import com.raft.core.Role;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class RaftStressTest {

    private final List<Node<String>> cluster = new ArrayList<>();
    private InMemoryNetwork network;

    @Test
    void runHighLoadStressTest() throws InterruptedException {
        try{
        int numNodes = 3;
        int totalRequests = 1000; // Numero di messaggi da inviare
        int concurrentClients = 5; // Numero di thread client simultanei

        network = new InMemoryNetwork(false); // false = latenza minima per stressare la CPU
        setupCluster(numNodes);

        // 1. Attesa elezione Leader
        System.out.println("Waiting for leader election...");
        Node<String> leader = null;
        for (int i = 0; i < 10; i++) {
            Thread.sleep(1000);
            leader = cluster.stream().filter(n -> n.getRole() == Role.LEADER).findFirst().orElse(null);
            if (leader != null) break;
        }

        if (leader == null) throw new IllegalStateException("Leader not elected in time!");
        System.out.println("Leader found: " + leader.getNodeID() + ". Starting stress...");

        // 2. Stress Test con Virtual Threads
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        long startTime = System.currentTimeMillis();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < totalRequests; i++) {
                final int msgId = i;
                final Node<String> finalLeader = leader;
                executor.submit(() -> {
                    // Simula invio messaggio in una stanza
                    boolean ok = finalLeader.propose("StressClient-" + (msgId % concurrentClients), 
                                                    msgId, 
                                                    "SEND stress_room Message-" + msgId);
                    if (ok) successCount.incrementAndGet();
                    else failCount.incrementAndGet();
                });
            }
        }

        long endTime = System.currentTimeMillis();
        double duration = (endTime - startTime) / 1000.0;

        // 3. Risultati
        System.out.println("\n--- STRESS TEST RESULTS ---");
        System.out.println("Duration: " + duration + " s");
        System.out.println("Requests: " + totalRequests);
        System.out.println("Successful: " + successCount.get());
        System.out.println("Failed: " + failCount.get());
        System.out.println("Real-time Throughput: " + (successCount.get() / duration) + " req/s");
        
        leader.printPerformanceStats();

        // 4. Verifica coerenza (opzionale)
        System.out.println("\nChecking log consistency...");
        Thread.sleep(2000); // Tempo per la replicazione finale
        int leaderLogSize = leader.getLogCopy().size();
        for (Node<String> n : cluster) {
            System.out.println("Node " + n.getNodeID() + " log size: " + n.getLogCopy().size());
        }
    }
    finally{
        shutdownAndCleanup();
    }
    }

    private void setupCluster(int n) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) ids.add(String.valueOf((char)('A' + i)));

        for (String id : ids) {
            List<String> peers = new ArrayList<>(ids);
            peers.remove(id);
            Node<String> node = new Node<>(id, peers, network);
            network.addNode(node);
            cluster.add(node);
            node.start();
        }
    }

    private void shutdownAndCleanup() {
        // 1. Ferma tutti i nodi per rilasciare i lock sui file
        cluster.forEach(Node::stop);

        // 2. Elimina i file per ogni nodo
        for (Node<String> node : cluster) {
            deleteNodeFiles(node.getNodeID());
        }
    }

    private void deleteNodeFiles(String nodeId) {
        // I nomi dei file seguono la convenzione usata in WalStorage
        String[] extensions = {".meta", ".wal", ".snapshot"};
        for (String ext : extensions) {
            File file = new File("raft_node_" + nodeId + ext);
            if (file.exists()) {
                if (file.delete()) {
                } else {
                    System.err.println("Failed to delete: " + file.getName() + " (File might be in use)");
                }
            }
        }
    }
}
