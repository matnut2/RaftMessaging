package com.raft.node;

import com.raft.core.Role;
import com.raft.core.Network;
import com.raft.rpc.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.concurrent.CompletableFuture;

public class NodeTest {
    private Node<String> node;
    private final Network network = new Network() {
        
        @Override
        public CompletableFuture<RequestVoteResponse> sendRequestVote(String targetNodeID, RequestVoteRequest r){
            return CompletableFuture.completedFuture(new RequestVoteResponse(0, false));
        }

        @Override
        public CompletableFuture<AppendEntriesResponse> sendAppendEntries(String targetNodeID, AppendEntriesRequest r){
            return CompletableFuture.completedFuture(new AppendEntriesResponse(0, false));
        }
    };

    @BeforeEach
    void setUp(){
        node = new Node<>("Test-Node", Collections.emptyList(), network);
    }

    @AfterEach
    void tearDown(){
        if (node != null) node.stop();
    }

    @Test
    @DisplayName("The Node has to start with role FOLLOWER and Term 0")
    void shouldStartAsFollower(){
        assertThat(node.getRole()).isEqualTo(Role.FOLLOWER);
        assertThat(node.getTerm()).isEqualTo(0);
    }

    @Test
    @DisplayName("The node has to become a CANDIDATE for term 1 after the timeout")
    void shouldBecomeCandidateAfterTimeout() throws InterruptedException{
        node.start();
        Thread.sleep(500);

        assertThat(node.getRole()).isEqualTo(Role.CANDIDATE);
        assertThat(node.getTerm()).isGreaterThan(0);
    }   
}
