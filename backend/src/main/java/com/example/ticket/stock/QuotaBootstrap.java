package com.example.ticket.stock;

import com.example.ticket.order.OrderRepository;
import com.example.ticket.order.OrderStatus;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 啟動時把限購額度（目前持有數）從 DB 重建進 Redis，僅當 key 不存在時。
 * 與 {@link StockBootstrap} 同一鐵律：Redis 是正源，已存在的 key 不可覆寫。
 * 注意 setIfAbsent 修不了「存在但值錯」的 key——那是 admin 對帳 API 的職責。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuotaBootstrap {

    private final OrderRepository orderRepository;
    private final QuotaRedisRepository quotaRedis;

    @PostConstruct
    public void loadQuotasIntoRedis() {
        List<OrderRepository.HeldQuantity> rows =
                orderRepository.aggregateHeldQuantities(List.of(OrderStatus.PENDING, OrderStatus.PAID));
        int loaded = 0;
        int skipped = 0;
        for (OrderRepository.HeldQuantity r : rows) {
            Boolean ok = quotaRedis.setIfAbsent(r.getTicketId(), r.getUserId(), r.getQty());
            if (Boolean.TRUE.equals(ok)) loaded++;
            else skipped++;
        }
        log.info("[QuotaBootstrap] loaded={}, skipped (key already exists)={}, total={}",
                loaded, skipped, rows.size());
    }
}
