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
        // Pulizia file precedenti per garantire un test pulito
        deleteNodeFiles("A");
        deleteNodeFiles("B");
        deleteNodeFiles("C");
    }

    @AfterEach
    void tearDown() {
        cluster.forEach(Node::stop);
        cluster.clear();
        // Pulizia post-test
        deleteNodeFiles("A");
        deleteNodeFiles("B");
        deleteNodeFiles("C");
    }

    private void deleteNodeFiles(String nodeId) {
        new File("raft_node_" + nodeId + ".dat").delete();
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

        // 1. Aspettiamo l'elezione del Leader
        Thread.sleep(3000);
        Node<String> leader = cluster.stream()
                .filter(n -> n.getRole() == Role.LEADER)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Leader not found"));

        System.out.println("Leader: " + leader.getNodeID());

        // 2. Identifichiamo un Follower da "uccidere" temporaneamente (es. Node C)
        Node<String> victim = cluster.stream()
                .filter(n -> !n.getNodeID().equals(leader.getNodeID()))
                .findFirst().orElseThrow();
        
        System.out.println("Stopping Victim Node: " + victim.getNodeID());
        victim.stop(); 

        // 3. Scriviamo dati sul Leader mentre la vittima è offline
        System.out.println("--- Writing Data (Victim is offline) ---");
        for (int i = 0; i < 10; i++) {
            leader.propose("SET key" + i + "=value" + i);
            Thread.sleep(100); // Piccolo delay per dare tempo al commit
        }

        // 4. Forziamo uno SNAPSHOT sul Leader
        // Questo troncherà il log del Leader.
        // Assumiamo che il Leader abbia applicato tutti i 10 comandi.
        Thread.sleep(1000); 
        System.out.println("--- Forcing Snapshot on Leader ---");
        leader.takeSnapshot();

        // Verifica: Il log del leader dovrebbe essere corto ora (solo le entry dopo lo snapshot, o vuoto)
        assertThat(leader.getLogCopy().size()).isLessThan(10);
        System.out.println("Leader Log size after snapshot: " + leader.getLogCopy().size());

        // 5. Riaccendiamo la vittima
        // Poiché il leader non ha più i log 0-9, DEVE inviare uno snapshot
        System.out.println("--- Restarting Victim Node " + victim.getNodeID() + " ---");
        victim.start();

        // Diamo tempo per la ricezione dell'InstallSnapshot RPC e l'applicazione
        Thread.sleep(3000);

        // 6. Asserzioni
        System.out.println("Checking consistency...");
        
        // Verifica che la vittima abbia i dati (tramite la StateMachine ripristinata)
        String value9 = victim.get("key9");
        assertThat(value9)
            .as("Victim should have received key9 via Snapshot")
            .isEqualTo("value9");

        // Verifica che anche la vittima abbia troncato il log
        // Se avesse ricevuto AppendEntries, avrebbe un log lungo.
        // Avendo ricevuto Snapshot, il suo log in memoria dovrebbe essere corto/vuoto.
        assertThat(victim.getLogCopy().size())
            .as("Victim log should be truncated after installing snapshot")
            .isLessThan(10);

        System.out.println("✅ Test Passed: Snapshot installed successfully!");
    }
}