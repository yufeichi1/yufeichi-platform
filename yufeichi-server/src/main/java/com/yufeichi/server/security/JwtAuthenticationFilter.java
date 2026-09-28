package com.yufeichi.server.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yufeichi.server.common.error.ErrorCode;
import com.yufeichi.server.common.result.Result;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AccountStatusUserDetailsChecker;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String TOKEN_PREFIX = "Bearer ";

    private final JwtTokenProvider jwtTokenProvider;
    private final CustomUserDetailsService userDetailsService;
    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final ObjectMapper objectMapper;
    private final RedisSecurityStore securityStore;
    private final AccountStatusUserDetailsChecker accountChecker = new AccountStatusUserDetailsChecker();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path=request.getServletPath();
        // Public reading is deliberately independent of Redis and ignores optional Bearer headers.
        return "OPTIONS".equals(request.getMethod()) || path.equals("/api/auth/login") || path.equals("/api/health")
                || ("GET".equals(request.getMethod()) && java.util.List.of("/api/articles","/api/projects","/api/categories","/api/tags","/uploads")
                    .stream().anyMatch(prefix -> path.equals(prefix)||path.startsWith(prefix+"/")));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        try {
            String token = resolveToken(request);
            if (token != null) {
                // Parse and verify once. Account checks also apply to previously issued tokens.
                Long userId = jwtTokenProvider.getUserId(token);
                if(securityStore.isRevoked(token)) throw new BadCredentialsException("Revoked authentication");
                LoginUser loginUser = userDetailsService.loadUserById(userId);
                accountChecker.check(loginUser);
                var authentication = new UsernamePasswordAuthenticationToken(
                        loginUser, null, loginUser.getAuthorities());
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        } catch (SecurityUnavailableException exception) {
            SecurityContextHolder.clearContext();
            response.setStatus(503);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getWriter(), Result.error(ErrorCode.SERVICE_UNAVAILABLE));
            return;
        } catch (DataAccessException | AuthenticationServiceException exception) {
            SecurityContextHolder.clearContext();
            log.error("Authentication infrastructure failure type={}", exception.getClass().getSimpleName());
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getWriter(), Result.error(ErrorCode.SYSTEM_ERROR));
            return;
        } catch (JwtException | IllegalArgumentException | AuthenticationException exception) {
            SecurityContextHolder.clearContext();
            authenticationEntryPoint.commence(request, response,
                    new BadCredentialsException("Invalid authentication credentials", exception));
            return;
        }

        // Downstream application errors must not be mistaken for authentication failures.
        filterChain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        String authorization =
                request.getHeader("Authorization");

        if (authorization == null) {
            return null;
        }
        if (!authorization.regionMatches(true, 0, TOKEN_PREFIX, 0, TOKEN_PREFIX.length())) {
            throw new BadCredentialsException("Bearer authentication required");
        }
        String token = authorization.substring(TOKEN_PREFIX.length()).trim();
        if (token.isEmpty()) {
            throw new BadCredentialsException("Bearer token missing");
        }
        return token;
    }
}
