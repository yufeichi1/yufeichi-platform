package com.yufeichi.server.security;
import com.yufeichi.server.common.error.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.*;

@Component
public class RedisSecurityStore {
    private final StringRedisTemplate redis;
    private final int accountLimit,ipLimit;
    private final long windowMillis;
    private static final DefaultRedisScript<Long> CHECK=new DefaultRedisScript<>(
        "if tonumber(redis.call('GET',KEYS[1]) or '0') >= tonumber(ARGV[1]) or tonumber(redis.call('GET',KEYS[2]) or '0') >= tonumber(ARGV[2]) then return 1 end return 0",Long.class);
    private static final DefaultRedisScript<Long> FAIL=new DefaultRedisScript<>(
        "for i=1,2 do local n=redis.call('INCR',KEYS[i]); if n==1 or redis.call('PTTL',KEYS[i])<0 then redis.call('PEXPIRE',KEYS[i],ARGV[1]) end end return 1",Long.class);
    private static final DefaultRedisScript<Long> REVOKE=new DefaultRedisScript<>(
        "redis.call('SET',KEYS[1],'1','PXAT',ARGV[1]); return 1",Long.class);
    public RedisSecurityStore(StringRedisTemplate redis,
        @Value("${security.login.account-limit:5}") int accountLimit,
        @Value("${security.login.ip-limit:20}") int ipLimit,
        @Value("${security.login.window-seconds:600}") long windowSeconds) {
        if(accountLimit<1||ipLimit<1||windowSeconds<1||windowSeconds>86400) throw new IllegalArgumentException("Invalid login limits");
        this.redis=redis;this.accountLimit=accountLimit;this.ipLimit=ipLimit;this.windowMillis=windowSeconds*1000;
    }
    public static String normalizeUsername(String username) {
        return Normalizer.normalize(username.strip(),Normalizer.Form.NFKD).replaceAll("\\p{M}","").toLowerCase(Locale.ROOT);
    }
    public static String digest(String text) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    public static String revokedKey(String token){return "security:revoked:"+digest(token);}
    public List<String> failureKeys(String username,String ip){
        return List.of("security:login:user:"+digest(normalizeUsername(username)),"security:login:ip:"+digest(ip));
    }
    public void checkLogin(String username,String ip){
        try {
            Long limited=redis.execute(CHECK,failureKeys(username,ip),String.valueOf(accountLimit),String.valueOf(ipLimit));
            if(limited==null) throw new SecurityUnavailableException();
            if(limited==1) throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS);
        }
        catch(DataAccessException exception){throw new SecurityUnavailableException();}
    }
    public void failedLogin(String username,String ip){
        try {if(redis.execute(FAIL,failureKeys(username,ip),String.valueOf(windowMillis))==null) throw new SecurityUnavailableException();}
        catch(DataAccessException exception){throw new SecurityUnavailableException();}
    }
    public void successfulLogin(String username){
        // Never reset the shared IP counter when an attacker logs into a different valid account.
        try {if(redis.delete("security:login:user:"+digest(normalizeUsername(username)))==null) throw new SecurityUnavailableException();}
        catch(DataAccessException exception){throw new SecurityUnavailableException();}
    }
    public boolean isRevoked(String token){
        try {
            Boolean revoked=redis.hasKey(revokedKey(token));
            if(revoked==null) throw new SecurityUnavailableException();
            return revoked;
        }
        catch(DataAccessException exception){throw new SecurityUnavailableException();}
    }
    public void revoke(String token,long expiresAt){
        if(expiresAt<=System.currentTimeMillis())return;
        try {if(redis.execute(REVOKE,List.of(revokedKey(token)),String.valueOf(expiresAt))==null) throw new SecurityUnavailableException();}
        catch(DataAccessException exception){throw new SecurityUnavailableException();}
    }
}
