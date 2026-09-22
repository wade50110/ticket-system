package com.example.ticket.stock;

import com.example.ticket.order.OrderRepository;
import com.example.ticket.order.OrderStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * 限購額度啟動重建（v0.5-purchase-limit AC-10）:
 * 以 DB orders(PENDING+PAID)彙總,用 setIfAbsent 寫入 Redis(已存在的 key 不覆寫)。
 */
@ExtendWith(MockitoExtension.class)
class QuotaBootstrapTest {

    @Mock OrderRepository orderRepository;
    @Mock QuotaRedisRepository quotaRedis;

    @InjectMocks QuotaBootstrap quotaBootstrap;

    private record Row(Long ticketId, Long userId, Long qty) implements OrderRepository.HeldQuantity {
        @Override public Long getTicketId() { return ticketId; }
        @Override public Long getUserId() { return userId; }
        @Override public Long getQty() { return qty; }
    }

    @Test
    void loadQuotasIntoRedis_usesSetIfAbsentWithAggregatedHeldQuantities() {
        when(orderRepository.aggregateHeldQuantities(List.of(OrderStatus.PENDING, OrderStatus.PAID)))
                .thenReturn(List.of(new Row(1L, 10L, 2L), new Row(2L, 20L, 5L)));

        quotaBootstrap.loadQuotasIntoRedis();

        // 用彙總值 setIfAbsent（不覆寫既有 key）
        verify(quotaRedis).setIfAbsent(1L, 10L, 2L);
        verify(quotaRedis).setIfAbsent(2L, 20L, 5L);
        // 絕不可用無條件 set 洗掉 Redis 即時值
        verify(quotaRedis, never()).set(anyLong(), anyLong(), anyLong());
    }

    @Test
    void loadQuotasIntoRedis_emptyDb_doesNothing() {
        when(orderRepository.aggregateHeldQuantities(List.of(OrderStatus.PENDING, OrderStatus.PAID)))
                .thenReturn(List.of());

        quotaBootstrap.loadQuotasIntoRedis();

        verify(quotaRedis, never()).setIfAbsent(anyLong(), anyLong(), anyLong());
    }
}
