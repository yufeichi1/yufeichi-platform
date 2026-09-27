package com.yufeichi.server.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class PageQuery {
    @Min(1) @Max(1000000)
    private long pageNum = 1;
    @Min(1) @Max(100)
    private long pageSize = 10;
    @Size(max=100)
    private String keyword;
}
