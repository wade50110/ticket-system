package com.example.ticket.order;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    /** 限購額度彙總投影：某使用者對某票券的持有張數。 */
    interface HeldQuantity {
        Long getTicketId();
        Long getUserId();
        Long getQty();
    }

    /**
     * 依 (ticketId, userId) 彙總指定狀態訂單的持有張數。
     * 供限購額度的啟動重建（setIfAbsent）與 admin 對帳（覆寫）使用，statuses 一律傳 PENDING + PAID。
     */
    @Query("""
            SELECT oi.ticketId AS ticketId, o.userId AS userId, SUM(oi.quantity) AS qty
              FROM Order o JOIN o.items oi
             WHERE o.status IN :statuses
             GROUP BY oi.ticketId, o.userId
            """)
    List<HeldQuantity> aggregateHeldQuantities(@Param("statuses") Collection<OrderStatus> statuses);

    List<Order> findByUserIdOrderByIdDesc(Long userId);

    @EntityGraph(attributePaths = "items")
    Optional<Order> findWithItemsById(Long id);

    /**
     * 條件更新：只有當訂單目前為 PAID 才翻成 REFUNDED，回傳受影響筆數。
     * 讓「同一訂單並發退票」只有一個請求會成功（updated == 1），其餘拿到 0，藉此防重複退票。
     */
    @Modifying
    @Transactional
    @Query("""
            UPDATE Order o
               SET o.status = com.example.ticket.order.OrderStatus.REFUNDED,
                   o.refundedAt = :refundedAt,
                   o.refundTransactionId = :txn
             WHERE o.id = :id
               AND o.status = com.example.ticket.order.OrderStatus.PAID
            """)
    int markRefunded(@Param("id") Long id,
                     @Param("refundedAt") LocalDateTime refundedAt,
                     @Param("txn") String txn);
}
