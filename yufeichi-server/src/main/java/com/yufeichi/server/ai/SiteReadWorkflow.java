package com.yufeichi.server.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yufeichi.server.common.error.BusinessException;
import com.yufeichi.server.common.error.ErrorCode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Deterministic search -> first detail per source type. No model, vector search, loop, memory or dynamic dispatch from user input. */
@Service
public class SiteReadWorkflow {
    private final SiteReadToolCatalog catalog;
    private final SiteReadTools tools;
    private final AiProperties properties;
    private final AiRequestGuard guard;
    private final ObjectMapper json;
    public SiteReadWorkflow(SiteReadToolCatalog catalog, SiteReadTools tools, AiProperties properties, AiRequestGuard guard, ObjectMapper json) {
        this.catalog = catalog; this.tools = tools; this.properties = properties; this.guard = guard; this.json = json;
    }
    public record Step(String tool, String outcome, int resultCount) { }
    public record Response(String requestId, String mode, String message, List<SiteReadTools.Item> matches,
                           List<SiteReadTools.Detail> details, List<Step> steps) { }
    public boolean available() { return properties.isEnabled() && properties.isToolsEnabled(); }
    public Response query(ToolQueryRequest request, long userId) {
        if (request == null || request.scope() == null || request.limit() == null || request.limit() < 1 || request.limit() > 5)
            throw new BusinessException(ErrorCode.PARAM_ERROR);
        String keyword = SiteReadTools.keyword(request.keyword());
        if (!available()) throw new BusinessException(ErrorCode.AI_UNAVAILABLE);
        String requestId = UUID.randomUUID().toString(); long started = System.nanoTime();
        try (var lease = guard.acquireToolWorkflow(userId)) {
            var matches = new ArrayList<SiteReadTools.Item>();
            var details = new ArrayList<SiteReadTools.Detail>();
            var steps = new ArrayList<Step>();
            for (String type : List.of("article", "project")) {
                if (request.scope() == ToolQueryRequest.Scope.ARTICLE && type.equals("project")
                        || request.scope() == ToolQueryRequest.Scope.PROJECT && type.equals("article")) continue;
                String searchName = type.equals("article") ? "searchArticles" : "searchProjects";
                var found = call(searchName, Map.of("keyword", keyword, "limit", request.limit()), SiteReadTools.SearchResult.class);
                matches.addAll(found.items()); steps.add(new Step(searchName, found.status(), found.items().size()));
                if (found.items().isEmpty()) continue;
                String detailName = type.equals("article") ? "getArticleDetail" : "getProjectDetail";
                var result = call(detailName, Map.of("id", found.items().getFirst().sourceId()), SiteReadTools.DetailResult.class);
                if (result.detail() != null) details.add(result.detail());
                steps.add(new Step(detailName, result.status(), result.detail() == null ? 0 : 1));
            }
            // Do not release an earlier preview or excerpt when it became private/stale mid-workflow.
            boolean stale = matches.stream().anyMatch(m -> !tools.current(m)) || details.stream().anyMatch(d -> !tools.current(d));
            if (stale) { matches.clear(); details.clear(); }
            String message = stale ? "查询期间内容已变化，请重新查询。" : matches.isEmpty()
                    ? "未找到标题或名称匹配的公开内容，请换一个关键词。" : "已查询公开内容，并展示每类首项的原文摘录；可点击来源查看完整内容。";
            org.slf4j.LoggerFactory.getLogger(getClass()).info("ai requestId={} operation=tool-workflow steps={} outcome={} durationMs={}",
                    requestId, steps.size(), stale ? "SOURCE_CHANGED" : matches.isEmpty() ? "EMPTY" : "FOUND", (System.nanoTime() - started) / 1_000_000);
            return new Response(requestId, "fixed-query", message, List.copyOf(matches), List.copyOf(details), List.copyOf(steps));
        }
    }
    private <T> T call(String name, Map<String, Object> input, Class<T> resultType) {
        try { return json.readValue(catalog.named(name).call(json.writeValueAsString(input)), resultType); }
        catch (java.io.IOException invalid) { throw new BusinessException(ErrorCode.AI_INVALID_RESPONSE); }
    }
}
