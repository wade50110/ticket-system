package com.example.ticket.stock;

import com.example.ticket.order.OrderRepository;
import com.example.ticket.order.OrderStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/**
 * 限購額度對帳（v0.5-purchase-limit AC-12）：
 * DB 彙總值覆寫（修偏高與偏低）+ 刪除 DB 已無持有的 stale key。
 */
@ExtendWith(MockitoExtension.class)
class QuotaReconcileServiceTest {

    @Mock OrderRepository orderRepository;
    @Mock QuotaRedisRepository quotaRedis;

    @InjectMocks QuotaReconcileService reconcileService;

    private record Row(Long ticketId, Long userId, Long qty) implements OrderRepository.HeldQuantity {
        @Override public Long getTicketId() { return ticketId; }
        @Override public Long getUserId() { return userId; }
        @Override public Long getQty() { return qty; }
    }

    @Test
    void reconcile_overwritesFromDbAggregateAndDeletesStaleKeys() {
        when(orderRepository.aggregateHeldQuantities(List.of(OrderStatus.PENDING, OrderStatus.PAID)))
                .thenReturn(List.of(new Row(1L, 10L, 2L), new Row(2L, 20L, 1L)));

        String staleKey = QuotaRedisRepository.KEY_PREFIX + "9:99";
        Set<String> scanned = new HashSet<>(Set.of(
                QuotaRedisRepository.key(1L, 10L),
                QuotaRedisRepository.key(2L, 20L),
                staleKey));
        when(quotaRedis.scanKeys(QuotaRedisRepository.KEY_PREFIX + "*")).thenReturn(scanned);
        when(quotaRedis.deleteKeys(argThat(keys -> keys.size() == 1 && keys.contains(staleKey))))
                .thenReturn(1L);

        int updated = reconcileService.reconcile();

        // DB 有的兩筆被覆寫、stale 一筆被刪 → 共 3
        assertEquals(3, updated);
        verify(quotaRedis).set(1L, 10L, 2L);
        verify(quotaRedis).set(2L, 20L, 1L);
    }

    @Test
    void reconcile_noStaleKeys_onlyOverwrites() {
        when(orderRepository.aggregateHeldQuantities(List.of(OrderStatus.PENDING, OrderStatus.PAID)))
                .thenReturn(List.of(new Row(1L, 10L, 4L)));
        when(quotaRedis.scanKeys(QuotaRedisRepository.KEY_PREFIX + "*"))
                .thenReturn(new HashSet<>(Set.of(QuotaRedisRepository.key(1L, 10L))));
        when(quotaRedis.deleteKeys(argThat(Set::isEmpty))).thenReturn(0L);

        assertEquals(1, reconcileService.reconcile());
        verify(quotaRedis).set(1L, 10L, 4L);
    }
}
