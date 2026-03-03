package com.raft;

import com.raft.core.HttpNetwork;
import com.raft.node.Node;

import java.util.List;
import java.util.Map;

public class main {
    public static void main(String[] args) throws InterruptedException {
        String nodeId = args.length > 0 ? args[0] : "A";

        // Utilizzo dei nomi dei servizi Docker al posto di 127.0.0.1
        Map<String, String> clusterAddresses = Map.of(
            "A", "http://node-a:8081",
            "B", "http://node-b:8082",
            "C", "http://node-c:8083"
        );

        int localPort;
        List<String> peers;

        switch (nodeId) {
            case "A" -> { localPort = 8081; peers = List.of("B", "C"); }
            case "B" -> { localPort = 8082; peers = List.of("A", "C"); }
            case "C" -> { localPort = 8083; peers = List.of("A", "B"); }
            default -> throw new IllegalArgumentException("Unknown Node: " + nodeId);
        }

        HttpNetwork network = new HttpNetwork(localPort, clusterAddresses);
        Node<String> node = new Node<>(nodeId, peers, network);
        
        network.registerLocalNode(node);
        node.start();
        
        System.out.println("Node " + nodeId + " in execution.");
        Thread.currentThread().join();
    }
}