package com.yufeichi.server.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yufeichi.server.common.error.BusinessException;
import com.yufeichi.server.common.error.ErrorCode;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;

/** The same four callbacks are used by the fixed workflow and can later be explicitly passed to a model. */
@Component
public class SiteReadToolCatalog implements ToolCallbackProvider {
    private final ToolCallback[] callbacks;
    private final ObjectMapper json;
    public SiteReadToolCatalog(SiteReadTools tools, ObjectMapper json) {
        this.json = json;
        callbacks = Arrays.stream(MethodToolCallbackProvider.builder().toolObjects(tools).build().getToolCallbacks())
                .map(this::bounded).toArray(ToolCallback[]::new);
        if (callbacks.length != 4) throw new IllegalStateException("Exactly four public readers required");
    }
    @Override public ToolCallback[] getToolCallbacks() { return callbacks.clone(); }
    public ToolCallback named(String name) {
        return Arrays.stream(callbacks).filter(c -> c.getToolDefinition().name().equals(name)).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_ERROR, "不支持的只读查询"));
    }
    public void validateArguments(String name, String input) {
        named(name);
        validate(input, name.startsWith("search"));
    }
    public String callForUser(String name, String input, long userId) {
        if (userId < 1) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        return named(name).call(input, new org.springframework.ai.chat.model.ToolContext(java.util.Map.of("userId", userId)));
    }
    private ToolCallback bounded(ToolCallback delegate) {
        var original = delegate.getToolDefinition();
        boolean search = original.name().startsWith("search");
        ToolDefinition definition;
        try {
            var schema = (ObjectNode) json.readTree(original.inputSchema());
            schema.put("additionalProperties", false);
            var fields = (ObjectNode) schema.get("properties");
            if (search) {
                ((ObjectNode) fields.get("keyword")).put("minLength", 1).put("maxLength", SiteReadTools.MAX_KEYWORD);
                ((ObjectNode) fields.get("limit")).put("minimum", 1).put("maximum", SiteReadTools.MAX_RESULTS);
            } else ((ObjectNode) fields.get("id")).put("minimum", 1).put("maximum", SiteReadTools.MAX_ID);
            definition = ToolDefinition.builder().name(original.name()).description(original.description())
                    .inputSchema(json.writeValueAsString(schema)).build();
        } catch (java.io.IOException impossible) { throw new IllegalStateException("Invalid tool schema"); }
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() { return definition; }
            @Override public String call(String input) {
                validate(input, search);
                return delegate.call(input);
            }
            @Override public String call(String input, org.springframework.ai.chat.model.ToolContext context) {
                if (context == null || !(context.getContext().get("userId") instanceof Long id) || id < 1)
                    throw new BusinessException(ErrorCode.UNAUTHORIZED);
                return call(input); // Public-only readers do not need account details or ThreadLocal identity.
            }
        };
    }
    private void validate(String input, boolean search) {
        try {
            if (input == null || input.length() > 1024) throw new IllegalArgumentException();
            var value = json.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(input);
            var expected = search ? Set.of("keyword", "limit") : Set.of("id");
            if (value == null || !value.isObject() || value.size() != expected.size()) throw new IllegalArgumentException();
            for (var fields = value.fieldNames(); fields.hasNext();) if (!expected.contains(fields.next())) throw new IllegalArgumentException();
            if (search) {
                if (!value.path("keyword").isTextual()) throw new IllegalArgumentException();
                SiteReadTools.keyword(value.path("keyword").asText());
                if (!value.path("limit").isIntegralNumber() || !value.path("limit").canConvertToInt()
                        || value.path("limit").asInt() < 1 || value.path("limit").asInt() > SiteReadTools.MAX_RESULTS) throw new IllegalArgumentException();
            } else if (!value.path("id").isIntegralNumber() || !value.path("id").canConvertToLong()
                    || value.path("id").asLong() < 1 || value.path("id").asLong() > SiteReadTools.MAX_ID) throw new IllegalArgumentException();
        } catch (java.io.IOException | IllegalArgumentException invalid) { throw new BusinessException(ErrorCode.PARAM_ERROR); }
    }
}
