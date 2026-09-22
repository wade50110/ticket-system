package com.example.ticket.order.dto;

import com.example.ticket.order.OrderItem;

import java.math.BigDecimal;

public record OrderItemResponse(
        Long ticketId,
        String ticketName,
        BigDecimal unitPrice,
        Integer quantity,
        BigDecimal subtotal
) {
    public static OrderItemResponse from(OrderItem item) {
        return new OrderItemResponse(
                item.getTicketId(),
                item.getTicketName(),
                item.getUnitPrice(),
                item.getQuantity(),
                item.getSubtotal()
        );
    }
}
