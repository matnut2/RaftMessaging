package com.raft.node;

import com.raft.core.LogEntry;
import com.raft.core.Role;
import com.raft.core.Network;
import com.raft.rpc.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

public class Node<T>{
    private final String nodeID;
    private final List<String> peers;
    private final Network network;
    private final ExecutorService vThreadExecutor;
    private final ReentrantLock lock;
    private final Random random;
    private volatile boolean running;

    private final AtomicLong lastElectionResetTime;
    private final int MIN_TIMEOUT_MS = 150;
    private final int MAX_TIMEOUT_MS = 300;
    private final int heartbeatInterval = 50;
    private final int electionTimeout;

    private long currentTerm;
    private String votedFor;
    private final List<LogEntry<T>> log;

    private Role currentRole;
    private int votesReceived;

    public Node(String nodeID, List<String> peers, Network network){
        this.nodeID = nodeID;
        this.peers = peers;
        this.network = network;
        this.vThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.lock = new ReentrantLock();
        this.random = new Random();

        this.currentTerm = 0;
        this.votedFor = null;
        this.log = new ArrayList<>();

        this.currentRole = Role.FOLLOWER;
        this.electionTimeout = MIN_TIMEOUT_MS + random.nextInt(MAX_TIMEOUT_MS - MIN_TIMEOUT_MS);

        this.lastElectionResetTime = new AtomicLong(System.currentTimeMillis());
        this.running = true;
    }

    public void start(){
        vThreadExecutor.submit(this::runElectionLoop);
        System.out.println("Node " + nodeID + " started.");
    }

    public void stop(){
        this.running = false;
        vThreadExecutor.shutdownNow();
    }

    private void runElectionLoop(){
        while(running){
            long timeout = MIN_TIMEOUT_MS + random.nextInt(MAX_TIMEOUT_MS-MIN_TIMEOUT_MS);

            try{
                Thread.sleep(timeout);
            }
            catch (InterruptedException e){
                if (!running) break;
            }

            long now = System.currentTimeMillis();
            long elapsed = now - lastElectionResetTime.get();
            if (elapsed >= timeout){
                startElection();
            }
        }
    }

    private void startElection(){
        lock.lock();

        try{
            if (currentRole == Role.LEADER){
                return;
            }

            System.out.println("Node " + nodeID + " timeout passed. Starting election for term " + (currentTerm+1));

            currentRole = Role.CANDIDATE;
            currentTerm += 1;
            votedFor = nodeID;

            votesReceived = 1;
            System.out.println("Node " + nodeID + " starting election for term " + currentTerm);

            resetElectionTimer();

            RequestVoteRequest request = new RequestVoteRequest(currentTerm, nodeID, 0, 0);

            for (String peerID : peers){
                vThreadExecutor.submit(() -> network.sendRequestVote(peerID, request).thenAccept(this::handleVoteResponse));
            }
        }
        finally{
            lock.unlock();
        }
    }

    private void runHearthbeatLoop(){
        while (currentRole == Role.LEADER && running){
            long start = System.currentTimeMillis();

            sendHearthbeats();

            long elapsed = System.currentTimeMillis() - start;
            long sleepTime = heartbeatInterval - elapsed;

            if (sleepTime > 0){
                try{
                    Thread.sleep(sleepTime);
                }
                catch (InterruptedException e){
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void handleHeartbeatResponse(AppendEntriesResponse response) {
        lock.lock();
        try {
            if (response.term() > currentTerm) {
                System.out.println("Node " + nodeID + " stepping down (higher term found)");
                currentTerm = response.term();
                currentRole = Role.FOLLOWER;
                votedFor = null;
            }
        } finally {
            lock.unlock();
        }
    }

    private void sendHearthbeats(){
        AppendEntriesRequest request;

        lock.lock();

        try{
            if (currentRole != Role.LEADER) return;

            request = new AppendEntriesRequest<>(currentTerm, nodeID, 0, 0, new ArrayList<>(), 0);
        }
        finally{
            lock.unlock();
        }

        for (String peerId : peers) {
            vThreadExecutor.submit(() -> 
                network.sendAppendEntries(peerId, request)
                       .thenAccept(this::handleHeartbeatResponse)
            );
        }
    }

    public RequestVoteResponse handleRequestVote(RequestVoteRequest request){
        lock.lock();

        try{
            if (request.term() > currentTerm){
                currentTerm = request.term();
                currentRole = Role.FOLLOWER;
                votedFor = null;
            }

            boolean voteGranted = false;

            if (request.term() < currentTerm){
                voteGranted = false;
            }
            else if (votedFor == null || votedFor.equals(request.candidateId())){
                votedFor = request.candidateId();
                voteGranted = true;
                resetElectionTimer();
            }

            return new RequestVoteResponse(currentTerm, voteGranted);
        }
        finally{
            lock.unlock();
        }
    }

    private void handleVoteResponse(RequestVoteResponse response){
        lock.lock();

        try{
            if (response.term() > currentTerm){
                currentRole = Role.FOLLOWER;
                currentTerm = response.term();
                votedFor = null;
                return;
            }

            if (currentRole != Role.CANDIDATE){
                return;
            }

            if (response.voteGranted()){
                votesReceived += 1;
                int clusterSize = peers.size() + 1;
                int quorum = (clusterSize / 2) + 1;

                if (votesReceived >= quorum){
                    becomeLeader();
                }
            }
        }
        finally{
            lock.unlock();
        }
    }

    private void becomeLeader(){
        if (currentRole == Role.LEADER) return;

        currentRole = Role.LEADER;
        System.out.println("NODE " + nodeID + " BECAME LEADER (Term " + currentTerm + ")");

        vThreadExecutor.submit(this::runHearthbeatLoop);
    }

    public void resetElectionTimer(){
        this.lastElectionResetTime.set(System.currentTimeMillis());
    }

    public Role getRole(){
        lock.lock();
        try{
            return currentRole;
        }
        finally{
            lock.unlock();
        }
    }

    public long getTerm(){
        lock.lock();
        try{
            return currentTerm;
        }
        finally{
            lock.unlock();
        }
    }

    public String getNodeID(){
        lock.lock();

        try{
            return nodeID;
        }
        finally{
            lock.unlock();
        }
    }

    public AppendEntriesResponse handleAppendEntries(AppendEntriesRequest<?> request) {
        lock.lock();
        try {
            if (request.term() < currentTerm) {
                return new AppendEntriesResponse(currentTerm, false);
            }

            if (request.term() >= currentTerm) {
                currentTerm = request.term();
                currentRole = Role.FOLLOWER;
                votedFor = null;
                
                if (currentRole == Role.CANDIDATE) {
                    currentRole = Role.FOLLOWER;
                }
            }

            resetElectionTimer();

            return new AppendEntriesResponse(currentTerm, true);
        } finally {
            lock.unlock();
        }
    }
}