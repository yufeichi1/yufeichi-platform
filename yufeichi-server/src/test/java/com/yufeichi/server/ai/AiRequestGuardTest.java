package com.yufeichi.server.ai;

import com.yufeichi.server.common.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiRequestGuardTest {
    @Test void concurrencyIsBoundedAndClosingTwiceDoesNotCreateExtraCapacity() {
        var redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);
        var properties = new AiProperties();
        properties.setMaxConcurrentRequests(1);
        var guard = new AiRequestGuard(redis, properties);
        var first = guard.acquire(1);
        assertThatThrownBy(() -> guard.acquire(2)).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getCode()).isEqualTo(42900));
        first.close(); first.close();
        var next = guard.acquire(2);
        assertThatThrownBy(() -> guard.acquire(3)).isInstanceOf(BusinessException.class);
        next.close();
    }
    @Test void redisFailureClosesPermitAndFailsAsAi503() {
        var redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException("PRIVATE-REDIS-VALUE")).thenReturn(1L);
        var properties = new AiProperties();
        properties.setMaxConcurrentRequests(1);
        var guard = new AiRequestGuard(redis, properties);
        assertThatThrownBy(() -> guard.acquire(1)).isInstanceOfSatisfying(BusinessException.class, error -> {
            assertThat(error.getCode()).isEqualTo(63001);
            assertThat(error.getMessage()).doesNotContain("PRIVATE");
        });
        assertThatCode(() -> guard.acquire(1).close()).doesNotThrowAnyException();
    }
    @Test void nullAndRejectedQuotaNeverAcquireUsableLease() {
        var redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(null, 0L, 1L);
        var properties = new AiProperties();
        properties.setMaxConcurrentRequests(1);
        var guard = new AiRequestGuard(redis, properties);
        assertThatThrownBy(() -> guard.acquire(1)).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getCode()).isEqualTo(63001));
        assertThatThrownBy(() -> guard.acquire(1)).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getCode()).isEqualTo(42900));
        assertThatCode(() -> guard.acquire(1).close()).doesNotThrowAnyException();
    }
}
