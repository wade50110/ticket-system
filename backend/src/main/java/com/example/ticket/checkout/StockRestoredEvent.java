package com.example.ticket.checkout;

import java.util.List;

/**
 * 退票成功後發出的事件：Redis 已把庫存加回，請 listener 把對應數量同步「加回」DB tickets.stock。
 * 沿用 {@link StockChangedEvent.Change}，其中 quantity 一律為正數（要加回的張數）。
 */
public record StockRestoredEvent(Long orderId, List<StockChangedEvent.Change> changes) {
}
