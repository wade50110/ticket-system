package com.example.ticket.checkout;

import com.example.ticket.cart.CartItem;
import com.example.ticket.cart.CartItemRepository;
import com.example.ticket.lock.DistributedLock;
import com.example.ticket.lock.LockHandle;
import com.example.ticket.lock.LockProperties;
import com.example.ticket.metrics.TicketMetrics;
import com.example.ticket.order.Order;
import com.example.ticket.order.OrderItem;
import com.example.ticket.order.OrderRepository;
import com.example.ticket.order.OrderService;
import com.example.ticket.order.OrderStatus;
import com.example.ticket.order.dto.OrderResponse;
import com.example.ticket.payment.PaymentResult;
import com.example.ticket.payment.PaymentService;
import com.example.ticket.stock.QuotaRedisRepository;
import com.example.ticket.stock.StockRedisRepository;
import com.example.ticket.ticket.Ticket;
import com.example.ticket.ticket.TicketRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CheckoutService {

    private final CartItemRepository cartRepo;
    private final TicketRepository ticketRepo;
    private final OrderRepository orderRepo;
    private final StockRedisRepository stockRedis;
    private final QuotaRedisRepository quotaRedis;
    private final DistributedLock distributedLock;
    private final LockProperties lockProps;
    private final PaymentService paymentService;
    private final ApplicationEventPublisher eventPublisher;
    private final TicketMetrics metrics;

    public OrderResponse checkout(Long userId) {
        // 1. 讀購物車
        List<CartItem> cartItems = cartRepo.findByUserIdOrderByIdAsc(userId);
        if (cartItems.isEmpty()) {
            throw new CheckoutException("購物車是空的");
        }

        // 2. 依 ticketId 升冪排序（避免多 key 死鎖）
        List<CartItem> sorted = new ArrayList<>(cartItems);
        sorted.sort(Comparator.comparing(CartItem::getTicketId));

        // 3. 預讀票券（驗證存在 + 抓 name/price 快照），一次 query
        List<Long> ticketIds = sorted.stream().map(CartItem::getTicketId).toList();
        Map<Long, Ticket> tickets = ticketRepo.findAllById(ticketIds).stream()
                .collect(Collectors.toMap(Ticket::getId, t -> t));
        for (CartItem c : sorted) {
            if (!tickets.containsKey(c.getTicketId())) {
                throw new CheckoutException("購物車含已刪除票券（id=" + c.getTicketId() + "），請移除");
            }
        }

        // 4. 依序加鎖
        List<LockHandle> acquired = new ArrayList<>();
        List<StockChangedEvent.Change> decremented = new ArrayList<>();
        try {
            for (CartItem c : sorted) {
                LockHandle h = distributedLock.tryLock(
                        "lock:ticket:" + c.getTicketId(),
                        lockProps.waitTime(),
                        lockProps.leaseTime()
                );
                if (!h.isLocked()) {
                    throw new CheckoutException("搶票人潮過多，請稍後再試", CheckoutException.Reason.LOCK_FAILED);
                }
                acquired.add(h);
            }

            // 5. Lua 原子扣減（額度檢查 + 庫存扣減 + 額度累計在同一支腳本內完成）
            for (CartItem c : sorted) {
                Ticket t = tickets.get(c.getTicketId());
                Long result = quotaRedis.tryDecrementStockWithQuota(
                        c.getTicketId(), userId, c.getQuantity(), t.getPurchaseLimit());
                if (result == null || result == StockRedisRepository.RESULT_KEY_MISSING) {
                    rollback(userId, decremented);
                    throw new CheckoutException("票券「" + t.getName() + "」已下架", CheckoutException.Reason.SOLD_OUT);
                }
                if (result == QuotaRedisRepository.RESULT_QUOTA_EXCEEDED) {
                    int held = quotaRedis.getHeld(c.getTicketId(), userId);
                    int remainingQuota = Math.max(t.getPurchaseLimit() - held, 0);
                    rollback(userId, decremented);
                    throw new CheckoutException(
                            "超過限購數量：「" + t.getName() + "」每人限購 " + t.getPurchaseLimit()
                                    + " 張，你還可購買 " + remainingQuota + " 張",
                            CheckoutException.Reason.QUOTA_EXCEEDED);
                }
                if (result == StockRedisRepository.RESULT_INSUFFICIENT) {
                    Integer remaining = stockRedis.get(c.getTicketId());
                    safeMetric(metrics::recordOversellStockInsufficient);
                    rollback(userId, decremented);
                    throw new CheckoutException(
                            "票券「" + t.getName()
                                    + "」庫存不足，剩餘 " + (remaining == null ? 0 : remaining) + " 張",
                            CheckoutException.Reason.SOLD_OUT);
                }
                decremented.add(new StockChangedEvent.Change(c.getTicketId(), c.getQuantity()));
            }

            // 6. 建立 PENDING 訂單
            Order order = createPendingOrder(userId, sorted, tickets);

            // 7. Mock 付款
            PaymentResult pay = paymentService.charge(order.getId(), order.getTotalAmount());
            if (!pay.success()) {
                rollback(userId, decremented);
                markOrderFailed(order);
                throw new CheckoutException("付款失敗：" + pay.message());
            }

            // 8. 標 PAID、清購物車、發事件
            markOrderPaid(order, pay.transactionId());
            cartRepo.deleteByUserId(userId);
            eventPublisher.publishEvent(new StockChangedEvent(order.getId(), decremented));

            return OrderResponse.from(orderRepo.findWithItemsById(order.getId()).orElseThrow());

        } catch (CheckoutException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("checkout unexpected error, userId={}", userId, e);
            rollback(userId, decremented);
            throw new CheckoutException("結帳發生例外，請稍後重試");
        } finally {
            // 9. 反向釋放鎖
            Collections.reverse(acquired);
            for (LockHandle h : acquired) {
                h.release();
            }
        }
    }

    @Transactional
    protected Order createPendingOrder(Long userId, List<CartItem> sorted, Map<Long, Ticket> tickets) {
        BigDecimal total = BigDecimal.ZERO;
        Order order = Order.builder()
                .orderNo(OrderService.generateOrderNo())
                .userId(userId)
                .totalAmount(BigDecimal.ZERO)
                .status(OrderStatus.PENDING)
                .build();
        for (CartItem c : sorted) {
            Ticket t = tickets.get(c.getTicketId());
            BigDecimal subtotal = t.getPrice().multiply(BigDecimal.valueOf(c.getQuantity()));
            OrderItem item = OrderItem.builder()
                    .ticketId(t.getId())
                    .ticketName(t.getName())
                    .unitPrice(t.getPrice())
                    .quantity(c.getQuantity())
                    .subtotal(subtotal)
                    .build();
            order.addItem(item);
            total = total.add(subtotal);
        }
        order.setTotalAmount(total);
        return orderRepo.save(order);
    }

    @Transactional
    protected void markOrderPaid(Order order, String transactionId) {
        order.setStatus(OrderStatus.PAID);
        order.setPaymentTransactionId(transactionId);
        order.setPaidAt(LocalDateTime.now());
        orderRepo.save(order);
    }

    @Transactional
    protected void markOrderFailed(Order order) {
        order.setStatus(OrderStatus.FAILED);
        orderRepo.save(order);
    }

    /**
     * 回滾已扣的庫存與已累計的限購額度。
     * 兩步非原子（先回庫存、再釋回額度）：中間態只會讓併發請求短暫誤判 409，方向保守不會超賣/超限。
     */
    /**
     * 指標埋點的呼叫端保護:即使 metrics 實作意外拋例外,也不得逸出到臨界區的
     * {@code catch(RuntimeException)} 而把 reason 退化成 OTHER、或多跑一次回滾(monitoring.md F-5)。
     * 與 {@link TicketMetrics} 內部的 safe 形成 defense-in-depth。
     */
    private static void safeMetric(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ignored) {
            // 指標失敗絕不影響結帳流程
        }
    }

    private void rollback(Long userId, List<StockChangedEvent.Change> decremented) {
        safeMetric(metrics::recordRollback);
        for (StockChangedEvent.Change c : decremented) {
            try {
                stockRedis.increment(c.ticketId(), c.quantity());
            } catch (Exception e) {
                log.error("rollback stock failed: ticketId={}, qty={}", c.ticketId(), c.quantity(), e);
            }
            try {
                quotaRedis.release(c.ticketId(), userId, c.quantity());
            } catch (Exception e) {
                log.error("rollback quota failed: ticketId={}, userId={}, qty={}",
                        c.ticketId(), userId, c.quantity(), e);
            }
        }
        decremented.clear();
    }
}
