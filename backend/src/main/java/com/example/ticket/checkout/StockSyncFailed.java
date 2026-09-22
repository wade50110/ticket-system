package com.example.ticket.checkout;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "stock_sync_failed", indexes = {
        @Index(name = "idx_ssf_unresolved", columnList = "resolved_at")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockSyncFailed {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ticket_id", nullable = false)
    private Long ticketId;

    /** 負數=扣，正數=加（目前 v0.3 都是負數，但保留欄位語意） */
    @Column(nullable = false)
    private Integer delta;

    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (retryCount == null) retryCount = 0;
    }
}
