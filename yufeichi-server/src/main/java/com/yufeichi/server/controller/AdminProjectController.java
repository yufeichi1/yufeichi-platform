package com.yufeichi.server.controller;

import com.yufeichi.server.common.result.*;
import com.yufeichi.server.dto.*;
import com.yufeichi.server.service.ProjectService;
import com.yufeichi.server.vo.ProjectVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@io.swagger.v3.oas.annotations.security.SecurityRequirement(name="BearerAuth")
@RestController
@RequestMapping("/api/admin/projects")
@RequiredArgsConstructor
@Validated
public class AdminProjectController {
    private final ProjectService service;
    @GetMapping @PreAuthorize("hasAuthority('project:list')")
    public Result<PageResult<ProjectVO>> list(@org.springdoc.core.annotations.ParameterObject @Valid ProjectQuery query) { return Result.success(service.list(query,false)); }
    @GetMapping("/{id}") @PreAuthorize("hasAuthority('project:list')")
    public Result<ProjectVO> detail(@Positive @PathVariable long id) { return Result.success(service.detail(id,false)); }
    @PostMapping @PreAuthorize("hasAuthority('project:add')")
    public Result<ProjectVO> create(@Valid @RequestBody ProjectWriteRequest request) { return Result.success(service.save(null,request)); }
    @PutMapping("/{id}") @PreAuthorize("hasAuthority('project:update')")
    public Result<ProjectVO> update(@Positive @PathVariable long id,@Valid @RequestBody ProjectWriteRequest request) { return Result.success(service.save(id,request)); }
    @PutMapping("/{id}/status") @PreAuthorize("hasAuthority('project:update')")
    public Result<ProjectVO> status(@Positive @PathVariable long id,@Valid @RequestBody ProjectStatusRequest request) { return Result.success(service.status(id,request.status())); }
    @DeleteMapping("/{id}") @PreAuthorize("hasAuthority('project:delete')")
    public Result<Void> delete(@Positive @PathVariable long id) { service.delete(id); return Result.success(); }
}
