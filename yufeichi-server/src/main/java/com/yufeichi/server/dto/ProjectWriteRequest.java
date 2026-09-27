package com.yufeichi.server.dto;

import jakarta.validation.constraints.*;

public record ProjectWriteRequest(
        @NotBlank @Size(max=100) String name,
        @NotBlank @Size(max=20000) String description,
        @Size(max=500) @Pattern(regexp="^(?:/uploads/(?:avatar|article|project|other)/[a-f0-9-]+\\.(?:png|jpg)|https?://[^\\s]+)?$") String coverUrl,
        @Size(max=500) @Pattern(regexp="^(?:https?://[^\\s]+)?$") String githubUrl,
        @Size(max=500) @Pattern(regexp="^(?:https?://[^\\s]+)?$") String demoUrl,
        @Size(max=500) String techStack,
        @Min(0) @Max(1000000) Integer sortOrder,
        @Min(0) @Max(1) Integer status) {}
