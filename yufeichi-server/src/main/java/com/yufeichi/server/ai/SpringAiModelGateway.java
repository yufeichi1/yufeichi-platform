package com.yufeichi.server.ai;

import com.yufeichi.server.common.error.ErrorCode;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import reactor.core.publisher.Mono;

final class SpringAiModelGateway implements AiModelGateway {
    private final OpenAiChatModel model;
    private final AiProperties properties;

    SpringAiModelGateway(OpenAiChatModel model, AiProperties properties) {
        this.model = model;
        this.properties = properties;
    }

    @Override
    public Flux<String> generate(String system, String content, boolean structured) {
        return Flux.defer(() -> {
            var completed = new AtomicBoolean();
            var options = OpenAiChatOptions.builder().model(properties.getChatModel())
                    .reasoningEffort(properties.effectiveReasoningEffort())
                    .maxTokens(properties.getMaxOutputTokens()).internalToolExecutionEnabled(false);
            if (structured && properties.isJsonMode()) {
                options.responseFormat(new ResponseFormat(ResponseFormat.Type.JSON_OBJECT, null));
            }
            return model.stream(new Prompt(List.of(new SystemMessage(system), new UserMessage(content)), options.build()))
                    .doOnNext(response -> {
                        if (response.getResult() != null && "STOP".equalsIgnoreCase(response.getResult().getMetadata().getFinishReason()))
                            completed.set(true);
                    })
                    .map(response -> {
                        if (response.getResult() == null || response.getResult().getOutput() == null) return "";
                        String text = response.getResult().getOutput().getText();
                        return text == null ? "" : text;
                    }).filter(text -> !text.isEmpty())
                    .concatWith(Mono.defer(() -> completed.get() ? Mono.empty()
                            : Mono.error(new AiProviderException(ErrorCode.AI_INVALID_RESPONSE, false))));
        }).onErrorMap(error -> {
            if (error instanceof AiProviderException) return error;
            int status = error instanceof WebClientResponseException http ? http.getStatusCode().value() : 0;
            boolean timeout = hasType(error, "Timeout");
            // 429 may indicate exhausted credit; don't retry it or an auth/parameter failure.
            boolean transientFailure = status == 502 || status == 503 || status == 504
                    || (status == 0 && hasType(error, "ConnectException"));
            return new AiProviderException(timeout ? ErrorCode.AI_TIMEOUT : ErrorCode.AI_UNAVAILABLE, transientFailure);
        });
    }

    private static boolean hasType(Throwable error, String name) {
        for (int i = 0; error != null && i < 8; i++, error = error.getCause()) {
            if (error.getClass().getSimpleName().contains(name)) return true;
        }
        return false;
    }
}
