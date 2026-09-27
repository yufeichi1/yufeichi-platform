package com.yufeichi.server.controller;

import com.yufeichi.server.common.result.Result;
import com.yufeichi.server.dto.CategoryWriteRequest;
import com.yufeichi.server.service.TaxonomyService;
import com.yufeichi.server.vo.CategoryVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@io.swagger.v3.oas.annotations.security.SecurityRequirement(name="BearerAuth")
@RestController
@RequestMapping("/api/admin/categories")
@RequiredArgsConstructor
@Validated
public class AdminCategoryController {
    private final TaxonomyService service;
    @GetMapping
    @PreAuthorize("hasAuthority('category:list')")
    public Result<List<CategoryVO>> list() { return Result.success(service.categories(false)); }
    @PostMapping
    @PreAuthorize("hasAuthority('category:add')")
    public Result<CategoryVO> create(@Valid @RequestBody CategoryWriteRequest request) {
        return Result.success(service.saveCategory(null,request));
    }
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('category:update')")
    public Result<CategoryVO> update(@Positive @PathVariable long id, @Valid @RequestBody CategoryWriteRequest request) {
        return Result.success(service.saveCategory(id,request));
    }
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('category:delete')")
    public Result<Void> delete(@Positive @PathVariable long id) { service.deleteCategory(id); return Result.success(); }
}
