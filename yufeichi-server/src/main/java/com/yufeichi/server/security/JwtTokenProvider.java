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
            @Value("${jwt.expire-minutes:1440}") long expireMinutes
    ) {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);

        if (keyBytes.length < 32) {
            throw new IllegalArgumentException(
                    "jwt.secret 至少需要 32 个 UTF-8 字节"
            );
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
                .subject(String.valueOf(loginUser.getUser().getId()))
                .claim("username", loginUser.getUsername())
                .issuedAt(issuedAt)
                .expiration(expiration)
                .signWith(secretKey)
                .compact();
    }

    public boolean validateToken(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException exception) {
            return false;
        }
    }

    public Long getUserId(String token) {
        return Long.valueOf(parseClaims(token).getSubject());
    }

    public String getUsername(String token) {
        return parseClaims(token)
                .get("username", String.class);
    }

    public long getExpireMinutes() {
        return expireMinutes;
    }

    private Claims parseClaims(String token) {
        return jwtParser
                .parseSignedClaims(token)
                .getPayload();
    }
}
