package com.yufeichi.server.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SummaryRequest(
        @NotBlank(message = "正文不能为空") @Size(max = 20000, message = "正文超过 AI 输入限制") String content) { }
