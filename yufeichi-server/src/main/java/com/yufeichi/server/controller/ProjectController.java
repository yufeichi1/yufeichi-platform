package com.yufeichi.server.controller;

import com.yufeichi.server.common.result.*;
import com.yufeichi.server.dto.ProjectQuery;
import com.yufeichi.server.service.ProjectService;
import com.yufeichi.server.vo.ProjectVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
@Validated
public class ProjectController {
    private final ProjectService service;
    @GetMapping
    public Result<PageResult<ProjectVO>> list(@org.springdoc.core.annotations.ParameterObject @Valid ProjectQuery query) { return Result.success(service.list(query,true)); }
    @GetMapping("/{id}")
    public Result<ProjectVO> detail(@Positive @PathVariable long id) { return Result.success(service.detail(id,true)); }
}
