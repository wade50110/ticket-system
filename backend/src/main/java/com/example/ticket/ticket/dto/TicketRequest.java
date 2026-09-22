package com.example.ticket.ticket.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class TicketRequest {

    @NotBlank
    @Size(max = 200)
    private String name;

    @Size(max = 2000)
    private String description;

    @NotNull
    @DecimalMin(value = "0.00", inclusive = true)
    private BigDecimal price;

    @NotNull
    @Min(0)
    private Integer stock;

    /** 每帳號限購數；null = 不限購，不接受 0 或負數 */
    @Min(1)
    private Integer purchaseLimit;

    private LocalDateTime visibleAt;

    private LocalDateTime visibleUntil;
}
