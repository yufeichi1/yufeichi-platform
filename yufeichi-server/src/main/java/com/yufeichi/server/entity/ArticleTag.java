package com.yufeichi.server.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("blog_article_tag")
public class ArticleTag {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long articleId;
    private Long tagId;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
