package com.yufeichi.server.ai;

import com.yufeichi.server.common.error.BusinessException;
import com.yufeichi.server.common.error.ErrorCode;
import com.yufeichi.server.security.RedisSecurityStore;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class AiRequestGuard {
    private static final DefaultRedisScript<Long> RESERVE = new DefaultRedisScript<>("""
            for i=1,#KEYS do
              if tonumber(redis.call('GET',KEYS[i]) or '0') >= tonumber(ARGV[i*2-1]) then return 0 end
            end
            for i=1,#KEYS do
              redis.call('INCR',KEYS[i])
              if redis.call('PTTL',KEYS[i]) < 0 then redis.call('PEXPIRE',KEYS[i],ARGV[i*2]) end
            end
            return 1
            """, Long.class);
    private final StringRedisTemplate redis;
    private final AiProperties properties;
    private final Semaphore concurrent;

    public AiRequestGuard(StringRedisTemplate redis, AiProperties properties) {
        this.redis = redis;
        this.properties = properties;
        this.concurrent = new Semaphore(properties.getMaxConcurrentRequests());
    }

    public Lease acquire(long userId) {
        if (!concurrent.tryAcquire()) throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS);
        Lease lease = new Lease();
        try {
            String day = LocalDate.now(ZoneOffset.UTC).toString();
            String user = RedisSecurityStore.digest(Long.toString(userId));
            reserve(List.of("ai:quota:user:minute:" + user, "ai:quota:user:day:" + day + ":" + user,
                            "ai:quota:global:day:" + day),
                    Integer.toString(properties.getUserMinuteLimit()), "60000",
                    Integer.toString(properties.getUserDailyLimit()), "172800000",
                    Integer.toString(properties.getDailyRequestLimit()), "172800000");
            return lease;
        } catch (RuntimeException failure) {
            lease.close();
            throw failure;
        }
    }

    public void reserveRetry() {
        reserve(List.of("ai:quota:global:day:" + LocalDate.now(ZoneOffset.UTC)),
                Integer.toString(properties.getDailyRequestLimit()), "172800000");
    }

    private static final DefaultRedisScript<Long> INDEX_RESERVE = new DefaultRedisScript<>("""
            if tonumber(redis.call('GET',KEYS[1]) or '0') >= tonumber(ARGV[1]) then return 0 end
            if tonumber(redis.call('GET',KEYS[2]) or '0') >= 10 then return 0 end
            if tonumber(redis.call('GET',KEYS[3]) or '0') + tonumber(ARGV[2]) > 100000 then return 0 end
            redis.call('INCR',KEYS[1]); redis.call('INCR',KEYS[2]); redis.call('INCRBY',KEYS[3],ARGV[2])
            for i=1,3 do if redis.call('PTTL',KEYS[i]) < 0 then redis.call('PEXPIRE',KEYS[i],172800000) end end
            return 1
            """, Long.class);

    /** Same concurrency/global request budget as Chat; additional conservative embedding caps. No automatic retries. */
    public Lease acquireIndexBatch(int chars) {
        if (chars < 1 || chars > 20480) throw new IllegalArgumentException("Invalid embedding batch size");
        if (!concurrent.tryAcquire()) throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS);
        var lease = new Lease();
        try {
            String day = LocalDate.now(ZoneOffset.UTC).toString();
            Long accepted = redis.execute(INDEX_RESERVE, List.of("ai:quota:global:day:" + day,
                    "ai:quota:index:day:" + day, "ai:quota:index:chars:" + day),
                    Integer.toString(properties.getDailyRequestLimit()), Integer.toString(chars));
            if (accepted == null) throw new BusinessException(ErrorCode.AI_UNAVAILABLE);
            if (accepted != 1) throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS);
            return lease;
        } catch (RuntimeException error) {
            lease.close();
            if (error instanceof DataAccessException) throw new BusinessException(ErrorCode.AI_UNAVAILABLE);
            throw error;
        }
    }

    private void reserve(List<String> keys, String... arguments) {
        try {
            Long result = redis.execute(RESERVE, keys, (Object[]) arguments);
            if (result == null) throw new BusinessException(ErrorCode.AI_UNAVAILABLE);
            if (result == 0) throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS);
        } catch (DataAccessException failure) {
            throw new BusinessException(ErrorCode.AI_UNAVAILABLE);
        }
    }

    public final class Lease implements AutoCloseable {
        private final AtomicBoolean closed = new AtomicBoolean();
        @Override public void close() {
            if (closed.compareAndSet(false, true)) concurrent.release();
        }
    }
}
