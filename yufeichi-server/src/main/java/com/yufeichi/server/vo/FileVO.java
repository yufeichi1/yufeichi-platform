package com.yufeichi.server.vo;

public record FileVO(Long id, String originalName, String fileName, String fileUrl,
        String fileType, String fileExt, Long fileSize, String bizType) {}
