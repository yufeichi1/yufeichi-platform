package com.yufeichi.server.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record KnowledgeRequest(@NotBlank @Size(max = 500) String question) { }
