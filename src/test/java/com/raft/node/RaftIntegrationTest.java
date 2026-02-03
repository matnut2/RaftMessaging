package com.raft.node;

import com.raft.core.InMemoryNetwork;
import com.raft.core.LogEntry;
import com.raft.core.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

// We use AssertJ for readable assertions
import static org.assertj.core.api.Assertions.assertThat;

public class RaftIntegrationTest {

    // Keep track of nodes to stop them after test
    private final List<Node<String>> cluster = new ArrayList<>();

    @AfterEach
    void tearDown() {
        // Stop all virtual threads to avoid memory leaks or zombie threads
        cluster.forEach(Node::stop);
        cluster.clear();
    }

    @Test
    void clusterShouldElectLeaderAndStabilize() throws InterruptedException {
        // 1. Create Network (with simulated latency = true)
        InMemoryNetwork network = new InMemoryNetwork(true);

        // 2. Create Nodes
        // We use the simple constructor. The nodes will pick their own random fixed timeout
        // between 150ms and 300ms.
        Node<String> nodeA = new Node<>("A", List.of("B", "C"), network);
        Node<String> nodeB = new Node<>("B", List.of("A", "C"), network);
        Node<String> nodeC = new Node<>("C", List.of("A", "B"), network);

        // 3. Register nodes in the network and local list
        network.addNode(nodeA); cluster.add(nodeA);
        network.addNode(nodeB); cluster.add(nodeB);
        network.addNode(nodeC); cluster.add(nodeC);

        // 4. Start the cluster
        System.out.println("--- Starting Cluster ---");
        nodeA.start();
        nodeB.start();
        nodeC.start();

        // 5. Wait for Election (5 seconds is plenty for 150-300ms timeouts)
        System.out.println("Waiting for election to finalize...");
        Thread.sleep(5000);

        // 6. Verify a Leader exists
        List<Node<String>> leaders = cluster.stream()
                .filter(n -> n.getRole() == Role.LEADER)
                .toList();

        System.out.println("Leaders found: " + leaders.size());
        cluster.forEach(n -> 
            System.out.println("Node " + n.getNodeID() + ": " + n.getRole() + " (Term " + n.getTerm() + ")")
        );

        // ASSERTION: There must be exactly one leader
        assertThat(leaders).hasSize(1);
        
        Node<String> leader = leaders.get(0);
        long initialTerm = leader.getTerm();

        // 7. Verify Stabilization (Heartbeats)
        // We wait another 2 seconds. Since Heartbeats are 50ms, 
        // the leader should keep everyone quiet. The term should NOT increase.
        System.out.println("Waiting for stabilization check...");
        Thread.sleep(2000);

        assertThat(leader.getRole())
                .as("Leader should maintain leadership")
                .isEqualTo(Role.LEADER);

        assertThat(leader.getTerm())
                .as("Term should not change if heartbeats are working")
                .isEqualTo(initialTerm);

        // Check that followers are synced to the leader's term
        long leaderTerm = leader.getTerm();
        cluster.stream()
               .filter(n -> n != leader)
               .forEach(follower -> {
                   assertThat(follower.getRole()).isEqualTo(Role.FOLLOWER);
                   assertThat(follower.getTerm()).isLessThanOrEqualTo(leaderTerm);
               });
    }

    @Test
    void shouldReplicateCommandsToFollowers() throws InterruptedException {
        // 1. Create Network (with simulated latency = true)
        InMemoryNetwork network = new InMemoryNetwork(true);

        // 2. Create Nodes
        // The nodes will pick their own random fixed timeout between 300ms and 600ms.
        Node<String> nodeA = new Node<>("A", List.of("B", "C"), network);
        Node<String> nodeB = new Node<>("B", List.of("A", "C"), network);
        Node<String> nodeC = new Node<>("C", List.of("A", "B"), network);

        // 3. Register nodes in the network and local list
        network.addNode(nodeA); cluster.add(nodeA);
        network.addNode(nodeB); cluster.add(nodeB);
        network.addNode(nodeC); cluster.add(nodeC);

        // 4. Start the cluster
        System.out.println("--- Starting Cluster ---");
        nodeA.start();
        nodeB.start();
        nodeC.start();

        // 5. Wait for Election (5 seconds is plenty for stabilization)
        System.out.println("Waiting for election to finalize...");
        Thread.sleep(5000);

        // 6. Find the Leader
        Node<String> leader = cluster.stream()
                .filter(n -> n.getRole() == Role.LEADER)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No Leader found!"));

        System.out.println("Leader elected: " + leader.getNodeID());

        // 7. Propose a Command
        System.out.println("--- Sending Command 'CMD_1' ---");
        boolean accepted = leader.propose("CMD_1");
        
        assertThat(accepted)
            .as("Leader should accept the proposal")
            .isTrue();

        // 8. Wait for Replication (Heartbeat interval is 20-50ms, so 2 seconds is enough)
        Thread.sleep(2000);

        // 9. Verify that ALL nodes have the command in their log
        for (Node<String> node : cluster) {
            List<LogEntry<String>> log = node.getLogCopy();
            
            System.out.println("Node " + node.getNodeID() + " Log: " + log);

            assertThat(log)
                .as("Node %s should have exactly 1 entry", node.getNodeID())
                .hasSize(1);
            
            assertThat(log.get(0).command())
                .as("Node %s should have the correct command", node.getNodeID())
                .isEqualTo("CMD_1");
        }
    }
}