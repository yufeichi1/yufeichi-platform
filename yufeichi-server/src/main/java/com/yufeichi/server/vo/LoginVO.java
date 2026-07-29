package com.yufeichi.server.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
@Schema(description = "登录结果")
public class LoginVO {

    private String token;
    private String tokenType;
    private long expireMinutes;
    private UserInfoVO userInfo;
    private List<String> permissions;
}
