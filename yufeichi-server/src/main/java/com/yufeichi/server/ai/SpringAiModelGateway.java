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

final class SpringAiModelGateway implements AiModelGateway, AiToolModelGateway {
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

    @Override public Mono<Round> round(List<org.springframework.ai.chat.messages.Message> messages,
                                      List<org.springframework.ai.tool.ToolCallback> tools) {
        return Mono.defer(() -> {
            var options = OpenAiChatOptions.builder().model(properties.getChatModel())
                    .reasoningEffort(properties.effectiveToolReasoningEffort()).maxTokens(properties.getMaxOutputTokens())
                    .internalToolExecutionEnabled(false).toolCallbacks(tools).parallelToolCalls(false);
            // Only the final, tool-free round requests JSON mode. No implicit model/tool loop.
            if (tools.isEmpty() && properties.isJsonMode()) options.responseFormat(new ResponseFormat(ResponseFormat.Type.JSON_OBJECT, null));
            return model.stream(new Prompt(messages, options.build())).reduce(new ToolRoundBuffer(), ToolRoundBuffer::add)
                    .map(ToolRoundBuffer::finish);
        }).onErrorMap(error -> {
            if (error instanceof com.yufeichi.server.common.error.BusinessException) return error;
            return new AiProviderException(hasType(error, "Timeout") ? ErrorCode.AI_TIMEOUT : ErrorCode.AI_UNAVAILABLE, false);
        });
    }

    private static final class ToolRoundBuffer {
        final StringBuilder text = new StringBuilder();
        final java.util.List<org.springframework.ai.chat.messages.AssistantMessage.ToolCall> calls = new java.util.ArrayList<>();
        String reason = "";
        int input, output;
        ToolRoundBuffer add(org.springframework.ai.chat.model.ChatResponse response) {
            if (response.getResults().size() > 1) throw new AiProviderException(ErrorCode.AI_INVALID_RESPONSE, false);
            var result = response.getResult();
            if (result != null) {
                var message = result.getOutput();
                if (message.getText() != null) text.append(message.getText());
                calls.addAll(message.getToolCalls()); // OpenAiApi merges fragmented tool arguments before emitting.
                if (text.length() > 6000 || calls.size() > 4 || calls.stream().anyMatch(c -> c.arguments() == null || c.arguments().length() > 1024))
                    throw new AiProviderException(ErrorCode.AI_INVALID_RESPONSE, false);
                String finish = result.getMetadata().getFinishReason();
                if (finish != null && !finish.isBlank()) reason = finish;
            }
            var usage = response.getMetadata().getUsage();
            if (usage != null) {
                input = usage.getPromptTokens() == null ? input : usage.getPromptTokens();
                output = usage.getCompletionTokens() == null ? output : usage.getCompletionTokens();
            }
            return this;
        }
        Round finish() {
            if (!(calls.isEmpty() ? "STOP" : "TOOL_CALLS").equalsIgnoreCase(reason))
                throw new AiProviderException(ErrorCode.AI_INVALID_RESPONSE, false);
            return new Round(org.springframework.ai.chat.messages.AssistantMessage.builder().content(text.toString())
                    .toolCalls(List.copyOf(calls)).build(), reason, input, output);
        }
    }
}
