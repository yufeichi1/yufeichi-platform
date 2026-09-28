package com.yufeichi.server.security;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RedisSecurityStoreTest {
    @Test void missingRedisAcknowledgementNeverAuthenticatesOrReportsSuccessfulRevocation() {
        var redis = mock(StringRedisTemplate.class);
        when(redis.hasKey(anyString())).thenReturn(null);
        when(redis.delete(anyString())).thenReturn(null);
        var store = new RedisSecurityStore(redis, 5, 20, 600);
        assertThatThrownBy(() -> store.checkLogin("user", "127.0.0.1")).isInstanceOf(SecurityUnavailableException.class);
        assertThatThrownBy(() -> store.failedLogin("user", "127.0.0.1")).isInstanceOf(SecurityUnavailableException.class);
        assertThatThrownBy(() -> store.successfulLogin("user")).isInstanceOf(SecurityUnavailableException.class);
        assertThatThrownBy(() -> store.isRevoked("token")).isInstanceOf(SecurityUnavailableException.class);
        assertThatThrownBy(() -> store.revoke("token", System.currentTimeMillis() + 60000)).isInstanceOf(SecurityUnavailableException.class);
    }
}
