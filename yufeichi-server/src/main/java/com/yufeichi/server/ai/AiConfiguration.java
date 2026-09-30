package com.yufeichi.server.ai;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.net.http.HttpClient.Builder;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class AiConfiguration {
    @Bean(destroyMethod = "dispose")
    Scheduler aiScheduler(AiProperties properties) {
        properties.validate();
        return Schedulers.newBoundedElastic(properties.getMaxConcurrentRequests(), 8, "ai-model");
    }

    @Bean(destroyMethod = "dispose")
    Scheduler aiTimer() { return Schedulers.newSingle("ai-timer"); }

    @Bean(destroyMethod = "shutdownNow")
    @ConditionalOnProperty(name = "app.ai.enabled", havingValue = "true")
    @Conditional(ProviderConfigured.class)
    ThreadPoolExecutor aiHttpExecutor(AiProperties properties) {
        int size = properties.getMaxConcurrentRequests();
        return new ThreadPoolExecutor(size, size, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32), task -> {
            Thread thread = new Thread(task, "ai-http");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Bean
    @ConditionalOnProperty(name = "app.ai.enabled", havingValue = "true")
    @Conditional(ProviderConfigured.class)
    @ConditionalOnMissingBean(AiModelGateway.class)
    AiModelGateway aiModelGateway(AiProperties properties, ThreadPoolExecutor aiHttpExecutor) {
        properties.validate();
        Builder jdk = java.net.http.HttpClient.newBuilder()
                .executor(aiHttpExecutor).connectTimeout(Duration.ofSeconds(properties.getConnectTimeoutSeconds()));
        var http = jdk.build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(properties.getRequestTimeoutSeconds()));
        var api = OpenAiApi.builder().baseUrl(properties.getBaseUrl()).apiKey(properties.getApiKey())
                .completionsPath(properties.getCompletionsPath())
                .restClientBuilder(RestClient.builder().requestFactory(factory))
                .webClientBuilder(WebClient.builder().clientConnector(new JdkClientHttpConnector(http))
                        .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(65536)))
                .build();
        var model = OpenAiChatModel.builder().openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder().model(properties.getChatModel())
                        .reasoningEffort(properties.effectiveReasoningEffort())
                        .maxTokens(properties.getMaxOutputTokens()).internalToolExecutionEnabled(false).build())
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).build())
                .observationRegistry(ObservationRegistry.NOOP).build();
        return new SpringAiModelGateway(model, properties);
    }

    public static final class ProviderConfigured implements Condition {
        @Override public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            for (String name : new String[]{"base-url", "api-key", "chat-model"}) {
                if (context.getEnvironment().getProperty("app.ai." + name, "").isBlank()) return false;
            }
            return true;
        }
    }
}
