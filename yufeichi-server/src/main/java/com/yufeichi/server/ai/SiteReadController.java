package com.yufeichi.server.ai;

import com.yufeichi.server.common.result.Result;
import com.yufeichi.server.security.LoginUser;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai/tools")
@PreAuthorize("isAuthenticated()")
public class SiteReadController {
    private final SiteReadWorkflow workflow;
    public SiteReadController(SiteReadWorkflow workflow) { this.workflow = workflow; }
    @GetMapping("/status") public Result<java.util.Map<String, Boolean>> status() {
        return Result.success(java.util.Map.of("available", workflow.available()));
    }
    @PostMapping("/query") public Result<SiteReadWorkflow.Response> query(@Valid @RequestBody ToolQueryRequest request,
                                                                       @AuthenticationPrincipal LoginUser user) {
        return Result.success(workflow.query(request, user.getUser().getId()));
    }
}
