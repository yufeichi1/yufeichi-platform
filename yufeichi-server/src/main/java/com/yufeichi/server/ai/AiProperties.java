package com.yufeichi.server.ai;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;

// No generated toString: credentials must never appear in configuration diagnostics.
@Getter
@Setter
@ConfigurationProperties("app.ai")
public class AiProperties {
    private boolean enabled;
    private String baseUrl = "";
    private String completionsPath = "/v1/chat/completions";
    private String apiKey = "";
    private String chatModel = "";
    private boolean jsonMode = true;
    private String reasoningEffort = "";
    private int requestTimeoutSeconds = 60;
    private int indexTimeoutSeconds = 300;
    private int connectTimeoutSeconds = 5;
    private int heartbeatSeconds = 10;
    private int maxOutputTokens = 800;
    private int maxInputChars = 12000;
    private int maxConcurrentRequests = 2;
    private int userMinuteLimit = 3;
    private int userDailyLimit = 10;
    private int dailyRequestLimit = 100;
    private Embedding embedding = new Embedding();
    private Vector vector = new Vector();

    @Getter @Setter
    public static class Embedding {
        private String baseUrl = "";
        private String apiKey = "";
        private String model = "";
        private String path = "";
        private int dimensions = 1024;
        public boolean configured() { return !baseUrl.isBlank() && !apiKey.isBlank() && !model.isBlank(); }
        public String normalizedBaseUrl() { return baseUrl.replaceAll("/+$", ""); }
        public String effectivePath() { return path.isBlank()
                ? (normalizedBaseUrl().endsWith("/v1") ? "/embeddings" : "/v1/embeddings") : path; }
        public void validate() {
            if (dimensions < 1 || dimensions > 2000) throw new IllegalArgumentException("Invalid embedding dimensions");
            if (configured()) validateProvider(baseUrl, effectivePath());
        }
    }

    @Getter @Setter
    public static class Vector {
        private boolean enabled;
        private String url = "";
        private String username = "";
        private String password = "";
        public boolean configured() { return !url.isBlank() && !username.isBlank() && !password.isBlank(); }
        public void validate() {
            if (!enabled || !configured()) return;
            try {
                if (!url.startsWith("jdbc:postgresql://")) throw new IllegalArgumentException();
                URI uri = URI.create(url.substring(5));
                if (uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
                        || uri.getPath() == null || uri.getPath().length() < 2
                        || url.toLowerCase(java.util.Locale.ROOT).matches(".*[?&](password|user)=.*")) throw new IllegalArgumentException();
            } catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Invalid independent PostgreSQL configuration"); }
        }
    }

    private static void validateProvider(String base, String path) {
        try {
            URI uri = URI.create(base);
            boolean local = java.util.Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost() == null ? "" : uri.getHost());
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || !("https".equals(uri.getScheme()) || local && "http".equals(uri.getScheme()))
                    || !path.startsWith("/") || path.contains("?") || path.contains("#")) throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Invalid embedding provider configuration"); }
    }

    // Official DeepSeek defaults to thinking. Short summaries use non-thinking mode
    // unless the operator explicitly chooses another effort; other providers stay unchanged.
    public String effectiveReasoningEffort() {
        if (!reasoningEffort.isBlank()) return reasoningEffort;
        try {
            return "api.deepseek.com".equalsIgnoreCase(URI.create(baseUrl).getHost()) ? "none" : null;
        } catch (IllegalArgumentException invalid) { return null; }
    }

    public void validate() {
        if (!reasoningEffort.isBlank() && !java.util.Set.of("none", "minimal", "low", "medium", "high", "xhigh", "max").contains(reasoningEffort))
            throw new IllegalArgumentException("Invalid AI reasoning effort");
        if (indexTimeoutSeconds < 1 || indexTimeoutSeconds > 300 || requestTimeoutSeconds < 1 || requestTimeoutSeconds > 120 || connectTimeoutSeconds < 1
                || connectTimeoutSeconds > requestTimeoutSeconds || heartbeatSeconds < 1
                || heartbeatSeconds > 30 || maxOutputTokens < 1 || maxOutputTokens > 2000
                || maxInputChars < 1 || maxInputChars > 20000 || maxConcurrentRequests < 1
                || maxConcurrentRequests > 8 || userMinuteLimit < 1 || userDailyLimit < 1
                || dailyRequestLimit < 1) {
            throw new IllegalArgumentException("Invalid AI limits");
        }
        if (!enabled || baseUrl.isBlank() || apiKey.isBlank() || chatModel.isBlank()) return;
        try {
            URI uri = URI.create(baseUrl);
            boolean loopback = "localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost())
                    || "[::1]".equals(uri.getHost());
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null || !("https".equals(uri.getScheme())
                    || (loopback && "http".equals(uri.getScheme()))) || apiKey.isBlank()
                    || chatModel.isBlank() || !completionsPath.startsWith("/")
                    || completionsPath.contains("?") || completionsPath.contains("#")) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("AI enabled requires a safe provider URL, key, model and path");
        }
    }
}
