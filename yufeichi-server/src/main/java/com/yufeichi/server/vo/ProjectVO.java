package com.yufeichi.server.vo;

import java.time.LocalDateTime;

public record ProjectVO(Long id, String name, String description, String coverUrl, String githubUrl,
        String demoUrl, String techStack, Integer sortOrder, Integer status, LocalDateTime createdAt, LocalDateTime updatedAt) {}
