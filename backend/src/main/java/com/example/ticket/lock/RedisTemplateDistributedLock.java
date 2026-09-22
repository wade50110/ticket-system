package com.example.ticket.lock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 手刻分散式鎖：SET key token NX PX leaseMillis + Lua 比對 token 後刪除。
 * 目的：以最少程式碼示範鎖的本質（唯一 token / TTL / 原子釋放）。
 */
@Slf4j
@RequiredArgsConstructor
public class RedisTemplateDistributedLock implements DistributedLock {

    private static final String RELEASE_LUA =
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end";

    private static final RedisScript<Long> RELEASE_SCRIPT =
            new DefaultRedisScript<>(RELEASE_LUA, Long.class);

    private static final long POLL_INTERVAL_MILLIS = 50L;

    private final StringRedisTemplate redis;

    @Override
    public LockHandle tryLock(String key, Duration waitTime, Duration leaseTime) {
        String token = UUID.randomUUID().toString();
        long deadline = System.nanoTime() + waitTime.toNanos();

        while (true) {
            Boolean ok = redis.opsForValue().setIfAbsent(key, token, leaseTime.toMillis(), TimeUnit.MILLISECONDS);
            if (Boolean.TRUE.equals(ok)) {
                return new Handle(redis, key, token, true);
            }
            if (System.nanoTime() >= deadline) {
                return new Handle(redis, key, token, false);
            }
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Handle(redis, key, token, false);
            }
        }
    }

    private record Handle(StringRedisTemplate redis, String key, String token, boolean locked) implements LockHandle {
        @Override
        public boolean isLocked() {
            return locked;
        }

        @Override
        public void release() {
            if (!locked) return;
            try {
                redis.execute(RELEASE_SCRIPT, List.of(key), token);
            } catch (Exception e) {
                log.warn("Manual unlock failed for {}: {}", key, e.getMessage());
            }
        }
    }
}
