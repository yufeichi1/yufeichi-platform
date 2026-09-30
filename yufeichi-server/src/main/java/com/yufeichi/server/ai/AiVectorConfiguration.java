package com.yufeichi.server.ai;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Configuration
@ConditionalOnProperty(name = {"app.ai.enabled", "app.ai.vector.enabled"}, havingValue = "true")
@Conditional(AiVectorConfiguration.Configured.class)
public class AiVectorConfiguration {
    @Bean(destroyMethod = "shutdownNow")
    ThreadPoolExecutor aiEmbeddingExecutor(AiProperties properties) {
        int size = properties.getMaxConcurrentRequests();
        return new ThreadPoolExecutor(size, size, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16), task -> {
            var thread = new Thread(task, "ai-embedding-http"); thread.setDaemon(true); return thread;
        });
    }
    @Bean
    AiEmbeddingModel aiEmbeddingModel(AiProperties properties, ThreadPoolExecutor aiEmbeddingExecutor) {
        var embedding = properties.getEmbedding();
        embedding.validate(); properties.getVector().validate();
        var http = java.net.http.HttpClient.newBuilder().executor(aiEmbeddingExecutor)
                .connectTimeout(Duration.ofSeconds(properties.getConnectTimeoutSeconds())).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(properties.getRequestTimeoutSeconds()));
        var api = OpenAiApi.builder().baseUrl(embedding.normalizedBaseUrl()).apiKey(embedding.getApiKey())
                .embeddingsPath(embedding.effectivePath()).restClientBuilder(RestClient.builder().requestFactory(factory)).build();
        var model = new OpenAiEmbeddingModel(api, MetadataMode.EMBED,
                OpenAiEmbeddingOptions.builder().model(embedding.getModel()).dimensions(embedding.getDimensions()).encodingFormat("float").build(),
                RetryTemplate.builder().maxAttempts(1).build(), ObservationRegistry.NOOP);
        return new AiEmbeddingModel(model, embedding.getDimensions(), properties.getMaxInputChars());
    }
    @Bean(destroyMethod = "close")
    AiVectorStore aiVectorStore(AiProperties properties, AiEmbeddingModel embedding) {
        // Owns its pool internally: no DataSource/JdbcTemplate/Flyway bean replaces MySQL.
        return new AiVectorStore(properties, embedding);
    }
    public static final class Configured implements Condition {
        @Override public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            for (String name : new String[]{"embedding.base-url", "embedding.api-key", "embedding.model",
                    "vector.url", "vector.username", "vector.password"})
                if (context.getEnvironment().getProperty("app.ai." + name, "").isBlank()) return false;
            return true;
        }
    }
}
