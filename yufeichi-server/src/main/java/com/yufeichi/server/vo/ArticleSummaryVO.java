package com.yufeichi.server.vo;

import java.time.LocalDateTime;

public record ArticleSummaryVO(Long id, String title, String summary, String coverUrl,
        Long categoryId, Long authorId, Integer status, Integer isTop, Integer isFeatured,
        Long viewCount, LocalDateTime publishedAt, LocalDateTime createdAt, LocalDateTime updatedAt) {}
