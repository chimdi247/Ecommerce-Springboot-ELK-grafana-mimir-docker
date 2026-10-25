package com.phegondev.Phegon.Eccormerce.ratelimit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Fixed-window counter in Redis, shared by every backend instance. INCR + EXPIRE run in one Lua
 * script so a crash between the two calls can never leave a key without a TTL.
 * Fails OPEN: if Redis is unavailable requests are allowed (availability over strictness).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimiter {

    private static final String LUA = """
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
            local ttl = redis.call('TTL', KEYS[1])
            if ttl < 0 then redis.call('EXPIRE', KEYS[1], ARGV[1]); ttl = tonumber(ARGV[1]) end
            return {count, ttl}
            """;

    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> SCRIPT = new DefaultRedisScript<>(LUA, List.class);

    private final StringRedisTemplate redis;

    public record Result(boolean allowed, long count, long limit, long retryAfterSeconds) {
        public long remaining() {
            return Math.max(0, limit - count);
        }
    }

    public Result tryAcquire(String key, int limit, int windowSeconds) {
        try {
            List<?> reply = redis.execute(SCRIPT, List.of(key), String.valueOf(windowSeconds));
            if (reply == null || reply.size() < 2) {
                return new Result(true, 0, limit, 0);
            }
            long count = ((Number) reply.get(0)).longValue();
            long ttl = ((Number) reply.get(1)).longValue();
            return new Result(count <= limit, count, limit, Math.max(ttl, 1));
        } catch (RuntimeException e) {
            log.warn("Rate limiter unavailable, allowing request: {}", e.toString());
            return new Result(true, 0, limit, 0);
        }
    }
}
