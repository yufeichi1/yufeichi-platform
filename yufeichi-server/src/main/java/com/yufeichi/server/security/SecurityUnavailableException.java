package com.yufeichi.server.security;
import com.yufeichi.server.common.error.*;
public class SecurityUnavailableException extends BusinessException {
    public SecurityUnavailableException() { super(ErrorCode.SERVICE_UNAVAILABLE); }
}
