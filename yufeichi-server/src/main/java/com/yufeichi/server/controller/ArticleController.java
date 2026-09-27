package com.yufeichi.server.controller;

import com.yufeichi.server.common.result.*;
import com.yufeichi.server.dto.ArticleQuery;
import com.yufeichi.server.service.ArticleService;
import com.yufeichi.server.vo.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/articles")
@RequiredArgsConstructor
@Validated
public class ArticleController {
    private final ArticleService service;
    @GetMapping
    public Result<PageResult<ArticleSummaryVO>> list(@org.springdoc.core.annotations.ParameterObject @Valid ArticleQuery query) { return Result.success(service.list(query,true)); }
    @GetMapping("/{id}")
    public Result<ArticleVO> detail(@Positive @PathVariable long id) { return Result.success(service.detail(id,true)); }
}
