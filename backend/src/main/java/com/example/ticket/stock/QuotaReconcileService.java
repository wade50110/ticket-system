package com.example.ticket.stock;

import com.example.ticket.order.OrderRepository;
import com.example.ticket.order.OrderStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 限購額度對帳：以 DB orders（PENDING + PAID）彙總值覆寫全部 quota key。
 * 這是額度錯誤值（釋回失敗、崩潰窗口、人為誤刪造成的偏高/偏低）的唯一收斂機制——
 * setIfAbsent 式的啟動重建修不了「存在但值錯」的 key。
 * 維護用、admin 手動觸發（比照 /api/admin/stock-sync/retry 的風格）。
 *
 * <p><b>⚠️ 僅可於無結帳流量的維護窗口執行。</b>本方法讀 DB 彙總快照後無條件覆寫 Redis，
 * 未與結帳共用 per-ticket 鎖；若在有併發結帳時執行，可能把「讀快照之後才成立的扣減」蓋掉，
 * 造成該帳號永久超限。因它被定位為異常修復工具、正常不需呼叫，故採「維護窗口執行」的約束
 * 而非引入全表鎖（對維護工具不划算）。若未來要線上熱對帳，需改為對每個 ticket 取結帳同一把鎖。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuotaReconcileService {

    private final OrderRepository orderRepository;
    private final QuotaRedisRepository quotaRedis;

    public int reconcile() {
        List<OrderRepository.HeldQuantity> rows =
                orderRepository.aggregateHeldQuantities(List.of(OrderStatus.PENDING, OrderStatus.PAID));

        // 1. 以 DB 彙總值覆寫（修偏低與偏高）
        Set<String> expected = new HashSet<>();
        for (OrderRepository.HeldQuantity r : rows) {
            quotaRedis.set(r.getTicketId(), r.getUserId(), r.getQty());
            expected.add(QuotaRedisRepository.key(r.getTicketId(), r.getUserId()));
        }

        // 2. Redis 上存在、但 DB 已無持有的 key（例如全退光了）直接刪除
        Set<String> stale = quotaRedis.scanKeys(QuotaRedisRepository.KEY_PREFIX + "*");
        stale.removeAll(expected);
        long deleted = quotaRedis.deleteKeys(stale);

        int updated = rows.size() + (int) deleted;
        log.info("[QuotaReconcile] set={}, deletedStale={}, updated={}", rows.size(), deleted, updated);
        return updated;
    }
}
