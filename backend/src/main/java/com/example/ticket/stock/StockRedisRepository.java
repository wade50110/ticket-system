package com.example.ticket.stock;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class StockRedisRepository {

    public static final String KEY_PREFIX = "ticket:stock:";

    /**
     * 原子扣減：
     * 回傳 -2 = key 不存在（票券不存在 / 未上架到 Redis）
     * 回傳 -1 = 庫存不足
     * 回傳 >=0 = 扣完剩餘庫存
     */
    private static final String DECR_LUA = """
            local v = redis.call('GET', KEYS[1])
            if not v then return -2 end
            local stock = tonumber(v)
            local qty = tonumber(ARGV[1])
            if stock < qty then return -1 end
            return redis.call('DECRBY', KEYS[1], qty)
            """;

    private static final RedisScript<Long> DECR_SCRIPT = new DefaultRedisScript<>(DECR_LUA, Long.class);

    public static final long RESULT_KEY_MISSING = -2L;
    public static final long RESULT_INSUFFICIENT = -1L;

    private final StringRedisTemplate redis;

    public static String key(Long ticketId) {
        return KEY_PREFIX + ticketId;
    }

    public Long tryDecrement(Long ticketId, int quantity) {
        return redis.execute(DECR_SCRIPT, List.of(key(ticketId)), String.valueOf(quantity));
    }

    public void increment(Long ticketId, int quantity) {
        redis.opsForValue().increment(key(ticketId), quantity);
    }

    public Integer get(Long ticketId) {
        String v = redis.opsForValue().get(key(ticketId));
        return v == null ? null : Integer.valueOf(v);
    }

    public void set(Long ticketId, int stock) {
        redis.opsForValue().set(key(ticketId), String.valueOf(stock));
    }

    public Boolean setIfAbsent(Long ticketId, int stock) {
        return redis.opsForValue().setIfAbsent(key(ticketId), String.valueOf(stock));
    }

    public void delete(Long ticketId) {
        redis.delete(key(ticketId));
    }
}
