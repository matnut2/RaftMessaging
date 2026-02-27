package com.raft;

import com.raft.core.HttpNetwork;
import com.raft.node.Node;

import java.util.List;
import java.util.Map;

public class main {
    public static void main(String[] args) throws InterruptedException {
        String nodeId = args.length > 0 ? args[0] : "A";

        Map<String, String> clusterAddresses = Map.of(
            "A", "http://127.0.0.1:8081",
            "B", "http://127.0.0.1:8082",
            "C", "http://127.0.0.1:8083"
        );

        int localPort;
        List<String> peers;

        switch (nodeId) {
            case "A" -> { localPort = 8081; peers = List.of("B", "C"); }
            case "B" -> { localPort = 8082; peers = List.of("A", "C"); }
            case "C" -> { localPort = 8083; peers = List.of("A", "B"); }
            default -> throw new IllegalArgumentException("Unkwnown Node: " + nodeId);
        }

        HttpNetwork network = new HttpNetwork(localPort, clusterAddresses);
        Node<String> node = new Node<>(nodeId, peers, network);
        
        network.registerLocalNode(node);
        node.start();
        
        System.out.println("Nodo " + nodeId + " in esecuzione.");
        Thread.currentThread().join();
    }
}