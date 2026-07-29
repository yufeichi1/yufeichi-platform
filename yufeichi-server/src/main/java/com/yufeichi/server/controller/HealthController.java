package com.yufeichi.server.controller;

import com.yufeichi.server.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "系统健康检查")
@RestController
@RequestMapping("/api/health")
public class HealthController {

    @Operation(summary = "检查后端服务是否正常")
    @GetMapping
    public Result<String> health() {
        return Result.success("ok");
    }
}
