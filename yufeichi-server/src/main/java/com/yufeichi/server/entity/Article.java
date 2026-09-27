package com.yufeichi.server.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("blog_article")
public class Article {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String title;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String summary;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String content;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String coverUrl;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long categoryId;
    private Long authorId;
    private Integer status;
    private Integer isTop;
    private Integer isFeatured;
    private Long viewCount;
    private LocalDateTime publishedAt;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;
}
