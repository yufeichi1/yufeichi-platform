package com.yufeichi.server.dto;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ArticleQuery extends PageQuery {
    @Positive private Long categoryId;
    @Positive private Long tagId;
    @Min(0) @Max(2) private Integer status;
}
