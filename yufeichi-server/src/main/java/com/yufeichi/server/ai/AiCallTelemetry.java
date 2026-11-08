package com.yufeichi.server.ai;

import org.slf4j.LoggerFactory;
import java.math.BigDecimal;
import java.util.*;

/** Bounded, content-free diagnostics. No prompt, answer, key, user identity or provider URL. */
public final class AiCallTelemetry {
    public static final String CONTEXT_KEY = AiCallTelemetry.class.getName();
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();
    private static final Map<String, Snapshot> RECENT = new LinkedHashMap<>();
    private AiCallTelemetry() { }
    public record Call(String kind, String model, Integer inputTokens, Integer outputTokens,
                       String outcome, long durationMs, BigDecimal reservedCny, BigDecimal estimatedCny) { }
    public record Candidate(String source, String contentHash, String chunkId, Double score) { }
    public record Snapshot(List<Call> calls, Long retrievalMs, List<Candidate> candidates) { }
    public static String current() { return Optional.ofNullable(CURRENT.get()).orElseGet(() -> UUID.randomUUID().toString()); }
    public static AutoCloseable scope(String id) {
        String previous = CURRENT.get(); CURRENT.set(id);
        return () -> { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); };
    }
    public static synchronized Snapshot snapshot(String id) {
        return RECENT.getOrDefault(id, new Snapshot(List.of(), null, List.of()));
    }
    private static void put(String id, Snapshot value) {
        RECENT.put(id, value);
        while (RECENT.size() > 128) RECENT.remove(RECENT.keySet().iterator().next());
    }
    public static synchronized void retrieval(String id, long ms, List<Candidate> candidates) {
        put(id, new Snapshot(snapshot(id).calls(), ms, List.copyOf(candidates)));
    }
    public static synchronized void record(String id, Call call) {
        var old = snapshot(id); var calls = new ArrayList<>(old.calls());
        if (calls.size() < 16) calls.add(call);
        put(id, new Snapshot(List.copyOf(calls), old.retrievalMs(), old.candidates()));
        LoggerFactory.getLogger(AiCallTelemetry.class).info(
                "ai_call requestId={} kind={} model={} outcome={} durationMs={} inputTokens={} outputTokens={} reservedCny={} estimatedCny={}",
                id, call.kind(), call.model(), call.outcome(), call.durationMs(), call.inputTokens(), call.outputTokens(), call.reservedCny(), call.estimatedCny());
    }
}
