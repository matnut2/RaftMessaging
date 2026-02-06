package com.raft.node;

import com.raft.core.InMemoryNetwork;
import com.raft.core.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class RaftKVTest {

    private final List<Node<String>> cluster = new ArrayList<>();

    @AfterEach
    void tearDown() {
        cluster.forEach(Node::stop);
        cluster.clear();
    }

    @Test
    void clusterShouldActAsKeyValueStore() throws InterruptedException {
        System.out.println("=== TEST: Distributed Key-Value Store ===");
        
        
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
        
        System.out.println("👑 Leader: " + leader.getNodeID());

        
        
        System.out.println("--- WRITING DATA ---");
        leader.propose("SET username=admin");
        leader.propose("SET currency=EUR");
        leader.propose("SET status=active");
        
        
        Thread.sleep(2000);

        
        
        System.out.println("--- READING DATA ---");
        
        for (Node<String> node : cluster) {
            String user = node.get("username");
            String curr = node.get("currency");
            
            System.out.println("Node " + node.getNodeID() + " has username=" + user);
            
            assertThat(user).isEqualTo("admin");
            assertThat(curr).isEqualTo("EUR");
        }

        
        System.out.println("--- UPDATING DATA ---");
        leader.propose("SET currency=USD");
        leader.propose("DEL status"); 
        
        Thread.sleep(2000);

        
        Node<String> follower = cluster.stream()
                .filter(n -> n != leader)
                .findFirst()
                .orElseThrow();

        assertThat(follower.get("currency")).isEqualTo("USD");
        assertThat(follower.get("status")).isNull();

        System.out.println("✅ Test Passed: Il cluster si comporta come un Database coerente!");
    }
}