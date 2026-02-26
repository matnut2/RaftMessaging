package com.raft.core;

import com.raft.node.Node;
import com.raft.rpc.AppendEntriesRequest;
import com.raft.rpc.AppendEntriesResponse;
import com.raft.rpc.InstallSnapshotRequest;
import com.raft.rpc.InstallSnapshotResponse;
import com.raft.rpc.PreVoteRequest;
import com.raft.rpc.PreVoteResponse;
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
        try {
            if (simulateLatency) simulateNetworkDelay();
        
            var target = nodes.get(targetNodeId);
            
            if (target == null) {
                return CompletableFuture.failedFuture(new RuntimeException("Node unreachable"));
            }
        
            @SuppressWarnings("rawtypes")
            com.raft.node.Node rawTarget = (com.raft.node.Node) target;
            
            RequestVoteResponse response = rawTarget.handleRequestVote(request);

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

    @Override
    public CompletableFuture<InstallSnapshotResponse> sendInstallSnapshot(String targetNodeID, InstallSnapshotRequest request){
        try{
            if (simulateLatency) simulateNetworkDelay();

            Node<?> target = nodes.get(targetNodeID);
            if (target == null){
                return CompletableFuture.failedFuture(new RuntimeException("Node Unreachable"));
            }

            @SuppressWarnings("unchecked")
            Node<Object> typedTarget = (Node<Object>) target;

            InstallSnapshotResponse response = typedTarget.handleInstallSnapshot(request);
            return CompletableFuture.completedFuture(response);
        }
        catch (Exception e){
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public CompletableFuture<String> sendClientGet(String targetNodeId, String key){
        try{
            if (simulateLatency) simulateNetworkDelay();

            Node<?> target = (Node<?>) nodes.get(targetNodeId);

            if (target == null)
                return CompletableFuture.failedFuture(new RuntimeException("Node Unreachable"));

            @SuppressWarnings("rawtypes")
            Node rawTarget = (Node) target;

            String result = rawTarget.get(key);
                return CompletableFuture.completedFuture(result);

            } 
            catch (Exception e) {
                return CompletableFuture.failedFuture(e);
        }
    }
    
    @Override
    public CompletableFuture<PreVoteResponse> sendPreVote(String targetNodeId, PreVoteRequest request) {
        try {
            if (simulateLatency) simulateNetworkDelay();
        
            Node<?> target = nodes.get(targetNodeId);
            if (target == null) {
                return CompletableFuture.failedFuture(new RuntimeException("Node unreachable"));
            }
        
            @SuppressWarnings("unchecked")
            Node<Object> typedTarget = (Node<Object>) target;
            
            PreVoteResponse response = typedTarget.handlePreVote(request);
            return CompletableFuture.completedFuture(response);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private void simulateNetworkDelay() {
        try {
            
            
            int delay = 5 + random.nextInt(15);
            TimeUnit.MILLISECONDS.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

}