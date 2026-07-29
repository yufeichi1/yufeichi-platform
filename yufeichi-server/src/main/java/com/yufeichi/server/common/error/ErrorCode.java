package com.yufeichi.server.common.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    SUCCESS(0, "success"),

    PARAM_ERROR(40000, "请求参数错误"),
    UNAUTHORIZED(40100, "未登录或登录已失效"),
    FORBIDDEN(40300, "没有访问权限"),
    NOT_FOUND(40400, "请求资源不存在"),

    SYSTEM_ERROR(50000, "系统内部错误"),
    USERNAME_OR_PASSWORD_ERROR(50001, "用户名或密码错误"),
    USER_DISABLED(50002, "用户已被禁用"),

    ARTICLE_NOT_FOUND(60001, "文章不存在"),
    PROJECT_NOT_FOUND(61001, "项目不存在"),
    FILE_UPLOAD_ERROR(62001, "文件上传失败");

    private final int code;
    private final String message;
}
