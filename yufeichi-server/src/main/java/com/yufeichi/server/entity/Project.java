package com.yufeichi.server.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("project")
public class Project {
    @TableId(type=IdType.AUTO) private Long id;
    private String name;
    private String description;
    @TableField(updateStrategy=FieldStrategy.ALWAYS) private String coverUrl;
    @TableField(updateStrategy=FieldStrategy.ALWAYS) private String githubUrl;
    @TableField(updateStrategy=FieldStrategy.ALWAYS) private String demoUrl;
    @TableField(updateStrategy=FieldStrategy.ALWAYS) private String techStack;
    private Integer sortOrder;
    private Integer status;
    @TableField(fill=FieldFill.INSERT) private LocalDateTime createdAt;
    @TableField(fill=FieldFill.INSERT_UPDATE) private LocalDateTime updatedAt;
    @TableLogic private Integer deleted;
}
