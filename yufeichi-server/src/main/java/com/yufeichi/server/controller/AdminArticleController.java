package com.yufeichi.server.controller;

import com.yufeichi.server.common.result.*;
import com.yufeichi.server.dto.*;
import com.yufeichi.server.service.ArticleService;
import com.yufeichi.server.vo.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@io.swagger.v3.oas.annotations.security.SecurityRequirement(name="BearerAuth")
@RestController
@RequestMapping("/api/admin/articles")
@RequiredArgsConstructor
@Validated
public class AdminArticleController {
    private final ArticleService service;
    @GetMapping @PreAuthorize("hasAuthority('article:list')")
    public Result<PageResult<ArticleSummaryVO>> list(@org.springdoc.core.annotations.ParameterObject @Valid ArticleQuery query) { return Result.success(service.list(query,false)); }
    @GetMapping("/{id}") @PreAuthorize("hasAuthority('article:list')")
    public Result<ArticleVO> detail(@Positive @PathVariable long id) { return Result.success(service.detail(id,false)); }
    @PostMapping @PreAuthorize("hasAuthority('article:add')")
    public Result<ArticleVO> create(@Valid @RequestBody ArticleWriteRequest request) { return Result.success(service.save(null,request)); }
    @PutMapping("/{id}") @PreAuthorize("hasAuthority('article:update')")
    public Result<ArticleVO> update(@Positive @PathVariable long id, @Valid @RequestBody ArticleWriteRequest request) { return Result.success(service.save(id,request)); }
    @DeleteMapping("/{id}") @PreAuthorize("hasAuthority('article:delete')")
    public Result<Void> delete(@Positive @PathVariable long id) { service.delete(id); return Result.success(); }
    @PostMapping("/{id}/publish") @PreAuthorize("hasAuthority('article:publish')")
    public Result<ArticleVO> publish(@Positive @PathVariable long id) { return Result.success(service.publish(id,true)); }
    @PostMapping("/{id}/unpublish") @PreAuthorize("hasAuthority('article:publish')")
    public Result<ArticleVO> unpublish(@Positive @PathVariable long id) { return Result.success(service.publish(id,false)); }
}
