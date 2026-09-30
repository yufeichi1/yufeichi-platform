package com.yufeichi.server.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

// Wire-compatible local provider fixture: no real models or paid network calls.
public final class AiWireFixture implements AutoCloseable {
    private final HttpServer server;
    private final ObjectMapper json = new ObjectMapper();
    private final java.util.concurrent.ExecutorService executor = Executors.newCachedThreadPool();
    public volatile String mode = "normal";
    public volatile boolean structured;
    public volatile boolean correctAuthorization;
    public final AtomicInteger calls = new AtomicInteger();
    public final AtomicInteger disconnected = new AtomicInteger();

    public AiWireFixture() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/v1/chat/completions", exchange -> {
                int attempt = calls.incrementAndGet();
                String scenario = mode;
                try (exchange) {
                    correctAuthorization = "Bearer fixture-not-a-real-key".equals(exchange.getRequestHeaders().getFirst("Authorization"));
                    var request = json.readTree(exchange.getRequestBody());
                    structured = request.path("response_format").path("type").asText().equals("json_object");
                    if (scenario.equals("auth") || scenario.equals("rate") || scenario.equals("transient") && attempt == 1) {
                        int status = scenario.equals("auth") ? 401 : scenario.equals("rate") ? 429 : 503;
                        byte[] privateBody = "PRIVATE-PROVIDER-SECRET".getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(status, privateBody.length);
                        exchange.getResponseBody().write(privateBody);
                        return;
                    }
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, 0);
                    String output = structured ? "{\"summary\":\"这是测试摘要。\"}" : "这是测试摘要。";
                    if (scenario.equals("bad-json")) output = "格式错误";
                    if (scenario.equals("too-long")) output = structured ? "{\"summary\":\"" + "长".repeat(501) + "\"}" : "长".repeat(501);
                    if (scenario.equals("timeout")) {
                        Thread.sleep(5000);
                    }
                    // Invalid-length fixture is delivered promptly: test output validation independently of timeout.
                    int step = scenario.equals("too-long") ? output.length() : 3;
                    for (int offset = 0; offset < output.length(); offset += step) {
                        var chunk = Map.of("id", "fixture-id", "object", "chat.completion.chunk", "created", 1,
                                "model", "fixture-model", "choices", List.of(Map.of("index", 0,
                                "delta", Map.of("role", "assistant", "content", output.substring(offset, Math.min(output.length(), offset + step))))));
                        exchange.getResponseBody().write(("data: " + json.writeValueAsString(chunk) + "\n\n").getBytes(StandardCharsets.UTF_8));
                        exchange.getResponseBody().flush();
                        Thread.sleep(scenario.equals("slow") ? 800 : 10);
                    }
                    if (!scenario.equals("unfinished")) {
                        var finalChunk = Map.of("id", "fixture-id", "object", "chat.completion.chunk", "created", 1,
                                "model", "fixture-model", "choices", List.of(Map.of("index", 0,
                                "delta", Map.of(), "finish_reason", scenario.equals("truncated") ? "length" : "stop")));
                        exchange.getResponseBody().write(("data: " + json.writeValueAsString(finalChunk) + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
                        exchange.getResponseBody().flush();
                    }
                } catch (java.io.IOException cancelled) { disconnected.incrementAndGet(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            });
            server.start();
        } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }
    public String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    public void reset(String scenario) { mode = scenario; calls.set(0); disconnected.set(0); }
    @Override public void close() { server.stop(0); executor.shutdownNow(); }
}
