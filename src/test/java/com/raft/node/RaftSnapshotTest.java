package com.raft.node;

import com.raft.core.InMemoryNetwork;
import com.raft.core.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class RaftSnapshotTest {

    private final List<Node<String>> cluster = new ArrayList<>();

    @BeforeEach
    void setup() {
        deleteNodeFiles("A");
        deleteNodeFiles("B");
        deleteNodeFiles("C");
    }

    @AfterEach
    void tearDown() {
        cluster.forEach(Node::stop);
        cluster.clear();
        deleteNodeFiles("A");
        deleteNodeFiles("B");
        deleteNodeFiles("C");
    }

    private void deleteNodeFiles(String nodeId) {
        new File("raft_node_" + nodeId + ".meta").delete();
        new File("raft_node_" + nodeId + ".wal").delete();
        new File("raft_node_" + nodeId + ".snapshot").delete();
    }

    @Test
    void laggingFollowerShouldReceiveSnapshot() throws InterruptedException {
        System.out.println("=== TEST: Snapshot Replication ===");

        InMemoryNetwork network = new InMemoryNetwork(true);
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

        System.out.println("Leader: " + leader.getNodeID());

        Node<String> victim = cluster.stream()
                .filter(n -> !n.getNodeID().equals(leader.getNodeID()))
                .findFirst().orElseThrow();
        
        System.out.println("Stopping Victim Node: " + victim.getNodeID());
        victim.stop(); 

        System.out.println("--- Writing Data (Victim is offline) ---");
        for (int i = 0; i < 10; i++) {
            leader.propose("ClientA", 1, "SET key" + i + "=value" + i);
            Thread.sleep(100); 
        }

        Thread.sleep(1000); 
        System.out.println("--- Forcing Snapshot on Leader ---");
        leader.takeSnapshot();

        assertThat(leader.getLogCopy().size()).isLessThan(10);
        System.out.println("Leader Log size after snapshot: " + leader.getLogCopy().size());

        System.out.println("--- Restarting Victim Node " + victim.getNodeID() + " ---");
        victim.start();

        Thread.sleep(3000);

        System.out.println("Checking consistency...");
        
        String value9 = victim.get("key9");
        assertThat(value9)
            .as("Victim should have received key9 via Snapshot")
            .isEqualTo("value9");

        assertThat(victim.getLogCopy().size())
            .as("Victim log should be truncated after installing snapshot")
            .isLessThan(10);

        System.out.println("✅ Test Passed: Snapshot installed successfully!");
    }
}