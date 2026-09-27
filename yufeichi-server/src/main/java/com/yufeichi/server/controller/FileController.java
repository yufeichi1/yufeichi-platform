package com.yufeichi.server.controller;

import com.yufeichi.server.common.result.Result;
import com.yufeichi.server.dto.FileUploadRequest;
import com.yufeichi.server.service.FileService;
import com.yufeichi.server.vo.FileVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;

@io.swagger.v3.oas.annotations.security.SecurityRequirement(name="BearerAuth")
@RestController
@RequestMapping("/api/admin/files")
@RequiredArgsConstructor
public class FileController {
    private final FileService service;
    @PostMapping(value="/upload",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('file:upload')")
    public Result<FileVO> upload(@Valid @ModelAttribute FileUploadRequest request) throws IOException {
        return Result.success(service.upload(request));
    }
}
