package com.yufeichi.server.ai;

import com.yufeichi.server.common.result.Result;
import com.yufeichi.server.security.LoginUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/ai/knowledge")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AiKnowledgeController {
    private final AiKnowledgeIndex index;
    public AiKnowledgeController(AiKnowledgeIndex index) { this.index = index; }
    @GetMapping public Result<Map<String, Object>> status() { return Result.success(index.status()); }
    @PostMapping("/reindex") public Result<Map<String, String>> reindex(@AuthenticationPrincipal LoginUser user) {
        return Result.success(Map.of("jobId", index.submit(user.getUser().getId())));
    }
}
