package com.example.ticket.checkout;

import com.example.ticket.cart.CartItem;
import com.example.ticket.cart.CartItemRepository;
import com.example.ticket.lock.DistributedLock;
import com.example.ticket.lock.LockHandle;
import com.example.ticket.lock.LockProperties;
import com.example.ticket.order.Order;
import com.example.ticket.order.OrderRepository;
import com.example.ticket.order.OrderStatus;
import com.example.ticket.order.dto.OrderResponse;
import com.example.ticket.payment.PaymentResult;
import com.example.ticket.payment.PaymentService;
import com.example.ticket.stock.QuotaRedisRepository;
import com.example.ticket.stock.StockRedisRepository;
import com.example.ticket.ticket.Ticket;
import com.example.ticket.ticket.TicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 結帳的限購分支（v0.5-purchase-limit AC-2、AC-3）。
 * Lua 腳本本身的原子性由 QuotaRedisRepositoryRedisTest 以真 Redis 驗證，
 * 這裡驗證的是 CheckoutService 對各回傳碼的處理與回滾對稱性。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CheckoutServiceTest {

    @Mock CartItemRepository cartRepo;
    @Mock TicketRepository ticketRepo;
    @Mock OrderRepository orderRepo;
    @Mock StockRedisRepository stockRedis;
    @Mock QuotaRedisRepository quotaRedis;
    @Mock DistributedLock distributedLock;
    @Mock LockProperties lockProps;
    @Mock PaymentService paymentService;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock LockHandle lockHandle;

    CheckoutService checkoutService;

    private static final long USER_ID = 10L;

    @BeforeEach
    void setUp() {
        checkoutService = new CheckoutService(cartRepo, ticketRepo, orderRepo, stockRedis,
                quotaRedis, distributedLock, lockProps, paymentService, eventPublisher);
        when(lockProps.waitTime()).thenReturn(java.time.Duration.ofMillis(200));
        when(lockProps.leaseTime()).thenReturn(java.time.Duration.ofMillis(3000));
        when(distributedLock.tryLock(anyString(), any(), any())).thenReturn(lockHandle);
        when(lockHandle.isLocked()).thenReturn(true);
    }

    private CartItem cartItem(long ticketId, int qty) {
        return CartItem.builder().id(ticketId * 100).userId(USER_ID).ticketId(ticketId).quantity(qty).build();
    }

    private Ticket ticket(long id, String name, Integer purchaseLimit) {
        return Ticket.builder().id(id).name(name).price(new BigDecimal("100.00"))
                .stock(10).purchaseLimit(purchaseLimit).build();
    }

    @Test
    void checkout_quotaExceededOnSecondTicket_rollsBackFirstStockAndQuota() {
        when(cartRepo.findByUserIdOrderByIdAsc(USER_ID))
                .thenReturn(List.of(cartItem(1L, 2), cartItem(2L, 1)));
        when(ticketRepo.findAllById(List.of(1L, 2L)))
                .thenReturn(List.of(ticket(1L, "無限票", null), ticket(2L, "限購票", 2)));
        when(quotaRedis.tryDecrementStockWithQuota(1L, USER_ID, 2, null)).thenReturn(8L);
        when(quotaRedis.tryDecrementStockWithQuota(2L, USER_ID, 1, 2))
                .thenReturn(QuotaRedisRepository.RESULT_QUOTA_EXCEEDED);
        when(quotaRedis.getHeld(2L, USER_ID)).thenReturn(2);

        CheckoutException ex = assertThrows(CheckoutException.class,
                () -> checkoutService.checkout(USER_ID));

        assertTrue(ex.getMessage().contains("超過限購數量"), ex.getMessage());
        assertTrue(ex.getMessage().contains("限購票"), ex.getMessage());
        assertTrue(ex.getMessage().contains("你還可購買 0 張"), ex.getMessage());

        // 已扣的第一張票：庫存與額度都要回補
        verify(stockRedis).increment(1L, 2);
        verify(quotaRedis).release(1L, USER_ID, 2);
        // 不建單、不付款、鎖全數釋放
        verify(orderRepo, never()).save(any());
        verify(paymentService, never()).charge(anyLong(), any());
        verify(lockHandle, times(2)).release();
    }

    @Test
    void checkout_insufficientStock_rollsBackQuotaOfPreviousTickets() {
        when(cartRepo.findByUserIdOrderByIdAsc(USER_ID))
                .thenReturn(List.of(cartItem(1L, 2), cartItem(2L, 3)));
        when(ticketRepo.findAllById(List.of(1L, 2L)))
                .thenReturn(List.of(ticket(1L, "票A", 4), ticket(2L, "票B", null)));
        when(quotaRedis.tryDecrementStockWithQuota(1L, USER_ID, 2, 4)).thenReturn(8L);
        when(quotaRedis.tryDecrementStockWithQuota(2L, USER_ID, 3, null))
                .thenReturn(StockRedisRepository.RESULT_INSUFFICIENT);
        when(stockRedis.get(2L)).thenReturn(1);

        CheckoutException ex = assertThrows(CheckoutException.class,
                () -> checkoutService.checkout(USER_ID));

        assertTrue(ex.getMessage().contains("庫存不足"), ex.getMessage());
        verify(stockRedis).increment(1L, 2);
        verify(quotaRedis).release(1L, USER_ID, 2);
    }

    @Test
    void checkout_success_passesPurchaseLimitToAtomicScript() {
        when(cartRepo.findByUserIdOrderByIdAsc(USER_ID)).thenReturn(List.of(cartItem(1L, 2)));
        when(ticketRepo.findAllById(List.of(1L))).thenReturn(List.of(ticket(1L, "限購票", 4)));
        when(quotaRedis.tryDecrementStockWithQuota(1L, USER_ID, 2, 4)).thenReturn(8L);
        when(orderRepo.save(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            o.setId(55L);
            return o;
        });
        when(paymentService.charge(eq(55L), any())).thenReturn(PaymentResult.ok("MOCK-1"));
        when(orderRepo.findWithItemsById(55L)).thenAnswer(inv -> {
            Order o = Order.builder().id(55L).orderNo("ORD-X").userId(USER_ID)
                    .totalAmount(new BigDecimal("200.00")).status(OrderStatus.PAID).build();
            return Optional.of(o);
        });

        OrderResponse resp = checkoutService.checkout(USER_ID);

        assertEquals(OrderStatus.PAID, resp.status());
        // 限購值必須傳進原子腳本（權威閘門在 Lua）
        verify(quotaRedis).tryDecrementStockWithQuota(1L, USER_ID, 2, 4);
        // 成功路徑不得回補
        verify(stockRedis, never()).increment(anyLong(), anyInt());
        verify(quotaRedis, never()).release(anyLong(), anyLong(), anyInt());
        verify(cartRepo).deleteByUserId(USER_ID);
        verify(eventPublisher).publishEvent(any(StockChangedEvent.class));
    }
}
