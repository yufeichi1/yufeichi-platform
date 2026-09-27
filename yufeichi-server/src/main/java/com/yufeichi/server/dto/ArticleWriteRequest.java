package com.yufeichi.server.dto;

import jakarta.validation.constraints.*;
import java.util.List;

// Full replacement of editable fields; status/author/counters are never client-controlled.
public record ArticleWriteRequest(
        @NotBlank @Size(max=200) String title,
        @Size(max=500) String summary,
        @NotBlank @Size(max=200000) String content,
        @Size(max=500) @Pattern(regexp="^(?:/uploads/(?:avatar|article|project|other)/[a-f0-9-]+\\.(?:png|jpg)|https?://[^\\s]+)?$") String coverUrl,
        @Positive Long categoryId,
        @Size(max=50) List<@NotNull @Positive Long> tagIds,
        @Min(0) @Max(1) Integer isTop,
        @Min(0) @Max(1) Integer isFeatured) {}
