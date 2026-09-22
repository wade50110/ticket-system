package com.example.ticket.order.dto;

import com.example.ticket.order.Order;
import com.example.ticket.order.OrderStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record OrderResponse(
        Long orderId,
        String orderNo,
        OrderStatus status,
        BigDecimal totalAmount,
        String paymentTransactionId,
        LocalDateTime createdAt,
        LocalDateTime paidAt,
        LocalDateTime refundedAt,
        String refundTransactionId,
        List<OrderItemResponse> items
) {
    public static OrderResponse from(Order order) {
        List<OrderItemResponse> items = order.getItems() == null ? List.of()
                : order.getItems().stream().map(OrderItemResponse::from).toList();
        return new OrderResponse(
                order.getId(),
                order.getOrderNo(),
                order.getStatus(),
                order.getTotalAmount(),
                order.getPaymentTransactionId(),
                order.getCreatedAt(),
                order.getPaidAt(),
                order.getRefundedAt(),
                order.getRefundTransactionId(),
                items
        );
    }

    public static OrderResponse summary(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getOrderNo(),
                order.getStatus(),
                order.getTotalAmount(),
                order.getPaymentTransactionId(),
                order.getCreatedAt(),
                order.getPaidAt(),
                order.getRefundedAt(),
                order.getRefundTransactionId(),
                List.of()
        );
    }
}
