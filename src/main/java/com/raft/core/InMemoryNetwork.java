package com.raft.core;

import com.raft.node.Node;
import com.raft.rpc.AppendEntriesRequest;
import com.raft.rpc.AppendEntriesResponse;
import com.raft.rpc.RequestVoteRequest;
import com.raft.rpc.RequestVoteResponse;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class InMemoryNetwork implements Network {

    private final Map<String, Node<?>> nodes = new ConcurrentHashMap<>();
    private final boolean simulateLatency;
    private final Random random = new Random();

    public InMemoryNetwork(boolean simulateLatency) {
        this.simulateLatency = simulateLatency;
    }

    public void addNode(Node<?> node) {
        nodes.put(node.getNodeID(), node);
    }

    @Override
    public CompletableFuture<RequestVoteResponse> sendRequestVote(String targetNodeId, RequestVoteRequest request) {
        // NON usiamo più supplyAsync. Eseguiamo direttamente nel thread del chiamante.
        // Poiché il chiamante è un Virtual Thread (dentro Node), questo è efficientissimo.
        try {
            if (simulateLatency) simulateNetworkDelay();
            
            Node<?> target = nodes.get(targetNodeId);
            if (target == null) {
                 // Invece di lanciare eccezione che rompe il thread, restituiamo una future fallita
                return CompletableFuture.failedFuture(new RuntimeException("Node unreachable"));
            }
            
            // Chiamata diretta (Method Call invece di RPC)
            RequestVoteResponse response = target.handleRequestVote(request);
            return CompletableFuture.completedFuture(response);

        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public CompletableFuture<AppendEntriesResponse> sendAppendEntries(String targetNodeId, AppendEntriesRequest request) {
        try {
            if (simulateLatency) simulateNetworkDelay();

            Node<?> target = nodes.get(targetNodeId);
            if (target == null) {
                return CompletableFuture.failedFuture(new RuntimeException("Node unreachable"));
            }

            @SuppressWarnings("unchecked")
            Node<Object> typedTarget = (Node<Object>) target;
            
            AppendEntriesResponse response = typedTarget.handleAppendEntries(request);
            return CompletableFuture.completedFuture(response);

        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private void simulateNetworkDelay() {
        try {
            // Latenza leggera: 5-20ms.
            // Grazie ai Virtual Threads, questo sleep NON blocca il sistema operativo.
            int delay = 5 + random.nextInt(15);
            TimeUnit.MILLISECONDS.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}