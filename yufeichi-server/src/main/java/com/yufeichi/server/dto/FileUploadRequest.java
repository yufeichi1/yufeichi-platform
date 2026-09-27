package com.yufeichi.server.dto;

import jakarta.validation.constraints.*;
import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

@Data
public class FileUploadRequest {
    @NotNull private MultipartFile file;
    @NotBlank @Pattern(regexp="avatar|article|project|other")
    private String bizType="article";
}
