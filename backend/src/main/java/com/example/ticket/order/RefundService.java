package com.example.ticket.order;

import com.example.ticket.checkout.StockChangedEvent;
import com.example.ticket.checkout.StockRestoredEvent;
import com.example.ticket.order.dto.OrderResponse;
import com.example.ticket.payment.PaymentResult;
import com.example.ticket.payment.PaymentService;
import com.example.ticket.stock.QuotaRedisRepository;
import com.example.ticket.stock.StockRedisRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 訂單退票：釋放庫存回 Redis（正源）、Mock 退款、狀態轉 REFUNDED、非同步把庫存加回 DB。
 *
 * <p>設計重點：
 * <ul>
 *   <li>用 {@link OrderRepository#markRefunded} 的條件更新（WHERE status = PAID）做原子狀態翻轉，
 *       只有搶到那次翻轉的請求才會釋放庫存，避免同一訂單被重複退票、庫存被重複加回。</li>
 *   <li>Redis 為庫存正源，先加回 Redis；DB 透過 {@link StockRestoredEvent} 非同步回寫，
 *       失敗會落到 stock_sync_failed 由排程補償（與結帳扣減同一套機制）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundService {

    private final OrderRepository orderRepo;
    private final StockRedisRepository stockRedis;
    private final QuotaRedisRepository quotaRedis;
    private final PaymentService paymentService;
    private final ApplicationEventPublisher eventPublisher;

    public OrderResponse refund(Long userId, Long orderId) {
        Order order = orderRepo.findWithItemsById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("訂單不存在"));

        // 僅本人可退（訊息同「不存在」，不洩漏他人訂單是否存在）
        if (!order.getUserId().equals(userId)) {
            throw new IllegalArgumentException("訂單不存在");
        }
        if (order.getStatus() != OrderStatus.PAID) {
            throw new RefundException("只有已付款的訂單可以退票");
        }

        // 1. Mock 退款（無實際副作用，僅產生交易序號）
        PaymentResult refund = paymentService.refund(orderId, order.getTotalAmount());
        if (!refund.success()) {
            throw new RefundException("退款失敗：" + refund.message());
        }

        LocalDateTime now = LocalDateTime.now();

        // 2. 原子狀態翻轉：PAID -> REFUNDED。updated == 0 代表被別的請求先退掉了。
        int updated = orderRepo.markRefunded(orderId, now, refund.transactionId());
        if (updated == 0) {
            throw new RefundException("訂單已退票或目前狀態無法退票");
        }

        // 3. Redis 立即釋放庫存（正源），並釋回限購額度（Lua 夾 0，不得產生負值）
        List<StockChangedEvent.Change> changes = new ArrayList<>();
        for (OrderItem item : order.getItems()) {
            try {
                stockRedis.increment(item.getTicketId(), item.getQuantity());
            } catch (Exception e) {
                // Redis 加回失敗只記錄，不阻斷退票（款已退、狀態已改）；DB 回寫事件仍會發出
                log.error("退票還原 Redis 庫存失敗 orderId={} ticketId={}", orderId, item.getTicketId(), e);
            }
            try {
                quotaRedis.release(item.getTicketId(), order.getUserId(), item.getQuantity());
            } catch (Exception e) {
                // 釋回失敗只記錄（額度偏高、對使用者保守誤擋），由 admin 對帳 API 收斂
                log.error("退票釋回限購額度失敗 orderId={} ticketId={} userId={}",
                        orderId, item.getTicketId(), order.getUserId(), e);
            }
            changes.add(new StockChangedEvent.Change(item.getTicketId(), item.getQuantity()));
        }

        // 4. 非同步把庫存加回 DB（失敗走 stock_sync_failed 補償）
        eventPublisher.publishEvent(new StockRestoredEvent(orderId, changes));

        // 5. 更新記憶體中的 detached 物件供回應（markRefunded 是 bulk update，不會回填此物件）
        order.setStatus(OrderStatus.REFUNDED);
        order.setRefundedAt(now);
        order.setRefundTransactionId(refund.transactionId());
        log.info("[REFUND] orderId={} userId={} amount={} txn={}",
                orderId, userId, order.getTotalAmount(), refund.transactionId());
        return OrderResponse.from(order);
    }
}
