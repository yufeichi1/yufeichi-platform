package com.yufeichi.server.vo;

import java.time.LocalDateTime;
import java.util.List;

public record ArticleVO(Long id, String title, String summary, String content, String coverUrl,
        Long categoryId, Long authorId, Integer status, Integer isTop, Integer isFeatured,
        Long viewCount, LocalDateTime publishedAt, LocalDateTime createdAt, LocalDateTime updatedAt,
        List<Long> tagIds, List<TagVO> tags) {}
