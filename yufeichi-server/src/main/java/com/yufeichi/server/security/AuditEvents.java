package com.yufeichi.server.security;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
@Slf4j
public final class AuditEvents {
    private AuditEvents(){}
    public static Long actor(){
        var auth=SecurityContextHolder.getContext().getAuthentication();
        return auth!=null && auth.getPrincipal() instanceof LoginUser user ? user.getUser().getId() : null;
    }
    public static void record(String action,String outcome,Long actor,Long target){
        // Only fixed event names and numeric IDs. Never log arguments, bodies, credentials or token values.
        log.info("audit action={} outcome={} actor={} target={}",action,outcome,actor,target);
    }
}
