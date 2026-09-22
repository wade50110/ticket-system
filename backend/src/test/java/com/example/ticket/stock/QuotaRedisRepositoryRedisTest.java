package com.example.ticket.stock;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 限購 Lua 腳本的整合測試（v0.5-purchase-limit AC-2、AC-4、AC-5、AC-6、AC-11）。
 * 需要本機 Redis（localhost:6380，docker compose 的 ticket-redis）；未啟動時整類跳過。
 * 使用 db 15 與應用資料（db 0）完全隔離，每個測試前 flush。
 */
class QuotaRedisRepositoryRedisTest {

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private static QuotaRedisRepository quotaRepo;
    private static StockRedisRepository stockRepo;

    private static final long TICKET = 990001L;
    private static final long OTHER_TICKET = 990002L;
    private static final long USER_A = 88001L;
    private static final long USER_B = 88002L;

    @BeforeAll
    static void connect() {
        RedisStandaloneConfiguration cfg = new RedisStandaloneConfiguration("localhost", 6380);
        cfg.setDatabase(15);
        factory = new LettuceConnectionFactory(cfg);
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        boolean available;
        try {
            available = "PONG".equalsIgnoreCase(redis.getConnectionFactory().getConnection().ping());
        } catch (Exception e) {
            available = false;
        }
        Assumptions.assumeTrue(available, "本機 Redis(localhost:6380)未啟動，跳過整合測試");
        quotaRepo = new QuotaRedisRepository(redis);
        stockRepo = new StockRedisRepository(redis);
    }

    @AfterAll
    static void close() {
        if (factory != null) factory.destroy();
    }

    @BeforeEach
    void flushTestDb() {
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    // ---- 扣減 Lua ----

    @Test
    void decrement_withoutLimit_decrementsStockAndTracksQuota() {
        stockRepo.set(TICKET, 10);
        Long result = quotaRepo.tryDecrementStockWithQuota(TICKET, USER_A, 3, null);
        assertEquals(7L, result);
        assertEquals(7, stockRepo.get(TICKET));
        // 未設限也要累計持有數（之後才設限時持有數已正確）
        assertEquals(3, quotaRepo.getHeld(TICKET, USER_A));
    }

    @Test
    void decrement_exactlyAtLimit_succeeds_thenOneMoreRejected() {
        stockRepo.set(TICKET, 10);
        assertEquals(8L, quotaRepo.tryDecrementStockWithQuota(TICKET, USER_A, 2, 4));
        assertEquals(6L, quotaRepo.tryDecrementStockWithQuota(TICKET, USER_A, 2, 4)); // 剛好買滿
        assertEquals(4, quotaRepo.getHeld(TICKET, USER_A));

        Long rejected = quotaRepo.tryDecrementStockWithQuota(TICKET, USER_A, 1, 4);
        assertEquals(QuotaRedisRepository.RESULT_QUOTA_EXCEEDED, rejected);
        // 被拒時庫存與額度都不得變動
        assertEquals(6, stockRepo.get(TICKET));
        assertEquals(4, quotaRepo.getHeld(TICKET, USER_A));
    }

    @Test
    void decrement_overLimit_changesNothing() {
        stockRepo.set(TICKET, 10);
        Long result = quotaRepo.tryDecrementStockWithQuota(TICKET, USER_A, 3, 2);
        assertEquals(QuotaRedisRepository.RESULT_QUOTA_EXCEEDED, result);
        assertEquals(10, stockRepo.get(TICKET));
        assertEquals(0, quotaRepo.getHeld(TICKET, USER_A));
    }

    @Test
    void decrement_stockKeyMissing_returnsMissingAndNoQuotaKey() {
        Long result = quotaRepo.tryDecrementStockWithQuota(TICKET, USER_A, 1, 4);
        assertEquals(StockRedisRepository.RESULT_KEY_MISSING, result);
        assertFalse(Boolean.TRUE.equals(redis.hasKey(QuotaRedisRepository.key(TICKET, USER_A))));
    }

    @Test
    void decrement_insufficientStock_quotaUnchanged() {
        stockRepo.set(TICKET, 1);
        Long result = quotaRepo.tryDecrementStockWithQuota(TICKET, USER_A, 2, 10);
        assertEquals(StockRedisRepository.RESULT_INSUFFICIENT, result);
        assertEquals(1, stockRepo.get(TICKET));
        assertEquals(0, quotaRepo.getHeld(TICKET, USER_A));
    }

    // ---- 釋回 Lua（AC-11：任何情況不得為負）----

    @Test
    void release_normal_decreasesHeld() {
        quotaRepo.set(TICKET, USER_A, 4);
        assertEquals(3L, quotaRepo.release(TICKET, USER_A, 1));
        assertEquals(3, quotaRepo.getHeld(TICKET, USER_A));
    }

    @Test
    void release_missingKey_noopAndNoNegativeKeyCreated() {
        assertEquals(0L, quotaRepo.release(TICKET, USER_A, 5));
        assertFalse(Boolean.TRUE.equals(redis.hasKey(QuotaRedisRepository.key(TICKET, USER_A))));
    }

    @Test
    void release_moreThanHeld_clampsToZeroAndDeletesKey() {
        quotaRepo.set(TICKET, USER_A, 1);
        assertEquals(0L, quotaRepo.release(TICKET, USER_A, 5));
        assertEquals(0, quotaRepo.getHeld(TICKET, USER_A));
        // 釋回到 0 應刪 key,避免零值 key 無 TTL 累積
        assertFalse(Boolean.TRUE.equals(redis.hasKey(QuotaRedisRepository.key(TICKET, USER_A))));
    }

    @Test
    void release_exactlyToZero_deletesKey() {
        quotaRepo.set(TICKET, USER_A, 2);
        assertEquals(0L, quotaRepo.release(TICKET, USER_A, 2));
        assertFalse(Boolean.TRUE.equals(redis.hasKey(QuotaRedisRepository.key(TICKET, USER_A))));
    }

    // ---- setIfAbsent（啟動重建語意，AC-10）----

    @Test
    void setIfAbsent_onlyWritesWhenKeyMissing() {
        // key 不存在 → 寫入成功
        assertTrue(quotaRepo.setIfAbsent(TICKET, USER_A, 3));
        assertEquals(3, quotaRepo.getHeld(TICKET, USER_A));
        // key 已存在 → 不覆寫（Redis 即時值優先）
        assertFalse(quotaRepo.setIfAbsent(TICKET, USER_A, 99));
        assertEquals(3, quotaRepo.getHeld(TICKET, USER_A));
    }

    // ---- 併發（AC-5 同帳號不超限、AC-6 跨帳號隔離）----

    @Test
    void concurrent_sameUser_neverExceedsLimit() throws Exception {
        stockRepo.set(TICKET, 100);
        int threads = 10;
        int limit = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger successes = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    Long r = quotaRepo.tryDecrementStockWithQuota(TICKET, USER_A, 1, limit);
                    if (r != null && r >= 0) successes.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS), "併發測試逾時");
        pool.shutdown();

        assertEquals(limit, successes.get(), "同帳號併發下成功數必須恰等於限購數");
        assertEquals(limit, quotaRepo.getHeld(TICKET, USER_A));
        assertEquals(100 - limit, stockRepo.get(TICKET));

        // 跨帳號隔離：A 買滿不影響 B
        assertEquals(100 - limit - 2, quotaRepo.tryDecrementStockWithQuota(TICKET, USER_B, 2, limit));
        assertEquals(2, quotaRepo.getHeld(TICKET, USER_B));
    }

    // ---- key 管理 ----

    @Test
    void deleteByTicket_removesOnlyThatTicketsQuotaKeys() {
        quotaRepo.set(TICKET, USER_A, 2);
        quotaRepo.set(TICKET, USER_B, 1);
        quotaRepo.set(OTHER_TICKET, USER_A, 3);

        assertEquals(2, quotaRepo.deleteByTicket(TICKET));

        assertEquals(0, quotaRepo.getHeld(TICKET, USER_A));
        assertEquals(0, quotaRepo.getHeld(TICKET, USER_B));
        assertEquals(3, quotaRepo.getHeld(OTHER_TICKET, USER_A));
    }

    @Test
    void scanKeys_findsQuotaKeysByPattern() {
        quotaRepo.set(TICKET, USER_A, 2);
        quotaRepo.set(OTHER_TICKET, USER_B, 1);
        Set<String> keys = quotaRepo.scanKeys(QuotaRedisRepository.KEY_PREFIX + "*");
        assertEquals(2, keys.size());
        assertTrue(keys.contains(QuotaRedisRepository.key(TICKET, USER_A)));
        assertTrue(keys.contains(QuotaRedisRepository.key(OTHER_TICKET, USER_B)));
    }
}
