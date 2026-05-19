package com.example.ticket.cart.dto;

import com.example.ticket.cart.CartItem;
import com.example.ticket.ticket.Ticket;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
public class CartItemResponse {

    private Long id;
    private Long ticketId;
    private String ticketName;
    private BigDecimal price;
    private Integer stock;
    private Integer quantity;
    private BigDecimal subtotal;

    public static CartItemResponse from(CartItem item, Ticket ticket) {
        BigDecimal subtotal = ticket.getPrice().multiply(BigDecimal.valueOf(item.getQuantity()));
        return new CartItemResponse(
                item.getId(),
                ticket.getId(),
                ticket.getName(),
                ticket.getPrice(),
                ticket.getStock(),
                item.getQuantity(),
                subtotal
        );
    }
}
