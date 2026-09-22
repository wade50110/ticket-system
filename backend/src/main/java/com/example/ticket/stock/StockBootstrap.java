package com.example.ticket.stock;

import com.example.ticket.ticket.TicketRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class StockBootstrap {

    private final TicketRepository ticketRepository;
    private final StockRedisRepository stockRedis;

    /**
     * 啟動時把 DB tickets.stock 載入 Redis，僅當 key 不存在時（避免覆蓋 Redis 即時值）。
     * Redis 是正源；DB 重啟後若 Redis 還在，不能用 DB 數字洗掉它。
     */
    @PostConstruct
    public void loadStocksIntoRedis() {
        var tickets = ticketRepository.findAll();
        int loaded = 0;
        int skipped = 0;
        for (var t : tickets) {
            Boolean ok = stockRedis.setIfAbsent(t.getId(), t.getStock());
            if (Boolean.TRUE.equals(ok)) loaded++;
            else skipped++;
        }
        log.info("[StockBootstrap] loaded={}, skipped (key already exists)={}, total={}",
                loaded, skipped, tickets.size());
    }
}
