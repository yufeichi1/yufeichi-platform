package com.yufeichi.server.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class JwtTokenProvider {

    private final SecretKey secretKey;
    private final JwtParser jwtParser;
    private final long expireMinutes;

    public JwtTokenProvider(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.expire-minutes:45}") long expireMinutes
    ) {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);

        if (keyBytes.length < 32) {
            throw new IllegalArgumentException(
                    "jwt.secret 至少需要 32 个 UTF-8 字节"
            );
        }
        if (expireMinutes < 30 || expireMinutes > 60) {
            throw new IllegalArgumentException("jwt.expire-minutes 必须在30至60分钟之间");
        }

        this.secretKey = Keys.hmacShaKeyFor(keyBytes);
        this.jwtParser = Jwts.parser()
                .verifyWith(secretKey)
                .build();
        this.expireMinutes = expireMinutes;
    }

    public String createToken(LoginUser loginUser) {
        Date issuedAt = new Date();
        Date expiration = new Date(
                issuedAt.getTime() + expireMinutes * 60_000L
        );

        return Jwts.builder()
                .id(java.util.UUID.randomUUID().toString())
                .subject(String.valueOf(loginUser.getUser().getId()))
                .claim("username", loginUser.getUsername())
                .issuedAt(issuedAt)
                .expiration(expiration)
                .signWith(secretKey)
                .compact();
    }

    public boolean validateToken(String token) {
        try {
            getUserId(token);
            return true;
        } catch (JwtException | IllegalArgumentException exception) {
            return false;
        }
    }

    public Long getUserId(String token) {
        Claims claims = parseClaims(token);
        String subject = claims.getSubject();
        if (claims.getExpiration() == null || subject == null
                || !subject.matches("[1-9][0-9]*")) {
            throw new IllegalArgumentException("Token 缺少有效的用户 ID 或过期时间");
        }
        return Long.valueOf(subject);
    }

    public String getUsername(String token) {
        return parseClaims(token)
                .get("username", String.class);
    }

    public long getExpireMinutes() {
        return expireMinutes;
    }
    public long getExpiresAt(String token) { return parseClaims(token).getExpiration().getTime(); }

    private Claims parseClaims(String token) {
        return jwtParser
                .parseSignedClaims(token)
                .getPayload();
    }
}
