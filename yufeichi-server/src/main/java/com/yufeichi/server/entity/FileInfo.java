package com.yufeichi.server.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("file_info")
public class FileInfo {
    @TableId(type=IdType.AUTO) private Long id;
    private String originalName;
    private String fileName;
    private String fileUrl;
    @JsonIgnore private String filePath;
    private String fileType;
    private String fileExt;
    private Long fileSize;
    private String bizType;
    private Long uploaderId;
    @TableField(fill=FieldFill.INSERT) private LocalDateTime createdAt;
    @TableLogic private Integer deleted;
}
