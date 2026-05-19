package com.example.ticket.ticket;

import org.springframework.data.jpa.repository.JpaRepository;
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
}
