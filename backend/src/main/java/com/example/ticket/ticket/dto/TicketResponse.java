package com.example.ticket.ticket.dto;

import com.example.ticket.ticket.Ticket;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class TicketResponse {

    private Long id;
    private String name;
    private String description;
    private BigDecimal price;
    private Integer stock;
    private LocalDateTime visibleAt;
    private LocalDateTime visibleUntil;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static TicketResponse from(Ticket t) {
        return new TicketResponse(
                t.getId(),
                t.getName(),
                t.getDescription(),
                t.getPrice(),
                t.getStock(),
                t.getVisibleAt(),
                t.getVisibleUntil(),
                t.getCreatedAt(),
                t.getUpdatedAt()
        );
    }
}
