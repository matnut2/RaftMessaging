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
        try {
            int numNodes = 15; 
            int totalRequests = 150000; 
            int concurrentClients = 3; 

            network = new InMemoryNetwork(false); 
            setupCluster(numNodes);

            System.out.println("Waiting for leader election...");
            Node<String> leader = null;
            for (int i = 0; i < 10; i++) {
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

            System.out.println("\n--- STRESS TEST RESULTS ---");
            System.out.println("Duration: " + duration + " s");
            System.out.println("Requests: " + totalRequests);
            System.out.println("Successful: " + successCount.get());
            System.out.println("Failed: " + failCount.get());
            System.out.println("Real-time Throughput: " + (successCount.get() / duration) + " req/s");
            
            leader.printPerformanceStats();

            waitForClusterSync(totalRequests);

            System.out.println("\nChecking log consistency...");
            for (Node<String> n : cluster) {
                System.out.println("Node " + n.getNodeID() + " log size: " + n.getTotalLogSize());
            }
        } finally {
            shutdownAndCleanup();
        }
    }

    private void setupCluster(int n) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) ids.add(String.valueOf(i));

        for (String id : ids) {
            List<String> peers = new ArrayList<>(ids);
            peers.remove(id);
            Node<String> node = new Node<>(id, peers, network);
            network.addNode(node);
            cluster.add(node);
            node.start();
        }
    }

    private void waitForClusterSync(int expectedLogSize) throws InterruptedException {
        System.out.println("\nWaiting for all nodes to synchronize logs to size " + expectedLogSize + "...");
        long start = System.currentTimeMillis();
        boolean allSynced = false;

        while (System.currentTimeMillis() - start < 30000) { 
            allSynced = true;
            for (Node<String> n : cluster) {
                if (n.getTotalLogSize() < expectedLogSize) {
                    allSynced = false;
                    break;
                }
            }
            if (allSynced) break;
            Thread.sleep(250); 
        }

        if (!allSynced) {
            System.err.println("Warning: Cluster did not fully synchronize within the timeout.");
        } else {
            System.out.println("Cluster fully synchronized.");
        }
    }

    private void shutdownAndCleanup() {
        System.out.println("\nInitiating cluster shutdown...");
        
        cluster.forEach(Node::stop);

        try {
            Thread.sleep(1500); 
            System.gc();    
            Thread.sleep(500);  
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        System.out.println("Cleaning up storage files...");
        for (Node<String> node : cluster) {
            deleteNodeFiles(node.getNodeID());
        }
    }

    private void deleteNodeFiles(String nodeId) {
        String[] extensions = {".meta", ".wal", ".snapshot"};
        for (String ext : extensions) {
            File file = new File("raft_node_" + nodeId + ext);
            if (file.exists()) {
                if (!file.delete()) {
                    System.err.println("Failed to delete: " + file.getName() + " (File might be in use)");
                }
            }
        }
    }
}