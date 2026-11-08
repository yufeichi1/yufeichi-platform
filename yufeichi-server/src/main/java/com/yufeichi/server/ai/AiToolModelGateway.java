package com.yufeichi.server.ai;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Mono;
import java.util.List;

/** One provider round only; the application owns execution, budgets and conversation lifetime. */
public interface AiToolModelGateway {
    record Round(AssistantMessage message, String finishReason, Integer inputTokens, Integer outputTokens) { }
    Mono<Round> round(List<Message> messages, List<ToolCallback> tools);
}
