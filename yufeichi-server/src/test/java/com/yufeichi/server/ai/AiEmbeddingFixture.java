package com.yufeichi.server.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Deterministic vectors over the real OpenAI-compatible HTTP protocol; no paid calls. */
public final class AiEmbeddingFixture implements AutoCloseable {
    private final HttpServer server;
    public final AtomicInteger calls = new AtomicInteger();
    public volatile boolean wrongDimensions;
    public volatile boolean contractValid;
    public volatile int failOnCall;
    public volatile Runnable afterInput = () -> { };
    public final List<String> inputs = new java.util.concurrent.CopyOnWriteArrayList<>();
    public AiEmbeddingFixture() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/compatible-mode/v1/embeddings", exchange -> {
                int call = calls.incrementAndGet();
                try (exchange) {
                    var json = new ObjectMapper(); var request = json.readTree(exchange.getRequestBody());
                    for (var input : request.path("input")) inputs.add(input.asText());
                    afterInput.run();
                    if (call == failOnCall) { exchange.sendResponseHeaders(503, -1); return; }
                    contractValid = "Bearer embedding-fixture-key".equals(exchange.getRequestHeaders().getFirst("Authorization"))
                            && "fixture-embedding".equals(request.path("model").asText())
                            && request.path("dimensions").asInt() == 1024 && "float".equals(request.path("encoding_format").asText());
                    var rows = new ArrayList<Map<String, Object>>();
                    for (int i = 0; i < request.path("input").size(); i++) {
                        String text = request.path("input").get(i).asText();
                        float[] vector = new float[wrongDimensions ? 1023 : 1024];
                        vector[text.contains("Java") ? 0 : 1] = 1;
                        rows.add(Map.of("object", "embedding", "index", i, "embedding", vector));
                    }
                    byte[] body = json.writeValueAsBytes(Map.of("object", "list", "model", "fixture-embedding", "data", rows,
                            "usage", Map.of("prompt_tokens", 10, "total_tokens", 10)));
                    exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                }
            });
            server.start();
        } catch (java.io.IOException failure) { throw new IllegalStateException("Could not start local embedding fixture"); }
    }
    public String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/compatible-mode/v1"; }
    @Override public void close() { server.stop(0); }
}
