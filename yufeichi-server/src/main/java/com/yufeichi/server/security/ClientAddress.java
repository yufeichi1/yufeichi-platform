package com.yufeichi.server.security;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.*;
import java.net.InetAddress;
import java.util.*;
@Component
public class ClientAddress {
    private final Set<String> trusted;
    public ClientAddress(@Value("${security.trusted-proxies:}") String proxies) {
        trusted=new HashSet<>(); for(String value:proxies.split(",")) if(!value.isBlank()) trusted.add(value.trim());
    }
    public String current() {
        var attributes=RequestContextHolder.getRequestAttributes();
        return attributes instanceof ServletRequestAttributes servlet ? resolve(servlet.getRequest()) : "internal";
    }
    public String resolve(HttpServletRequest request) {
        String peer=request.getRemoteAddr();
        if(!trusted.contains(peer)) return peer;
        String value=request.getHeader("X-Real-IP");
        // Accept a single numeric address only: never perform hostname DNS lookups.
        if(value==null || !value.matches("[0-9a-fA-F:.]{3,45}") || (!value.contains(".")&&!value.contains(":"))) return peer;
        try { return InetAddress.getByName(value).getHostAddress(); } catch(Exception ignored) { return peer; }
    }
}
