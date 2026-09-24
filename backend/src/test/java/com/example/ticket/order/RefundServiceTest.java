package com.example.ticket.order;

import com.example.ticket.checkout.StockRestoredEvent;
import com.example.ticket.metrics.TicketMetrics;
import com.example.ticket.order.dto.OrderResponse;
import com.example.ticket.payment.PaymentResult;
import com.example.ticket.payment.PaymentService;
import com.example.ticket.stock.QuotaRedisRepository;
import com.example.ticket.stock.StockRedisRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RefundServiceTest {

    @Mock
    OrderRepository orderRepo;
    @Mock
    StockRedisRepository stockRedis;
    @Mock
    QuotaRedisRepository quotaRedis;
    @Mock
    PaymentService paymentService;
    @Mock
    ApplicationEventPublisher eventPublisher;

    RefundService refundService;
    SimpleMeterRegistry registry;
    TicketMetrics metrics;

    private static final long USER_ID = 10L;
    private static final long ORDER_ID = 1L;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new TicketMetrics(registry);
        refundService = new RefundService(orderRepo, stockRedis, quotaRedis, paymentService, eventPublisher, metrics);
    }

    private Order paidOrder() {
        Order order = Order.builder()
                .id(ORDER_ID)
                .orderNo("ORD-TEST-0001")
                .userId(USER_ID)
                .totalAmount(new BigDecimal("14400.00"))
                .status(OrderStatus.PAID)
                .paidAt(LocalDateTime.now())
                .build();
        order.addItem(OrderItem.builder()
                .ticketId(1L).ticketName("周杰倫").unitPrice(new BigDecimal("4800.00"))
                .quantity(2).subtotal(new BigDecimal("9600.00")).build());
        order.addItem(OrderItem.builder()
                .ticketId(3L).ticketName("五月天").unitPrice(new BigDecimal("2400.00"))
                .quantity(2).subtotal(new BigDecimal("4800.00")).build());
        return order;
    }

    @Test
    void refund_success_releasesStockToRedisAndPublishesEvent() {
        Order order = paidOrder();
        when(orderRepo.findWithItemsById(ORDER_ID)).thenReturn(Optional.of(order));
        when(paymentService.refund(eq(ORDER_ID), any())).thenReturn(PaymentResult.ok("MOCK-REFUND-x"));
        when(orderRepo.markRefunded(eq(ORDER_ID), any(LocalDateTime.class), eq("MOCK-REFUND-x"))).thenReturn(1);

        OrderResponse resp = refundService.refund(USER_ID, ORDER_ID);

        assertEquals(OrderStatus.REFUNDED, resp.status());
        assertNotNull(resp.refundedAt());
        assertEquals("MOCK-REFUND-x", resp.refundTransactionId());

        // 每個品項的庫存都要加回 Redis
        verify(stockRedis).increment(1L, 2);
        verify(stockRedis).increment(3L, 2);

        // 每個品項的限購額度都要釋回（v0.5-purchase-limit AC-7）
        verify(quotaRedis).release(1L, USER_ID, 2);
        verify(quotaRedis).release(3L, USER_ID, 2);

        // 應發出 DB 回寫事件，且帶兩筆變更
        ArgumentCaptor<StockRestoredEvent> captor = ArgumentCaptor.forClass(StockRestoredEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertEquals(ORDER_ID, captor.getValue().orderId());
        assertEquals(2, captor.getValue().changes().size());

        // 退票成功計數 +1（monitoring.md F-3）
        assertEquals(1.0, registry.get("ticket_refund_total").tag("result", "success").counter().count(), 0.0001);
    }

    @Test
    void refund_notOwner_throwsAndDoesNothing() {
        Order order = paidOrder();
        when(orderRepo.findWithItemsById(ORDER_ID)).thenReturn(Optional.of(order));

        assertThrows(IllegalArgumentException.class,
                () -> refundService.refund(999L, ORDER_ID));

        verify(paymentService, never()).refund(anyLong(), any());
        verify(orderRepo, never()).markRefunded(anyLong(), any(), any());
        verify(stockRedis, never()).increment(anyLong(), anyInt());
        verify(quotaRedis, never()).release(anyLong(), anyLong(), anyInt());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void refund_orderNotFound_throws() {
        when(orderRepo.findWithItemsById(ORDER_ID)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> refundService.refund(USER_ID, ORDER_ID));
    }

    @Test
    void refund_notPaid_throwsRefundException() {
        Order order = paidOrder();
        order.setStatus(OrderStatus.REFUNDED); // 已退票
        when(orderRepo.findWithItemsById(ORDER_ID)).thenReturn(Optional.of(order));

        assertThrows(RefundException.class,
                () -> refundService.refund(USER_ID, ORDER_ID));

        verify(paymentService, never()).refund(anyLong(), any());
        verify(stockRedis, never()).increment(anyLong(), anyInt());
        // 退票失敗計數 +1（monitoring.md F-3）
        assertEquals(1.0, registry.get("ticket_refund_total").tag("result", "fail").counter().count(), 0.0001);
    }

    @Test
    void refund_lostConcurrentRace_markRefundedReturnsZero_throwsAndDoesNotReleaseStock() {
        Order order = paidOrder();
        when(orderRepo.findWithItemsById(ORDER_ID)).thenReturn(Optional.of(order));
        when(paymentService.refund(eq(ORDER_ID), any())).thenReturn(PaymentResult.ok("MOCK-REFUND-y"));
        when(orderRepo.markRefunded(eq(ORDER_ID), any(LocalDateTime.class), eq("MOCK-REFUND-y"))).thenReturn(0);

        assertThrows(RefundException.class,
                () -> refundService.refund(USER_ID, ORDER_ID));

        // 沒搶到狀態翻轉，就不可以釋放庫存 / 釋回額度 / 發回寫事件
        verify(stockRedis, never()).increment(anyLong(), anyInt());
        verify(quotaRedis, never()).release(anyLong(), anyLong(), anyInt());
        verify(eventPublisher, never()).publishEvent(any());
    }
}
