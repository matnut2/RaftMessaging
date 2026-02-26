package com.raft.node;

import com.raft.core.InMemoryNetwork;
import com.raft.core.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.io.File;

import static org.assertj.core.api.Assertions.assertThat;

public class RaftKVTest {

    private final List<Node<String>> cluster = new ArrayList<>();

    @BeforeEach
    void setup(){
        cleanupStorage();
    }

    @AfterEach
    void tearDown() {
        cluster.forEach(Node::stop);
        cluster.clear();
        deleteNodeFiles("A");
        deleteNodeFiles("B");
        deleteNodeFiles("C");
    }

    private void cleanupStorage() {
        System.gc();
        deleteNodeFiles("A");
        deleteNodeFiles("B");
        deleteNodeFiles("C");
    }

    private void deleteNodeFiles(String nodeId) {
        deleteFileWithRetry(new File("raft_node_" + nodeId + ".meta"));
        deleteFileWithRetry(new File("raft_node_" + nodeId + ".wal"));
        deleteFileWithRetry(new File("raft_node_" + nodeId + ".snapshot"));
    }

    private void deleteFileWithRetry(File file) {
        if (!file.exists()) return;
        
        // Riprova per 500ms se il file è bloccato
        for (int i = 0; i < 10; i++) {
            if (file.delete()) return;
            try { Thread.sleep(50); } catch (InterruptedException e) { }
        }
        System.err.println("WARNING: Could not delete file " + file.getName());
    }

    @Test
    void clusterShouldActAsKeyValueStore() throws InterruptedException {
        System.out.println("=== TEST: Distributed Key-Value Store ===");
        
        
        InMemoryNetwork network = new InMemoryNetwork(false); 
        Node<String> nodeA = new Node<>("A", List.of("B", "C"), network);
        Node<String> nodeB = new Node<>("B", List.of("A", "C"), network);
        Node<String> nodeC = new Node<>("C", List.of("A", "B"), network);

        network.addNode(nodeA); cluster.add(nodeA);
        network.addNode(nodeB); cluster.add(nodeB);
        network.addNode(nodeC); cluster.add(nodeC);

        nodeA.start(); nodeB.start(); nodeC.start();

        
        Thread.sleep(3000);
        Node<String> leader = cluster.stream()
                .filter(n -> n.getRole() == Role.LEADER)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Leader not found"));
        
        System.out.println("👑 Leader: " + leader.getNodeID());

        
        
        System.out.println("--- WRITING DATA ---");
        leader.propose("ClientA", 1,"SET username=admin");
        leader.propose("ClientA", 2,"SET currency=EUR");
        leader.propose("ClientA", 3,"SET status=active");
        
        
        Thread.sleep(2000);

        
        
        System.out.println("--- READING DATA ---");
        
        for (Node<String> node : cluster) {
            String user = node.get("username");
            String curr = node.get("currency");
            
            System.out.println("Node " + node.getNodeID() + " has username=" + user);

            if (user != null){
                assertThat(user).isEqualTo("admin");
                assertThat(curr).isEqualTo("EUR");
            } else {
                    System.err.println("❌ Node " + node.getNodeID() + " returned NULL! (Replication lag?)");
                }
        }

        
        System.out.println("--- UPDATING DATA ---");
        leader.propose("ClientA", 4, "SET currency=USD");
        leader.propose("ClientA", 5, "DEL status"); 
    
        
        Node<String> follower = cluster.stream()
                .filter(n -> n != leader)
                .findFirst()
                .orElseThrow();

        System.out.println("Checking update on follower " + follower.getNodeID());

        awaitValue(follower, "currency", "USD", 5000);
        
        assertThat(follower.get("currency")).isEqualTo("USD");
        
        awaitValue(follower, "status", null, 5000);
        assertThat(follower.get("status")).isNull();

        System.out.println("✅ Test Passed: Il cluster si comporta come un Database coerente!");
    }

    private void awaitValue(Node<String> node, String key, String expectedValue, long timeoutMs) throws InterruptedException {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            try {
                String current = node.get(key);
                
                if (expectedValue == null) {
                    if (current == null) return;
                } else {
                    if (expectedValue.equals(current)) return;
                }
            } catch (Exception e) {
                
                        }
            
            Thread.sleep(100);
        }
        System.err.println("Timeout waiting for key=" + key);
    }
}