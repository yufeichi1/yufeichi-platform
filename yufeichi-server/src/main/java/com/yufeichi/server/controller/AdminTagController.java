package com.yufeichi.server.controller;

import com.yufeichi.server.common.result.Result;
import com.yufeichi.server.dto.TagWriteRequest;
import com.yufeichi.server.service.TaxonomyService;
import com.yufeichi.server.vo.TagVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@io.swagger.v3.oas.annotations.security.SecurityRequirement(name="BearerAuth")
@RestController
@RequestMapping("/api/admin/tags")
@RequiredArgsConstructor
@Validated
public class AdminTagController {
    private final TaxonomyService service;
    @GetMapping
    @PreAuthorize("hasAuthority('tag:list')")
    public Result<List<TagVO>> list() { return Result.success(service.tags(false)); }
    @PostMapping
    @PreAuthorize("hasAuthority('tag:add')")
    public Result<TagVO> create(@Valid @RequestBody TagWriteRequest request) {
        return Result.success(service.saveTag(null,request));
    }
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('tag:update')")
    public Result<TagVO> update(@Positive @PathVariable long id, @Valid @RequestBody TagWriteRequest request) {
        return Result.success(service.saveTag(id,request));
    }
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('tag:delete')")
    public Result<Void> delete(@Positive @PathVariable long id) { service.deleteTag(id); return Result.success(); }
}
