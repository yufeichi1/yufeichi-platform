package com.yufeichi.server.ai;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AgentRequest(@NotBlank @Size(max=500) String question) {
    @JsonCreator(mode=JsonCreator.Mode.DELEGATING)
    public static AgentRequest from(JsonNode value) {
        if (value == null || !value.isObject() || value.size() != 1 || !value.path("question").isTextual())
            throw new IllegalArgumentException("Invalid agent request");
        return new AgentRequest(value.path("question").asText());
    }
}
