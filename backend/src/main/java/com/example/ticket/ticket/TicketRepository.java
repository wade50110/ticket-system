package com.example.ticket.ticket;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface TicketRepository extends JpaRepository<Ticket, Long> {

    @Query("""
            SELECT t FROM Ticket t
            WHERE (t.visibleAt IS NULL OR t.visibleAt <= :now)
              AND (t.visibleUntil IS NULL OR t.visibleUntil >= :now)
            ORDER BY t.id DESC
            """)
    List<Ticket> findVisibleAt(@Param("now") LocalDateTime now);

    /**
     * 異步同步用：把 DB stock 扣 quantity；條件帶 stock >= quantity 防扣成負數。
     * 真正的不超賣保證在 Redis Lua；這裡只是兜底寫入歷史值。
     */
    @Modifying
    @Query("UPDATE Ticket t SET t.stock = t.stock - :qty, t.updatedAt = CURRENT_TIMESTAMP WHERE t.id = :id AND t.stock >= :qty")
    int decrementStock(@Param("id") Long id, @Param("qty") int qty);

    /**
     * 退票用：把 DB stock 加回 quantity（不需 stock 條件）。
     * 真正的即時庫存以 Redis 為準，這裡只是兜底寫回歷史值。
     */
    @Modifying
    @Query("UPDATE Ticket t SET t.stock = t.stock + :qty, t.updatedAt = CURRENT_TIMESTAMP WHERE t.id = :id")
    int incrementStock(@Param("id") Long id, @Param("qty") int qty);
}
