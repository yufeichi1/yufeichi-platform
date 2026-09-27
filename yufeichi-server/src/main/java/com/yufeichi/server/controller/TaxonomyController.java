package com.yufeichi.server.controller;

import com.yufeichi.server.common.result.Result;
import com.yufeichi.server.service.TaxonomyService;
import com.yufeichi.server.vo.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class TaxonomyController {
    private final TaxonomyService service;
    @GetMapping("/categories")
    public Result<List<CategoryVO>> categories() { return Result.success(service.categories(true)); }
    @GetMapping("/tags")
    public Result<List<TagVO>> tags() { return Result.success(service.tags(true)); }
}
