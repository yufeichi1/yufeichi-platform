package com.yufeichi.server.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("blog_category")
public class Category {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String slug;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String description;
    private Integer sortOrder;
    private Integer status;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;
}
