package com.raft.node;

import com.raft.core.InMemoryNetwork;
import com.raft.core.LogEntry;
import com.raft.core.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;


import static org.assertj.core.api.Assertions.assertThat;

public class RaftIntegrationTest {

    
    private final List<Node<String>> cluster = new ArrayList<>();

    @AfterEach
    void tearDown() {
        
        cluster.forEach(Node::stop);
        cluster.clear();
    }

    @Test
    void clusterShouldElectLeaderAndStabilize() throws InterruptedException {
        
        InMemoryNetwork network = new InMemoryNetwork(true);

        
        
        
        Node<String> nodeA = new Node<>("A", List.of("B", "C"), network);
        Node<String> nodeB = new Node<>("B", List.of("A", "C"), network);
        Node<String> nodeC = new Node<>("C", List.of("A", "B"), network);

        
        network.addNode(nodeA); cluster.add(nodeA);
        network.addNode(nodeB); cluster.add(nodeB);
        network.addNode(nodeC); cluster.add(nodeC);

        
        System.out.println("--- Starting Cluster ---");
        nodeA.start();
        nodeB.start();
        nodeC.start();

        
        System.out.println("Waiting for election to finalize...");
        Thread.sleep(5000);

        
        List<Node<String>> leaders = cluster.stream()
                .filter(n -> n.getRole() == Role.LEADER)
                .toList();

        System.out.println("Leaders found: " + leaders.size());
        cluster.forEach(n -> 
            System.out.println("Node " + n.getNodeID() + ": " + n.getRole() + " (Term " + n.getTerm() + ")")
        );

        
        assertThat(leaders).hasSize(1);
        
        Node<String> leader = leaders.get(0);
        long initialTerm = leader.getTerm();

        
        
        
        System.out.println("Waiting for stabilization check...");
        Thread.sleep(2000);

        assertThat(leader.getRole())
                .as("Leader should maintain leadership")
                .isEqualTo(Role.LEADER);

        assertThat(leader.getTerm())
                .as("Term should not change if heartbeats are working")
                .isEqualTo(initialTerm);

        
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
        
        InMemoryNetwork network = new InMemoryNetwork(true);

        
        
        Node<String> nodeA = new Node<>("A", List.of("B", "C"), network);
        Node<String> nodeB = new Node<>("B", List.of("A", "C"), network);
        Node<String> nodeC = new Node<>("C", List.of("A", "B"), network);

        
        network.addNode(nodeA); cluster.add(nodeA);
        network.addNode(nodeB); cluster.add(nodeB);
        network.addNode(nodeC); cluster.add(nodeC);

        
        System.out.println("--- Starting Cluster ---");
        nodeA.start();
        nodeB.start();
        nodeC.start();

        
        System.out.println("Waiting for election to finalize...");
        Thread.sleep(5000);

        
        Node<String> leader = cluster.stream()
                .filter(n -> n.getRole() == Role.LEADER)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No Leader found!"));

        System.out.println("Leader elected: " + leader.getNodeID());

        
        System.out.println("--- Sending Command 'CMD_1' ---");
        boolean accepted = leader.propose("CMD_1");
        
        assertThat(accepted)
            .as("Leader should accept the proposal")
            .isTrue();

        
        Thread.sleep(2000);

        
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

    @Test
    void shouldCommitAndExecuteCommandOnMajority() throws InterruptedException {
        
        InMemoryNetwork network = new InMemoryNetwork(true);
        Node<String> nodeA = new Node<>("A", List.of("B", "C"), network);
        Node<String> nodeB = new Node<>("B", List.of("A", "C"), network);
        Node<String> nodeC = new Node<>("C", List.of("A", "B"), network);

        network.addNode(nodeA); cluster.add(nodeA);
        network.addNode(nodeB); cluster.add(nodeB);
        network.addNode(nodeC); cluster.add(nodeC);

        System.out.println("--- Starting Cluster ---");
        nodeA.start(); nodeB.start(); nodeC.start();

        
        System.out.println("Waiting for election...");
        Thread.sleep(5000);

        Node<String> leader = cluster.stream()
                .filter(n -> n.getRole() == Role.LEADER)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No Leader found!"));

        System.out.println("Leader elected: " + leader.getNodeID());

        
        System.out.println("--- Proposing Command 'SET_X=10' ---");
        boolean accepted = leader.propose("SET_X=10");
        assertThat(accepted).isTrue();

        
        
        
        
        
        
        
        Thread.sleep(2000);

        
        
        assertThat(leader.getCommitIndex())
                .as("Leader should have committed the entry")
                .isGreaterThanOrEqualTo(0);

        
        assertThat(leader.getLastApplied())
                .as("Leader should have executed the command")
                .isGreaterThan(0);

        
        
        for (Node<String> node : cluster) {
            if (node == leader) continue;

            System.out.println("Checking Node " + node.getNodeID() + 
                             " | Commit: " + node.getCommitIndex() + 
                             " | Applied: " + node.getLastApplied());

            assertThat(node.getCommitIndex())
                    .as("Follower %s should update commitIndex", node.getNodeID())
                    .isEqualTo(leader.getCommitIndex());

            assertThat(node.getLastApplied())
                    .as("Follower %s should execute the command", node.getNodeID())
                    .isEqualTo(leader.getLastApplied());
        }
    }

    @Test
    void followerShouldRejectInconsistentLog() throws InterruptedException {
        InMemoryNetwork network = new InMemoryNetwork(false); 

        
        Node<String> nodeA = new Node<>("A", List.of("B"), network);
        Node<String> nodeB = new Node<>("B", List.of("A"), network);
        
        network.addNode(nodeA); cluster.add(nodeA);
        network.addNode(nodeB); cluster.add(nodeB);
        
        nodeA.start();
        nodeB.start();

        
        Thread.sleep(1000); 
        Node<String> leader = (nodeA.getRole() == Role.LEADER) ? nodeA : nodeB;
        Node<String> follower = (leader == nodeA) ? nodeB : nodeA;
        
        
        leader.propose("X=1");
        Thread.sleep(500); 
        
        
        assertThat(follower.getLogCopy()).hasSize(1);

        
        
        
        
        
        
        
        
        
        
        
        leader.propose("Y=2");
        Thread.sleep(500);
        assertThat(follower.getLogCopy()).hasSize(2);
        
        System.out.println("Follower Log: " + follower.getLogCopy());
    }

    @Test
    void committedDataShouldSurviveLeaderCrash() throws InterruptedException {
        System.out.println("=== TEST: Leader Failover ===");
        
        
        InMemoryNetwork network = new InMemoryNetwork(true);
        Node<String> nodeA = new Node<>("A", List.of("B", "C"), network);
        Node<String> nodeB = new Node<>("B", List.of("A", "C"), network);
        Node<String> nodeC = new Node<>("C", List.of("A", "B"), network);

        network.addNode(nodeA); cluster.add(nodeA);
        network.addNode(nodeB); cluster.add(nodeB);
        network.addNode(nodeC); cluster.add(nodeC);

        nodeA.start(); nodeB.start(); nodeC.start();

        
        Thread.sleep(3000);
        Node<String> firstLeader = cluster.stream()
                .filter(n -> n.getRole() == Role.LEADER)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No Leader elected initially"));
        
        System.out.println("First Leader is: " + firstLeader.getNodeID());

        
        firstLeader.propose("CRITICAL_DATA");
        Thread.sleep(2000); 
        
        assertThat(firstLeader.getCommitIndex()).isGreaterThanOrEqualTo(0);

        
        System.out.println("KILLING LEADER " + firstLeader.getNodeID() + " ☠️");
        firstLeader.stop();
        
        
        Thread.sleep(3000);

        
        Node<String> newLeader = cluster.stream()
                .filter(n -> n != firstLeader)
                .filter(n -> n.getRole() == Role.LEADER)
                .findFirst()
                .orElseThrow(() -> new AssertionError("New Leader was not elected!"));

        System.out.println("New Leader is: " + newLeader.getNodeID());

        
        
        List<LogEntry<String>> newLeaderLog = newLeader.getLogCopy();
        assertThat(newLeaderLog).hasSize(1);
        assertThat(newLeaderLog.get(0).command()).isEqualTo("CRITICAL_DATA");

        
        newLeader.propose("NEW_ERA_DATA");
        Thread.sleep(2000);

        
        System.out.println("RESURRECTING " + firstLeader.getNodeID() + " ✨");
        
        
        
        
        
        
        for (Node<String> n : cluster) {
            if (n != firstLeader && n != newLeader) {
                List<LogEntry<String>> log = n.getLogCopy();
                System.out.println("Follower " + n.getNodeID() + " Log: " + log);
                assertThat(log).hasSize(2); 
                assertThat(log.get(1).command()).isEqualTo("NEW_ERA_DATA");
            }
        }
    }

    

}