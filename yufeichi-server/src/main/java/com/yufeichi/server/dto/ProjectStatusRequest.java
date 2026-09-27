package com.yufeichi.server.dto;

import jakarta.validation.constraints.*;

public record ProjectStatusRequest(@NotNull @Min(0) @Max(1) Integer status) {}
