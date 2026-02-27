package com.raft.core;

import com.google.gson.Gson;
import com.raft.rpc.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsServer;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class HttpNetwork implements Network{
    private final HttpClient httpClient;
    private final Gson gson;
    private final Map<String, String> peerAddresses;
    private RaftMessageReceiver localNode;

    public HttpNetwork(int localPort, Map<String, String> peerAddresses){
        this.peerAddresses = peerAddresses;
        this.httpClient = HttpClient.newBuilder().executor(Executors.newVirtualThreadPerTaskExecutor()).build();
        this.gson = new Gson();

        try{
            HttpServer server = HttpServer.create(new InetSocketAddress(localPort), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

            server.createContext("/requestVote", this::handleRequestVoteHttp);
            server.createContext("/appendEntries", this::handleAppendEntriesHttp);
            server.createContext("/installSnapshot", this::handleInstallSnapshotHttp);
            server.createContext("/preVote", this::handlePreVoteHttp);

            server.createContext("/clientGet", this::handleClientGetHttp);
            server.createContext("/clientPropose", this::handleClientProposeHttp);

            server.start();
            System.out.println("HTTP Server listening on port " + localPort);
        }
        catch (IOException e){
            throw new RuntimeException("Unable to start the HTTP Server: ", e);
        }
    }

    public void registerLocalNode(RaftMessageReceiver node){
        this.localNode = node;
    }

    private <T,R> CompletableFuture<R> sendPostRequest(String targetNodeID, String path, T requestObj, Class<R> responseClass){
        String targetUrl = peerAddresses.get(targetNodeID);

        if (targetUrl == null){
            return CompletableFuture.failedFuture(new RuntimeException("Unknown Peer Address: " + targetNodeID));
        }

        String jsonPayload = gson.toJson(requestObj);
        HttpRequest httpRequest = HttpRequest.newBuilder().uri(URI.create(targetUrl + path)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(jsonPayload, StandardCharsets.UTF_8)).build();

        return httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString()).thenApply(response -> gson.fromJson(response.body(), responseClass));
    }

    @Override
    public CompletableFuture<RequestVoteResponse> sendRequestVote(String targetNodeID, RequestVoteRequest request) {
        return sendPostRequest(targetNodeID, "/requestVote", request, RequestVoteResponse.class);
    }

    @Override
    public CompletableFuture<AppendEntriesResponse> sendAppendEntries(String targetNodeID, AppendEntriesRequest request) {
        return sendPostRequest(targetNodeID, "/appendEntries", request, AppendEntriesResponse.class);
    }

    @Override
    public CompletableFuture<InstallSnapshotResponse> sendInstallSnapshot(String targetNodeID, InstallSnapshotRequest request) {
        return sendPostRequest(targetNodeID, "/installSnapshot", request, InstallSnapshotResponse.class);
    }

    @Override
    public CompletableFuture<PreVoteResponse> sendPreVote(String targetNodeID, PreVoteRequest request) {
        return sendPostRequest(targetNodeID, "/preVote", request, PreVoteResponse.class);
    }

    @Override
    public CompletableFuture<String> sendClientGet(String targetNodeId, String key) {
        String targetUrl = peerAddresses.get(targetNodeId);
        if (targetUrl == null) return CompletableFuture.failedFuture(new RuntimeException("Peer sconosciuto"));

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(targetUrl + "/clientGet?key=" + key))
                .GET()
                .build();

        return httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
                .thenApply(HttpResponse::body);
    }

    private void handleRequestVoteHttp(HttpExchange exchange) throws IOException{
        processPost(exchange, RequestVoteRequest.class, req -> localNode.handleRequestVote(req));
    }

    private void handleAppendEntriesHttp(HttpExchange exchange) throws IOException {
        processPost(exchange, AppendEntriesRequest.class, req -> localNode.handleAppendEntries(req));
    }

    private void handleInstallSnapshotHttp(HttpExchange exchange) throws IOException {
        processPost(exchange, InstallSnapshotRequest.class, req -> localNode.handleInstallSnapshot(req));
    }

    private void handlePreVoteHttp(HttpExchange exchange) throws IOException {
        processPost(exchange, PreVoteRequest.class, req -> localNode.handlePreVote(req));
    }

    private void handleClientGetHttp(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        
        String query = exchange.getRequestURI().getQuery();
        String key = query != null && query.startsWith("key=") ? query.substring(4) : "";
        
        try {
            com.raft.node.Node<?> castedNode = (com.raft.node.Node<?>) localNode;
            String result = castedNode.get(key);
            String response = result != null ? result : "null";
            sendTextResponse(exchange, 200, response);
        } catch (Exception e) {
            sendTextResponse(exchange, 500, e.getMessage());
        }
    }

    private void handleClientProposeHttp(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        try (InputStreamReader reader = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
            StringBuilder body = new StringBuilder();
            int c;
            while ((c = reader.read()) != -1) {
                body.append((char) c);
            }
            
            com.raft.node.Node<String> castedNode = (com.raft.node.Node<String>) localNode;
            boolean success = castedNode.propose("HTTPClient", System.currentTimeMillis(), body.toString());
            
            if (success) {
                sendTextResponse(exchange, 200, "Proposta accettata");
            } else {
                sendTextResponse(exchange, 503, "Nodo non Leader");
            }
        } catch (Exception e) {
            sendTextResponse(exchange, 500, e.getMessage());
        }
    }

    private <T, R> void processPost(HttpExchange exchange, Class<T> reqClass, java.util.function.Function<T, R> handler) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        try (InputStreamReader reader = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
            T requestObj = gson.fromJson(reader, reqClass);
            R responseObj = handler.apply(requestObj);
            
            String jsonResponse = gson.toJson(responseObj);
            byte[] bytes = jsonResponse.getBytes(StandardCharsets.UTF_8);
            
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        } catch (Exception e) {
            exchange.sendResponseHeaders(500, -1);
        }
    }

    private void sendTextResponse(HttpExchange exchange, int statusCode, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}