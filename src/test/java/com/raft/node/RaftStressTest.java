package com.raft.node;

import com.raft.core.InMemoryNetwork;
import com.raft.core.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class RaftStressTest {

    private final List<Node<String>> cluster = new ArrayList<>();
    private InMemoryNetwork network;

    @BeforeEach
    void setup() {
        cleanStorageFiles();
    }

    @AfterEach
    void teardown() {
        for (Node<String> node : cluster) {
            node.stop();
        }
        cluster.clear();
        cleanStorageFiles();
    }

    private void cleanStorageFiles() {
        String[] ids = {"A", "B", "C"};
        String[] extensions = {".meta", ".wal", ".snapshot"};
        for (String id : ids) {
            for (String ext : extensions) {
                File file = new File("raft_node_" + id + ext);
                if (file.exists()) file.delete();
            }
        }
    }

    @Test
    void runHighLoadStressTest() throws InterruptedException {
        int numNodes = 10;
        int totalRequests = 1000; 
        int concurrentClients = 5; 

        network = new InMemoryNetwork(false);
        setupCluster(numNodes);

        System.out.println("Waiting for leader election...");
        Node<String> leader = null;
        for (int i = 0; i < 15; i++) {
            Thread.sleep(1000);
            leader = cluster.stream().filter(n -> n.getRole() == Role.LEADER).findFirst().orElse(null);
            if (leader != null) break;
        }

        if (leader == null) throw new IllegalStateException("Leader not elected in time!");
        System.out.println("Leader found: " + leader.getNodeID() + ". Starting stress...");

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        long startTime = System.currentTimeMillis();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < totalRequests; i++) {
                final int msgId = i;
                final Node<String> finalLeader = leader;
                executor.submit(() -> {
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

        Thread.sleep(10000);

        System.out.println("\n--- STRESS TEST RESULTS ---");
        System.out.println("Nodes: " + numNodes);
        System.out.println("Concurrent Clients: " + concurrentClients);
        System.out.println("Duration: " + duration + " s");
        System.out.println("Requests: " + totalRequests);
        System.out.println("Successful: " + successCount.get());
        System.out.println("Failed: " + failCount.get());
        System.out.println("Real-time Throughput: " + (successCount.get() / duration) + " req/s");

        System.out.println("\nChecking log consistency...");
        for (Node<String> n : cluster) {
            System.out.println("Node " + n.getNodeID() + " log size: " + n.getLogCopy().size());
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
}