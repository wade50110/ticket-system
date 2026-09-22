package com.example.ticket.checkout;

import java.util.List;

/**
 * 結帳成功後發出的事件：Redis 已扣減，請 listener 把對應數量同步回 DB tickets.stock。
 */
public record StockChangedEvent(Long orderId, List<Change> changes) {

    public record Change(Long ticketId, int quantity) {}
}
