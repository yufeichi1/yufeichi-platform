package com.yufeichi.server.common.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    SUCCESS(0, "success", HttpStatus.OK),

    PARAM_ERROR(40000, "请求参数错误", HttpStatus.BAD_REQUEST),
    UNAUTHORIZED(40100, "未登录或登录已失效", HttpStatus.UNAUTHORIZED),
    FORBIDDEN(40300, "没有访问权限", HttpStatus.FORBIDDEN),
    NOT_FOUND(40400, "请求资源不存在", HttpStatus.NOT_FOUND),
    METHOD_NOT_ALLOWED(40500, "请求方法不支持", HttpStatus.METHOD_NOT_ALLOWED),
    CONFLICT(40900, "数据已存在或发生冲突", HttpStatus.CONFLICT),
    PAYLOAD_TOO_LARGE(41300, "上传文件超过大小限制", HttpStatus.PAYLOAD_TOO_LARGE),
    UNSUPPORTED_MEDIA_TYPE(41500, "请求媒体类型不支持", HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    TOO_MANY_REQUESTS(42900, "请求过于频繁", HttpStatus.TOO_MANY_REQUESTS),

    SYSTEM_ERROR(50000, "系统内部错误", HttpStatus.INTERNAL_SERVER_ERROR),
    SERVICE_UNAVAILABLE(50300, "认证服务暂不可用，请稍后重试", HttpStatus.SERVICE_UNAVAILABLE),
    USERNAME_OR_PASSWORD_ERROR(50001, "用户名或密码错误", HttpStatus.UNAUTHORIZED),
    USER_DISABLED(50002, "用户已被禁用", HttpStatus.UNAUTHORIZED),

    ARTICLE_NOT_FOUND(60001, "文章不存在", HttpStatus.NOT_FOUND),
    PROJECT_NOT_FOUND(61001, "项目不存在", HttpStatus.NOT_FOUND),
    FILE_UPLOAD_ERROR(62001, "文件上传失败", HttpStatus.INTERNAL_SERVER_ERROR);

    private final int code;
    private final String message;
    private final HttpStatus httpStatus;

    public static HttpStatus httpStatusFor(int code) {
        for (ErrorCode error : values()) {
            if (error.code == code && error != SUCCESS) {
                return error.httpStatus;
            }
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }
}
