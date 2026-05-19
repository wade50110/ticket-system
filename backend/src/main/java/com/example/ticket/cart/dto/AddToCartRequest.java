package com.example.ticket.cart.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AddToCartRequest {

    @NotNull
    private Long ticketId;

    @NotNull
    @Min(1)
    private Integer quantity;
}
