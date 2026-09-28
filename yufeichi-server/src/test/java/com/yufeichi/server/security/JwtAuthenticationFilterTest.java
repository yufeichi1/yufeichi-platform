package com.yufeichi.server.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yufeichi.server.entity.User;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class JwtAuthenticationFilterTest {
    private static final String SECRET = "day1-unit-test-secret-at-least-32-bytes-long";
    private final ObjectMapper mapper = new ObjectMapper();
    private CustomUserDetailsService users;
    private JwtAuthenticationFilter filter;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        users = mock(CustomUserDetailsService.class);
        chain = mock(FilterChain.class);
        filter = new JwtAuthenticationFilter(new JwtTokenProvider(SECRET, 45), users,
                new RestAuthenticationEntryPoint(mapper), mapper, mock(RedisSecurityStore.class));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void enabledUserIsAuthenticatedAndChainRuns() throws Exception {
        when(users.loadUserById(1L)).thenReturn(user(1));
        var request = request("Bearer " + token("1", Instant.now().plusSeconds(60), SECRET));
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        assertThat(SecurityContextHolder.getContext().getAuthentication().isAuthenticated()).isTrue();
        verify(chain).doFilter(request, response);
        verify(users).loadUserById(1L);
    }

    @Test
    void disabledUserWithPreviouslyIssuedTokenIsRejected() throws Exception {
        when(users.loadUserById(1L)).thenReturn(user(0));
        assertRejected("Bearer " + token("1", Instant.now().plusSeconds(60), SECRET));
    }

    @Test
    void deletedUserIsRejected() throws Exception {
        when(users.loadUserById(1L)).thenThrow(new UsernameNotFoundException("deleted"));
        assertRejected("Bearer " + token("1", Instant.now().plusSeconds(60), SECRET));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidCredentials")
    void invalidCredentialsReturn401WithoutDatabaseAccess(String description, String header) throws Exception {
        assertRejected(header);
        verifyNoInteractions(users);
    }

    static Stream<Arguments> invalidCredentials() {
        Instant future = Instant.now().plusSeconds(60);
        return Stream.of(
                Arguments.of("malformed", "Bearer not-a-jwt"),
                Arguments.of("empty bearer", "Bearer "),
                Arguments.of("wrong scheme", "Basic abc"),
                Arguments.of("expired", "Bearer " + token("1", Instant.now().minusSeconds(60), SECRET)),
                Arguments.of("tampered signature", "Bearer " + token("1", future, SECRET + "different")),
                Arguments.of("missing subject", "Bearer " + token(null, future, SECRET)),
                Arguments.of("non-numeric subject", "Bearer " + token("admin", future, SECRET)),
                Arguments.of("negative subject", "Bearer " + token("-1", future, SECRET)),
                Arguments.of("zero subject", "Bearer " + token("0", future, SECRET)),
                Arguments.of("overflow subject", "Bearer " + token("999999999999999999999999", future, SECRET)),
                Arguments.of("missing expiry", "Bearer " + token("1", null, SECRET))
        );
    }

    @Test
    void databaseOutageIs500Not401AndDoesNotContinue() throws Exception {
        when(users.loadUserById(anyLong())).thenThrow(new DataAccessResourceFailureException("test outage"));
        var response = new MockHttpServletResponse();
        filter.doFilter(request("Bearer " + token("1", Instant.now().plusSeconds(60), SECRET)), response, chain);
        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(mapper.readTree(response.getContentAsString()).path("code").asInt()).isEqualTo(50000);
        assertThat(response.getContentAsString()).doesNotContain("test outage");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(chain);
    }

    @Test
    void missingTokenIsLeftForSecurityAuthorization() throws Exception {
        var request = request(null);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        verify(chain).doFilter(request, response);
        verifyNoInteractions(users);
    }

    private void assertRejected(String header) throws Exception {
        var response = new MockHttpServletResponse();
        filter.doFilter(request(header), response, chain);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(mapper.readTree(response.getContentAsString()).path("code").asInt()).isEqualTo(40100);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(chain);
    }

    private static MockHttpServletRequest request(String header) {
        var request = new MockHttpServletRequest("GET", "/api/auth/me");
        if (header != null) request.addHeader("Authorization", header);
        return request;
    }

    private static LoginUser user(int status) {
        User user = new User();
        user.setId(1L);
        user.setUsername("test-user");
        user.setStatus(status);
        return new LoginUser(user, List.of("reader"), List.of("article:list"));
    }

    private static String token(String subject, Instant expiration, String secret) {
        var builder = Jwts.builder().subject(subject);
        if (expiration != null) builder.expiration(Date.from(expiration));
        return builder.signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8))).compact();
    }
}
