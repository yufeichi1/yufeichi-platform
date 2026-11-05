package com.yufeichi.server.ai;

import java.util.List;

public record KnowledgeResponse(String answer, boolean answered, List<Source> sources,
                                String indexVersion, String requestId) {
    public record Source(String id, String sourceType, long sourceId, String title, String url) { }
}
