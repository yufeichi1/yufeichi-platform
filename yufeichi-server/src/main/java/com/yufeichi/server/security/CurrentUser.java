package com.yufeichi.server.security;

import com.yufeichi.server.common.error.*;
import org.springframework.security.core.context.SecurityContextHolder;

public final class CurrentUser {
    private CurrentUser() {}
    public static long id() {
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        if(authentication==null || !(authentication.getPrincipal() instanceof LoginUser user))
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        return user.getUser().getId();
    }
}
