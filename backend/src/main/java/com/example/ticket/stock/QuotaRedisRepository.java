package com.example.ticket.stock;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 限購額度（每帳號每票券的目前持有數）的 Redis 正源操作。
 * key 格式：ticket:quota:{ticketId}:{userId}，值 = PENDING + PAID 訂單的持有張數。
 *
 * <p>規格見 docs/requirements/v0.5/purchase-limit.md，兩條鐵律：
 * <ul>
 *   <li>額度檢查與庫存扣減必須在同一支 Lua 內原子完成，否則同帳號併發結帳可繞過限購。</li>
 *   <li>額度的增減一律走 Lua：釋回對不存在的 key 不動作、扣到低於 0 夾至 0 ——
 *       quota 值在任何情況下不得為負（裸 DECRBY 會建出負值，等於送額度讓使用者超買）。</li>
 * </ul>
 */
@Repository
@RequiredArgsConstructor
public class QuotaRedisRepository {

    public static final String KEY_PREFIX = "ticket:quota:";

    /**
     * 額度 + 庫存原子扣減。
     * KEYS[1] = 庫存 key、KEYS[2] = 額度 key；ARGV[1] = 數量、ARGV[2] = 限購數（-1 = 不限）。
     * 回傳 -3 = 超過限購、-2 = 庫存 key 不存在、-1 = 庫存不足、>=0 = 扣完剩餘庫存。
     * 額度不論有無設限一律累計（之後 admin 才設限時，持有數已是正確值）。
     */
    private static final String DECR_WITH_QUOTA_LUA = """
            local v = redis.call('GET', KEYS[1])
            if not v then return -2 end
            local stock = tonumber(v)
            local qty = tonumber(ARGV[1])
            local limit = tonumber(ARGV[2])
            local held = tonumber(redis.call('GET', KEYS[2]) or '0')
            if limit >= 0 and held + qty > limit then return -3 end
            if stock < qty then return -1 end
            redis.call('DECRBY', KEYS[1], qty)
            redis.call('INCRBY', KEYS[2], qty)
            return stock - qty
            """;

    /**
     * 額度釋回（夾 0）：key 不存在不動作；釋回後 ≤0 直接刪除 key（避免零值 key 無 TTL 累積），回傳 0；
     * 否則 SET 新值。quota 值任何情況不得為負。
     */
    private static final String RELEASE_LUA = """
            local v = redis.call('GET', KEYS[1])
            if not v then return 0 end
            local nv = tonumber(v) - tonumber(ARGV[1])
            if nv <= 0 then
                redis.call('DEL', KEYS[1])
                return 0
            end
            redis.call('SET', KEYS[1], nv)
            return nv
            """;

    private static final RedisScript<Long> DECR_WITH_QUOTA_SCRIPT =
            new DefaultRedisScript<>(DECR_WITH_QUOTA_LUA, Long.class);
    private static final RedisScript<Long> RELEASE_SCRIPT =
            new DefaultRedisScript<>(RELEASE_LUA, Long.class);

    public static final long RESULT_QUOTA_EXCEEDED = -3L;
    /** 不限購時傳給 Lua 的哨兵值 */
    private static final String NO_LIMIT = "-1";

    private final StringRedisTemplate redis;

    public static String key(Long ticketId, Long userId) {
        return KEY_PREFIX + ticketId + ":" + userId;
    }

    /**
     * 原子執行「額度檢查 + 庫存扣減 + 額度累計」。
     * @param limit 該票券限購數；null = 不限購（仍會累計持有數）
     */
    public Long tryDecrementStockWithQuota(Long ticketId, Long userId, int quantity, Integer limit) {
        return redis.execute(DECR_WITH_QUOTA_SCRIPT,
                List.of(StockRedisRepository.key(ticketId), key(ticketId, userId)),
                String.valueOf(quantity),
                limit == null ? NO_LIMIT : String.valueOf(limit));
    }

    /** 釋回額度（退票 / 取消 / 結帳回滾用），夾 0、key 不存在不動作。 */
    public Long release(Long ticketId, Long userId, int quantity) {
        return redis.execute(RELEASE_SCRIPT,
                List.of(key(ticketId, userId)),
                String.valueOf(quantity));
    }

    /** 目前持有數；key 不存在視為 0（與扣減 Lua 同語意）。 */
    public int getHeld(Long ticketId, Long userId) {
        String v = redis.opsForValue().get(key(ticketId, userId));
        return v == null ? 0 : Integer.parseInt(v);
    }

    /** 啟動重建用：僅在 key 不存在時寫入（Redis 上的才是即時值，不可覆寫）。 */
    public Boolean setIfAbsent(Long ticketId, Long userId, long held) {
        return redis.opsForValue().setIfAbsent(key(ticketId, userId), String.valueOf(held));
    }

    /** 對帳用：無條件覆寫。只允許 admin 對帳流程呼叫。 */
    public void set(Long ticketId, Long userId, long held) {
        redis.opsForValue().set(key(ticketId, userId), String.valueOf(held));
    }

    /** SCAN（非 KEYS，避免阻塞）列出符合 pattern 的 quota key。 */
    public Set<String> scanKeys(String pattern) {
        Set<String> keys = new HashSet<>();
        try (Cursor<String> cursor = redis.scan(ScanOptions.scanOptions().match(pattern).count(500).build())) {
            cursor.forEachRemaining(keys::add);
        }
        return keys;
    }

    /** 刪除某票券的全部額度 key（票券刪除時呼叫），回傳刪除數。 */
    public long deleteByTicket(Long ticketId) {
        Set<String> keys = scanKeys(KEY_PREFIX + ticketId + ":*");
        if (keys.isEmpty()) return 0;
        Long n = redis.delete(keys);
        return n == null ? 0 : n;
    }

    /** 對帳用:刪除指定 key 集合，回傳刪除數。 */
    public long deleteKeys(Set<String> keys) {
        if (keys.isEmpty()) return 0;
        Long n = redis.delete(keys);
        return n == null ? 0 : n;
    }
}
