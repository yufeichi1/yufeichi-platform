package com.yufeichi.server.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;

// Deliberately outside Surefire's *Test/*Tests discovery. Explicit -Dtest=LiveAiSmoke only.
class LiveAiSmoke {
    @org.junit.jupiter.api.BeforeAll static void suppressPrivateProviderDiagnostics() {
        for (String name : new String[]{"org.springframework.ai", "org.springframework.web.reactive.function.client"})
            ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(name)).setLevel(ch.qos.logback.classic.Level.OFF);
    }
    private <T> T safe(Supplier<T> operation) {
        try { return operation.get(); }
        catch (RuntimeException failure) {
            // A provider may echo credentials in an exception response. Never print its cause.
            throw new AssertionError("Live AI provider call failed; private details suppressed");
        }
    }
    private void gate() { assertThat(System.getenv("AI_SMOKE_ENABLED")).as("Explicit paid API smoke opt-in").isEqualTo("true"); }
    private String required(String name) {
        String value = System.getenv(name);
        assertThat(value).as("Missing configuration: " + name).isNotBlank();
        return value;
    }
    private OpenAiApi api(boolean embedding) {
        String prefix = embedding ? "AI_EMBEDDING_" : "AI_";
        String base = required(embedding ? prefix + "BASE_URL" : "AI_PROVIDER_BASE_URL");
        String key = required(prefix + "API_KEY");
        var http = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(30));
        base = base.replaceAll("/+$", "");
        String prefixPath = base.endsWith("/v1") ? "" : "/v1";
        return OpenAiApi.builder().baseUrl(base).apiKey(key)
                .completionsPath(System.getenv().getOrDefault("AI_COMPLETIONS_PATH", prefixPath + "/chat/completions"))
                .embeddingsPath(System.getenv().getOrDefault("AI_EMBEDDING_PATH", prefixPath + "/embeddings"))
                .restClientBuilder(RestClient.builder().requestFactory(factory))
                .webClientBuilder(WebClient.builder().clientConnector(new JdkClientHttpConnector(http))).build();
    }
    private OpenAiChatModel chat(boolean structured) {
        var options = OpenAiChatOptions.builder().model(required("AI_CHAT_MODEL")).maxTokens(128).internalToolExecutionEnabled(false);
        var properties = new AiProperties();
        properties.setBaseUrl(required("AI_PROVIDER_BASE_URL"));
        properties.setReasoningEffort(System.getenv().getOrDefault("AI_REASONING_EFFORT", ""));
        options.reasoningEffort(properties.effectiveReasoningEffort());
        if (structured && !"false".equals(System.getenv("AI_JSON_MODE")))
            options.responseFormat(new ResponseFormat(ResponseFormat.Type.JSON_OBJECT, null));
        return OpenAiChatModel.builder().openAiApi(api(false)).defaultOptions(options.build())
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).build()).observationRegistry(ObservationRegistry.NOOP).build();
    }
    @Test void ordinaryChat() {
        gate();
        var response = safe(() -> chat(false).call(new Prompt("用一句中文解释文章摘要的用途。")));
        assertThat(response.getResult()).isNotNull();
        assertThat(response.getResult().getOutput().getText() != null
                && !response.getResult().getOutput().getText().isBlank()).isTrue();
    }
    @Test void structuredChat() throws Exception {
        gate();
        var response = safe(() -> chat(true).call(new Prompt("只输出JSON对象，summary字段为字符串：概括文章摘要能帮助读者了解正文。")));
        try {
            var tree = new ObjectMapper().readTree(response.getResult().getOutput().getText());
            assertThat(tree.path("summary").isTextual()).isTrue();
        } catch (Exception invalid) { throw new AssertionError("Live AI returned invalid JSON; private output suppressed"); }
    }
    @Test void streamingChat() {
        gate();
        var pieces = safe(() -> chat(false).stream(new Prompt("用两句中文解释Java后端接口的用途。"))
                .timeout(Duration.ofSeconds(30)).collectList().block(Duration.ofSeconds(35)));
        assertThat(pieces).isNotEmpty();
        assertThat(pieces.stream().anyMatch(piece -> piece.getResult() != null
                && piece.getResult().getOutput().getText() != null && !piece.getResult().getOutput().getText().isBlank())).isTrue();
    }
    @Test void embeddingDimensions() {
        gate();
        var options = OpenAiEmbeddingOptions.builder().model(required("AI_EMBEDDING_MODEL"))
                .dimensions(Integer.parseInt(required("AI_EMBEDDING_DIMENSIONS"))).encodingFormat("float").build();
        var model = new OpenAiEmbeddingModel(api(true), MetadataMode.EMBED, options,
                RetryTemplate.builder().maxAttempts(1).build(), ObservationRegistry.NOOP);
        var vector = safe(() -> model.embed("Yufeichi网站公开文章知识库。"));
        assertThat(vector.length).isEqualTo(Integer.parseInt(required("AI_EMBEDDING_DIMENSIONS")));
        for (float value : vector) assertThat(Float.isFinite(value)).isTrue();
    }
}
