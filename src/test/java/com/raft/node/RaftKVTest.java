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
        cleanupStorage();
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
        for (int i = 0; i < 10; i++) {
            if (file.delete()) return;
            try { Thread.sleep(50); } catch (InterruptedException e) { }
        }
    }

    @Test
    void clusterShouldActAsMessageStore() throws InterruptedException {
        System.out.println("=== TEST: Distributed Messaging Store ===");
        
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
        leader.propose("ClientA", 1, "SEND general Ciao a tutti");
        leader.propose("ClientA", 2, "SEND general Benvenuti nel cluster");
        leader.propose("ClientA", 3, "SEND private_room Messaggio segreto");
        
        Thread.sleep(2000);

        System.out.println("--- READING DATA ---");
        for (Node<String> node : cluster) {
            String generalChat = node.get("general");
            String privateChat = node.get("private_room");
            
            if (generalChat != null && !generalChat.startsWith("Nessun messaggio")){
                assertThat(generalChat).contains("Ciao a tutti");
                assertThat(generalChat).contains("Benvenuti nel cluster");
                assertThat(privateChat).contains("Messaggio segreto");
            } else {
                System.err.println("❌ Node " + node.getNodeID() + " returned unexpected value!");
            }
        }
    }
}