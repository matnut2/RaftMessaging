package com.raft.core;

import com.google.gson.Gson;
import com.raft.rpc.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

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

    /**
     * Registers the local Raft node instance with the network layer.
     * <p>This method associates the network handler with a specific {@link RaftMessageReceiver} 
     * to allow incoming RPC requests (such as AppendEntries or RequestVote) to be 
     * forwarded to the local node's logic for processing.</p>
     *
     * @param node The local Raft node instance that will receive and handle incoming messages.
     */
    public void registerLocalNode(RaftMessageReceiver node){
        this.localNode = node;
    }

    /**
     * Sends an asynchronous HTTP POST request to a target peer node.
     * <p>This helper method serializes the request object into a JSON payload and transmits it to the 
     * specified endpoint URL. The response is then asynchronously deserialized back into the 
     * expected response class type.</p>
     *
     * @param <T>           The type of the request body object.
     * @param <R>           The expected type of the response body.
     * @param targetNodeID  The identifier of the destination node used to retrieve its address.
     * @param path          The specific API endpoint path (e.g., "/requestVote").
     * @param requestObj    The payload object to be sent in the request body.
     * @param responseClass The class type used for deserializing the JSON response.
     * @return A {@link CompletableFuture} that will eventually contain the deserialized response object, 
     * or fail with an exception if the node is unknown or the network call fails.
     */
    private <T,R> CompletableFuture<R> sendPostRequest(String targetNodeID, String path, T requestObj, Class<R> responseClass){
        String targetUrl = peerAddresses.get(targetNodeID);

        if (targetUrl == null){
            return CompletableFuture.failedFuture(new RuntimeException("Unknown Peer Address: " + targetNodeID));
        }

        String jsonPayload = gson.toJson(requestObj);
        HttpRequest httpRequest = HttpRequest.newBuilder().uri(URI.create(targetUrl + path)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(jsonPayload, StandardCharsets.UTF_8)).build();

        return httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString()).thenApply(response -> gson.fromJson(response.body(), responseClass));
    }

    /**
     * Sends a RequestVote RPC to a specific peer node asynchronously.
     * <p>This method initiates an HTTP POST request to the target node's {@code /requestVote} endpoint. 
     * It is used by candidates during an election to gather votes from the cluster.</p>
     *
     * @param targetNodeID The unique identifier of the node that should receive the request.
     * @param request      The {@link RequestVoteRequest} object containing the candidate's term and log metadata.
     * @return A {@link CompletableFuture} that will provide the {@link RequestVoteResponse} once the RPC call completes.
     */
    @Override
    public CompletableFuture<RequestVoteResponse> sendRequestVote(String targetNodeID, RequestVoteRequest request) {
        return sendPostRequest(targetNodeID, "/requestVote", request, RequestVoteResponse.class);
    }

    /**
     * Sends an AppendEntries RPC to a specific peer node asynchronously.
     * <p>This method initiates an HTTP POST request to the target node's {@code /appendEntries} endpoint. 
     * It is used by the leader to replicate log entries or to send heartbeats to maintain leadership authority 
     * over the cluster.</p>
     *
     * @param targetNodeID The unique identifier of the node that should receive the request.
     * @param request      The {@link AppendEntriesRequest} object containing the leader's term, log entries, 
     * and consistency metadata.
     * @return A {@link CompletableFuture} that will provide the {@link AppendEntriesResponse} 
     * once the RPC call completes.
     */
    @Override
    public CompletableFuture<AppendEntriesResponse> sendAppendEntries(String targetNodeID, AppendEntriesRequest request) {
        return sendPostRequest(targetNodeID, "/appendEntries", request, AppendEntriesResponse.class);
    }

    /**
     * Sends an InstallSnapshot RPC to a specific peer node asynchronously.
     * <p>This method initiates an HTTP POST request to the target node's {@code /installSnapshot} endpoint. 
     * It is invoked by the leader when a follower is too far behind to be updated via incremental 
     * log entries, requiring the transmission of a state machine snapshot instead.</p>
     *
     * @param targetNodeID The unique identifier of the node that should receive the snapshot chunk.
     * @param request      The {@link InstallSnapshotRequest} object containing the snapshot metadata, 
     * the byte offset, and the raw data chunk.
     * @return A {@link CompletableFuture} that will provide the {@link InstallSnapshotResponse} 
     * once the RPC call completes.
     */
    @Override
    public CompletableFuture<InstallSnapshotResponse> sendInstallSnapshot(String targetNodeID, InstallSnapshotRequest request) {
        return sendPostRequest(targetNodeID, "/installSnapshot", request, InstallSnapshotResponse.class);
    }

    /**
     * Sends a PreVote RPC to a specific peer node asynchronously.
     * <p>This method initiates an HTTP POST request to the target node's {@code /preVote} endpoint. 
     * It is used by a node during the Pre-Vote phase to determine if it can successfully win 
     * an election before incrementing its term, thereby avoiding cluster disruption 
     * caused by partitioned nodes.</p>
     *
     * @param targetNodeID The unique identifier of the node that should receive the pre-vote request.
     * @param request      The {@link PreVoteRequest} object containing the hypothetical next term 
     * and the candidate's log metadata.
     * @return A {@link CompletableFuture} that will provide the {@link PreVoteResponse} 
     * once the RPC call completes.
     */
    @Override
    public CompletableFuture<PreVoteResponse> sendPreVote(String targetNodeID, PreVoteRequest request) {
        return sendPostRequest(targetNodeID, "/preVote", request, PreVoteResponse.class);
    }

    /**
     * Sends a read request (GET) to a specific node's client interface asynchronously.
     * <p>This method is typically used to forward a client read request to the leader 
     * or to query the state machine of a specific node. It performs an HTTP GET request 
     * to the {@code /clientGet} endpoint with the specified key as a query parameter.</p>
     *
     * @param targetNodeId The unique identifier of the node to be queried.
     * @param key          The key or room name for which the state is being requested.
     * @return A {@link CompletableFuture} that will provide the raw response body (usually a JSON string) 
     * representing the current state for the given key.
     */
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

    /**
     * Handles incoming HTTP POST requests for the RequestVote RPC.
     * <p>This method deserializes the request body into a {@link RequestVoteRequest} object and 
     * delegates its processing to the local node's logic. It is part of the server-side 
     * implementation of the election process, where nodes decide whether to grant their 
     * vote to a requesting candidate.</p>
     *
     * @param exchange The {@link HttpExchange} containing the HTTP request and used to send the response.
     * @throws IOException If an I/O error occurs during request processing or response transmission.
     */
    private void handleRequestVoteHttp(HttpExchange exchange) throws IOException{
        processPost(exchange, RequestVoteRequest.class, req -> localNode.handleRequestVote(req));
    }

    /**
     * Handles incoming HTTP POST requests for the AppendEntries RPC.
     * <p>This method deserializes the request body into an {@link AppendEntriesRequest} object 
     * and delegates its processing to the local node's logic. It is the server-side entry 
     * point for log replication and heartbeats sent by the cluster leader.</p>
     *
     * @param exchange The {@link HttpExchange} containing the HTTP request and used to send the response.
     * @throws IOException If an I/O error occurs during request processing or response transmission.
     */
    private void handleAppendEntriesHttp(HttpExchange exchange) throws IOException {
        processPost(exchange, AppendEntriesRequest.class, req -> localNode.handleAppendEntries(req));
    }

    /**
     * Handles incoming HTTP POST requests for the InstallSnapshot RPC.
     * <p>This method deserializes the request body into an {@link InstallSnapshotRequest} object 
     * and delegates its processing to the local node's logic. It is invoked when a follower 
     * needs to receive a snapshot from the leader to synchronize its state.</p>
     *
     * @param exchange The {@link HttpExchange} containing the HTTP request and used to send the response.
     * @throws IOException If an I/O error occurs during request processing or response transmission.
     */
    private void handleInstallSnapshotHttp(HttpExchange exchange) throws IOException {
        processPost(exchange, InstallSnapshotRequest.class, req -> localNode.handleInstallSnapshot(req));
    }

    /**
     * Handles incoming HTTP POST requests for the PreVote RPC.
     * <p>This method deserializes the request body into a {@link PreVoteRequest} object 
     * and delegates its processing to the local node's logic. It is part of the 
     * implementation of the Pre-Vote phase, allowing the node to respond whether 
     * it would grant a vote to the sender in a future term.</p>
     *
     * @param exchange The {@link HttpExchange} containing the HTTP request and used to send the response.
     * @throws IOException If an I/O error occurs during request processing or response transmission.
     */
    private void handlePreVoteHttp(HttpExchange exchange) throws IOException {
        processPost(exchange, PreVoteRequest.class, req -> localNode.handlePreVote(req));
    }

    /**
     * Handles incoming HTTP GET requests from clients to retrieve state machine data.
     * <p>This method supports Cross-Origin Resource Sharing (CORS) by handling preflight 
     * {@code OPTIONS} requests. It extracts the lookup key from the query parameters, 
     * queries the local node's state machine, and returns the result as a JSON formatted 
     * response. In case of failure or if the node is not the leader, it returns an 
     * appropriate error status.</p>
     *
     * @param exchange The {@link HttpExchange} representing the client's request and response channel.
     * @throws IOException If an I/O error occurs during request parsing or sending the response.
     */
    private void handleClientGetHttp(HttpExchange exchange) throws IOException {
        setCorsHeaders(exchange);
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        
        String query = exchange.getRequestURI().getQuery();
        String key = query != null && query.startsWith("key=") ? query.substring(4) : "";
        
        try {
            com.raft.node.Node<?> castedNode = (com.raft.node.Node<?>) localNode;
            String result = castedNode.get(key);
            String response = result != null ? result : "[]";
            sendJsonResponse(exchange, 200, response);
        } catch (Exception e) {
            sendJsonResponse(exchange, 500, "{\"error\": \"" + e.getMessage() + "\"}");
        }
    }

    /**
     * Handles incoming HTTP POST requests from clients to propose new commands to the Raft cluster.
     * <p>This method implements the entry point for state machine modifications. It supports 
     * CORS preflight requests and enforces the use of the POST method. If the local node is 
     * the leader, it attempts to propose the command to the log; otherwise, it attempts 
     * to redirect the client to the current leader using an HTTP 307 (Temporary Redirect) 
     * status code and the {@code Location} header.</p>
     *
     * @param exchange The {@link HttpExchange} containing the client's command in the request body.
     * @throws IOException If an I/O error occurs during body reading or response transmission.
     */
    private void handleClientProposeHttp(HttpExchange exchange) throws IOException {
        setCorsHeaders(exchange);
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }
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
                sendJsonResponse(exchange, 200, "{\"status\": \"Proposition accepted by the leader\"}");
            } else {
                String leaderId = castedNode.getCurrentLeaderID();
                if (leaderId != null) {
                    String leaderUrl = peerAddresses.get(leaderId);
                    if (leaderUrl != null) {
                        exchange.getResponseHeaders().set("Location", leaderUrl + "/clientPropose");
                        exchange.sendResponseHeaders(307, -1);
                        exchange.close();
                        return;
                    }
                }
                sendJsonResponse(exchange, 503, "{\"error\": \"Not-Leader Node or Unknown Node\"}");
            }
        } catch (Exception e) {
            sendJsonResponse(exchange, 500, "{\"error\": \"" + e.getMessage() + "\"}");
        }
    }

    /**
     * Generic helper method to process incoming HTTP POST requests for Raft RPCs.
     * <p>This method handles the boilerplate of an HTTP RPC call: it validates the request method,
     * deserializes the JSON request body into the specified class, executes the provided 
     * handler function against the local node logic, and serializes the resulting response 
     * object back into JSON for the HTTP response.</p>
     *
     * @param <T>      The type of the incoming request object.
     * @param <R>      The type of the resulting response object.
     * @param exchange The {@link HttpExchange} representing the current connection.
     * @param reqClass The class reference for deserializing the request body.
     * @param handler  A function that maps the request object to a response by invoking 
     * the appropriate node logic.
     * @throws IOException If an I/O error occurs during stream reading or writing.
     */
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

    /**
     * Configures Cross-Origin Resource Sharing (CORS) headers for an HTTP response.
     * <p>This method enables the Raft node's HTTP interface to be accessed by web-based 
     * clients hosted on different domains. It permits common HTTP methods used for 
     * querying and proposing state changes, and allows the {@code Content-Type} header, 
     * which is essential for JSON-based communication.</p>
     *
     * @param exchange The {@link HttpExchange} whose response headers will be updated 
     * to include the CORS security policies.
     */
    private void setCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
    }

    /**
     * Utility method to send a JSON-formatted HTTP response.
     * <p>This method sets the {@code Content-Type} header to {@code application/json}, 
     * writes the appropriate HTTP status code, and transmits the JSON string as the 
     * response body using UTF-8 encoding. It ensures the output stream is properly 
     * closed after the data is written.</p>
     *
     * @param exchange   The {@link HttpExchange} representing the current connection.
     * @param statusCode The HTTP status code to be sent (e.g., 200 for success, 500 for error).
     * @param json       The raw JSON string to be sent in the response body.
     * @throws IOException If an I/O error occurs while setting headers or writing to the body stream.
     */
    private void sendJsonResponse(HttpExchange exchange, int statusCode, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    } 
}