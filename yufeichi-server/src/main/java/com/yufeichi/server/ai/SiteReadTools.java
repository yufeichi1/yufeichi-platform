package com.yufeichi.server.ai;

import com.yufeichi.server.common.error.BusinessException;
import com.yufeichi.server.common.error.ErrorCode;
import com.yufeichi.server.dto.ArticleQuery;
import com.yufeichi.server.dto.ProjectQuery;
import com.yufeichi.server.service.ArticleService;
import com.yufeichi.server.service.ProjectService;
import com.yufeichi.server.vo.ArticleVO;
import com.yufeichi.server.vo.ProjectVO;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/** Public business readers only. No URLs, SQL, status flags or write services are accepted as arguments. */
@Component
public class SiteReadTools {
    public static final int MAX_RESULTS = 5, MAX_KEYWORD = 80, MAX_TEXT = 2000;
    public static final long MAX_ID = 9_007_199_254_740_991L;
    private final ArticleService articles;
    private final ProjectService projects;

    public SiteReadTools(ArticleService articles, ProjectService projects) {
        this.articles = articles; this.projects = projects;
    }
    public record Item(String sourceType, long sourceId, String title, String excerpt, String url, String snapshotHash) { }
    public record Detail(String sourceType, long sourceId, String title, String text, String url, String contentHash, boolean truncated) { }
    public record SearchResult(String status, List<Item> items) { }
    public record DetailResult(String status, Detail detail) { }

    @Tool(name = "searchArticles", description = "Read-only literal keyword search in published article titles. Never reads drafts or deleted articles. Returns at most 5 bounded previews; not full-text or semantic search.")
    public SearchResult searchArticles(@ToolParam(description = "1-80 characters; no %, _, backslash or control characters") String keyword,
                                      @ToolParam(description = "Result limit: integer 1-5") Integer limit) {
        var query = new ArticleQuery(); query.setKeyword(keyword(keyword)); query.setPageSize(limit(limit));
        var items = articles.list(query, true).getRecords().stream()
                .map(a -> item("article", a.id(), a.title(), a.summary())).toList();
        return new SearchResult(items.isEmpty() ? "NOT_FOUND" : "FOUND", items);
    }
    @Tool(name = "searchProjects", description = "Read-only literal keyword search in public project names. Never reads hidden or deleted projects. Returns at most 5 bounded previews.")
    public SearchResult searchProjects(@ToolParam(description = "1-80 characters; no %, _, backslash or control characters") String keyword,
                                      @ToolParam(description = "Result limit: integer 1-5") Integer limit) {
        var query = new ProjectQuery(); query.setKeyword(keyword(keyword)); query.setPageSize(limit(limit));
        var items = projects.list(query, true).getRecords().stream()
                .map(p -> item("project", p.id(), p.name(), p.description())).toList();
        return new SearchResult(items.isEmpty() ? "NOT_FOUND" : "FOUND", items);
    }
    @Tool(name = "getArticleDetail", description = "Read-only published article by positive ID. Returns at most 2000 text characters with a canonical local source link and content hash. Hidden/deleted/nonexistent all return NOT_FOUND.")
    public DetailResult getArticleDetail(@ToolParam(description = "Positive integer article ID, at most 9007199254740991") Long id) {
        validId(id);
        try { return new DetailResult("FOUND", detail(articles.detail(id, true))); }
        catch (BusinessException missing) { return absent(missing, ErrorCode.ARTICLE_NOT_FOUND); }
    }
    @Tool(name = "getProjectDetail", description = "Read-only public project by positive ID. Returns at most 2000 text characters with a canonical local source link and content hash. Hidden/deleted/nonexistent all return NOT_FOUND.")
    public DetailResult getProjectDetail(@ToolParam(description = "Positive integer project ID, at most 9007199254740991") Long id) {
        validId(id);
        try { return new DetailResult("FOUND", detail(projects.detail(id, true))); }
        catch (BusinessException missing) { return absent(missing, ErrorCode.PROJECT_NOT_FOUND); }
    }
    // Completion fence: read current public services again, without turning this into a fifth model tool.
    boolean current(Item expected) {
        try {
            Item actual;
            if (expected.sourceType().equals("article")) {
                var a = articles.detail(expected.sourceId(), true); actual = item("article", a.id(), a.title(), a.summary());
            } else {
                var p = projects.detail(expected.sourceId(), true); actual = item("project", p.id(), p.name(), p.description());
            }
            return expected.equals(actual);
        } catch (BusinessException error) {
            if (error.getCode() == ErrorCode.ARTICLE_NOT_FOUND.getCode() || error.getCode() == ErrorCode.PROJECT_NOT_FOUND.getCode()) return false;
            throw error;
        }
    }
    boolean current(Detail expected) {
        try {
            var actual = expected.sourceType().equals("article") ? detail(articles.detail(expected.sourceId(), true)) : detail(projects.detail(expected.sourceId(), true));
            return expected.equals(actual);
        } catch (BusinessException error) {
            if (error.getCode() == ErrorCode.ARTICLE_NOT_FOUND.getCode() || error.getCode() == ErrorCode.PROJECT_NOT_FOUND.getCode()) return false;
            throw error;
        }
    }
    public static String keyword(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_KEYWORD
                || value.codePoints().anyMatch(c -> Character.isISOControl(c) || c == '%' || c == '_' || c == '\\'))
            throw new BusinessException(ErrorCode.PARAM_ERROR, "关键词需为1—80字符，不支持通配符或控制字符");
        return value.strip();
    }
    private static int limit(Integer limit) {
        if (limit == null || limit < 1 || limit > MAX_RESULTS) throw new BusinessException(ErrorCode.PARAM_ERROR);
        return limit;
    }
    private static void validId(Long id) {
        if (id == null || id < 1 || id > MAX_ID) throw new BusinessException(ErrorCode.PARAM_ERROR);
    }
    private static DetailResult absent(BusinessException error, ErrorCode expected) {
        if (error.getCode() != expected.getCode()) throw error;
        return new DetailResult("NOT_FOUND", null);
    }
    private static Item item(String type, long id, String title, String preview) {
        String safeTitle = clip(title, 200), excerpt = clip(preview, 200);
        return new Item(type, id, safeTitle, excerpt, url(type, id), AiKnowledgeSources.hash(type + ":" + id + ":" + safeTitle + ":" + excerpt));
    }
    private static Detail detail(ArticleVO a) {
        return detail("article", a.id(), a.title(), clean(a.summary()) + "\n\n" + clean(a.content()));
    }
    private static Detail detail(ProjectVO p) {
        return detail("project", p.id(), p.name(), clean(p.description()) + "\n\n" + clean(p.techStack()));
    }
    private static Detail detail(String type, long id, String title, String body) {
        String safeTitle = clip(title, 200), text = body.strip();
        return new Detail(type, id, safeTitle, clip(text, MAX_TEXT), url(type, id),
                AiKnowledgeSources.hash(type + ":" + id + ":" + clean(title) + ":" + text), text.length() > MAX_TEXT);
    }
    private static String clean(String value) { return value == null ? "" : value.replace("\r\n", "\n"); }
    private static String clip(String value, int max) {
        String text = clean(value).strip();
        int end = Math.min(text.length(), max);
        if (end < text.length() && end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end);
    }
    private static String url(String type, long id) { return (type.equals("article") ? "/articles/" : "/projects/") + id; }
}
