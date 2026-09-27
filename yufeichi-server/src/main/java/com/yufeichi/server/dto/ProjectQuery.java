package com.yufeichi.server.dto;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ProjectQuery extends PageQuery {
    @Min(0) @Max(1) private Integer status;
}
