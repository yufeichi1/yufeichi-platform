package com.yufeichi.server.ai;

import org.springframework.ai.document.Document;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Only public business fields leave MySQL. No authors, accounts, drafts or external URLs. */
@Component
public class AiKnowledgeSources {
    public static final int MAX_CHUNKS = 100;
    public record Source(String type, long id, String title, String text, String hash, String url) { }
    public record Snapshot(List<Source> sources, String hash) { }
    private final JdbcTemplate jdbc;
    public AiKnowledgeSources(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Snapshot read(boolean lock) {
        String suffix = lock ? " FOR SHARE" : "";
        var sources = new ArrayList<Source>();
        jdbc.query("SELECT id,title,summary,content FROM blog_article WHERE status=1 AND deleted=0 ORDER BY id LIMIT 501" + suffix,
                rs -> { sources.add(source("article", rs.getLong("id"), rs.getString("title"),
                        text(rs.getString("summary")) + "\n\n" + text(rs.getString("content")))); });
        if (sources.size() > 500) throw new IndexLimitException();
        jdbc.query("SELECT id,name,description,tech_stack FROM project WHERE status=1 AND deleted=0 ORDER BY id LIMIT 501" + suffix,
                rs -> { sources.add(source("project", rs.getLong("id"), rs.getString("name"),
                        text(rs.getString("description")) + "\n\n" + text(rs.getString("tech_stack")))); });
        if (sources.size() > 500) throw new IndexLimitException();
        StringBuilder manifest = new StringBuilder("paragraph-v1/1024\n");
        for (Source source : sources) manifest.append(source.type()).append(':').append(source.id()).append(':').append(source.hash()).append('\n');
        return new Snapshot(List.copyOf(sources), hash(manifest.toString()));
    }
    private static Source source(String type, long id, String title, String body) {
        String cleanTitle = text(title).strip();
        String text = cleanTitle + "\n\n" + body.replace("\r\n", "\n").strip();
        if (text.length() > 200_000) throw new IndexLimitException();
        String url = (type.equals("article") ? "/articles/" : "/projects/") + id;
        return new Source(type, id, cleanTitle, text, hash(text), url);
    }
    public List<Document> chunks(Snapshot snapshot, String version, String model, int dimensions) {
        UUID.fromString(version);
        var result = new ArrayList<Document>();
        for (Source source : snapshot.sources()) {
            var pieces = split(source.text());
            for (int i = 0; i < pieces.size(); i++) {
                String key = version + ":" + source.type() + ":" + source.id() + ":" + source.hash() + ":" + i;
                String id = UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
                result.add(new Document(id, pieces.get(i), Map.of("indexVersion", version, "sourceType", source.type(),
                        "sourceId", source.id(), "title", source.title(), "url", source.url(), "contentHash", source.hash(),
                        "chunk", i, "chunker", "paragraph-v1", "embeddingModel", model, "dimensions", dimensions)));
                if (result.size() > MAX_CHUNKS) throw new IndexLimitException();
            }
        }
        return List.copyOf(result);
    }
    static List<String> split(String text) {
        var result = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        for (String paragraph : text.split("\\n\\s*\\n")) {
            String remaining = paragraph.strip();
            if (remaining.isEmpty()) continue;
            if (!current.isEmpty() && current.length() + remaining.length() + 2 > 1024) {
                result.add(current.toString()); current.setLength(0);
            }
            while (remaining.length() > 1024) {
                int end = Character.isHighSurrogate(remaining.charAt(1023)) ? 1023 : 1024;
                result.add(remaining.substring(0, end)); remaining = remaining.substring(end);
            }
            if (!remaining.isEmpty()) { if (!current.isEmpty()) current.append("\n\n"); current.append(remaining); }
        }
        if (!current.isEmpty()) result.add(current.toString());
        return result;
    }
    static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String text(String value) { return value == null ? "" : value; }
    public static final class IndexLimitException extends RuntimeException { }
}
