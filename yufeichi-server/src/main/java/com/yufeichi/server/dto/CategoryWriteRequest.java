package com.yufeichi.server.dto;

import jakarta.validation.constraints.*;

public record CategoryWriteRequest(
        @NotBlank @Size(max=50) String name,
        @NotBlank @Size(max=80) @Pattern(regexp="[a-z0-9]+(?:-[a-z0-9]+)*") String slug,
        @Size(max=255) String description,
        @Min(0) @Max(1000000) Integer sortOrder,
        @Min(0) @Max(1) Integer status) {}
