package com.yufeichi.server.ai;

import jakarta.validation.constraints.*;

public record ToolQueryRequest(@NotBlank @Size(max=80) String keyword, @NotNull Scope scope,
                               @NotNull @Min(1) @Max(5) Integer limit) {
    @com.fasterxml.jackson.annotation.JsonCreator(mode = com.fasterxml.jackson.annotation.JsonCreator.Mode.DELEGATING)
    public static ToolQueryRequest from(com.fasterxml.jackson.databind.JsonNode value) {
        if (value == null || !value.isObject() || value.size() != 3 || !value.path("keyword").isTextual()
                || !value.path("scope").isTextual() || !value.path("limit").isIntegralNumber() || !value.path("limit").canConvertToInt())
            throw new IllegalArgumentException("Invalid fixed query fields");
        return new ToolQueryRequest(value.path("keyword").asText(), Scope.valueOf(value.path("scope").asText()), value.path("limit").asInt());
    }
    public enum Scope { ALL, ARTICLE, PROJECT }
}
