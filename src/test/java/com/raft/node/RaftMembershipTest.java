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

public class RaftMembershipTest {

    private final List<Node<String>> cluster = new ArrayList<>();
    private InMemoryNetwork network;

    @BeforeEach
    void setup() {
        cleanupStorage();
        network = new InMemoryNetwork(false); 
    }

    @AfterEach
    void tearDown() {
        cluster.forEach(Node::stop);
        cluster.clear();
        cleanupStorage();
    }

    private void cleanupStorage() {
        System.gc();
        deleteNodeFiles("A");
        deleteNodeFiles("B");
        deleteNodeFiles("C");
        deleteNodeFiles("D");
    }

    private void deleteNodeFiles(String nodeId) {
        new File("raft_node_" + nodeId + ".dat").delete();
        new File("raft_node_" + nodeId + ".snapshot").delete();
    }

    @Test
    void shouldDynamicallyAddNewNodeToCluster() throws InterruptedException {
        System.out.println("=== TEST: Add New Node (D) to existing cluster ===");

        Node<String> nodeA = new Node<>("A", List.of("B", "C"), network);
        Node<String> nodeB = new Node<>("B", List.of("A", "C"), network);
        Node<String> nodeC = new Node<>("C", List.of("A", "B"), network);

        network.addNode(nodeA); cluster.add(nodeA);
        network.addNode(nodeB); cluster.add(nodeB);
        network.addNode(nodeC); cluster.add(nodeC);

        nodeA.start(); nodeB.start(); nodeC.start();

        Thread.sleep(2000);
        Node<String> leader = cluster.stream()
                .filter(n -> n.getRole() == Role.LEADER)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Leader non trovato"));

        System.out.println("Leader: " + leader.getNodeID());

        Node<String> nodeD = new Node<>("D", List.of(leader.getNodeID()), network);
        network.addNode(nodeD);
        cluster.add(nodeD);
        nodeD.start();

        boolean added = leader.addServer("D");
        assertThat(added).isTrue();

        Thread.sleep(1000);

        assertThat(leader.getPeers()).contains("D");

        leader.propose("Client1", 1, "SET testKey=testValue");
        
        Thread.sleep(1000);

        assertThat(nodeD.getLogCopy().size())
            .as("Il nodo D dovrebbe aver ricevuto i log replicati dal leader")
            .isGreaterThan(0);
            
        System.out.println("Stato di testKey su D: " + nodeD.get("testKey"));
    }

    @Test
    void shouldRemoveNodeAndStopReplication() throws InterruptedException {
        System.out.println("=== TEST: Remove Node (C) from cluster ===");

        Node<String> nodeA = new Node<>("A", List.of("B", "C"), network);
        Node<String> nodeB = new Node<>("B", List.of("A", "C"), network);
        Node<String> nodeC = new Node<>("C", List.of("A", "B"), network);

        network.addNode(nodeA); cluster.add(nodeA);
        network.addNode(nodeB); cluster.add(nodeB);
        network.addNode(nodeC); cluster.add(nodeC);

        nodeA.start(); nodeB.start(); nodeC.start();

        Thread.sleep(2000);
        Node<String> leader = cluster.stream()
                .filter(n -> n.getRole() == Role.LEADER)
                .findFirst()
                .orElseThrow();

        Node<String> targetToRemove = cluster.stream()
                .filter(n -> !n.getNodeID().equals(leader.getNodeID()))
                .findFirst()
                .orElseThrow();

        String targetId = targetToRemove.getNodeID();
        System.out.println("Leader: " + leader.getNodeID() + " | Rimuovo: " + targetId);


        boolean removed = leader.removeServer(targetId);
        assertThat(removed).isTrue();

        Thread.sleep(1000);

        assertThat(leader.getPeers()).doesNotContain(targetId);

        int logSizeBefore = targetToRemove.getLogCopy().size();

        leader.propose("Client1", 2, "SET afterRemoval=true");
        
        Thread.sleep(1000);

        int logSizeAfter = targetToRemove.getLogCopy().size();
        assertThat(logSizeAfter)
            .as("Il log del nodo rimosso non deve crescere")
            .isEqualTo(logSizeBefore);
            
        assertThat(targetToRemove.getRole()).isEqualTo(Role.FOLLOWER);
    }
}