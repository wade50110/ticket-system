package com.example.ticket.checkout;

import com.example.ticket.ticket.TicketRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 拆出來獨立 bean，讓 @Retryable / @Transactional proxy 在外部呼叫時生效
 * （StockSyncListener 內呼叫 this.method 會被 Spring AOP 略過）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockSyncWorker {

    private final TicketRepository ticketRepo;
    private final StockSyncFailedRepository failedRepo;

    @Retryable(
            retryFor = Exception.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 500, multiplier = 2.0)
    )
    @Transactional
    public void syncOne(Long orderId, Long ticketId, int quantity) {
        int updated = ticketRepo.decrementStock(ticketId, quantity);
        if (updated == 0) {
            throw new IllegalStateException("ticket not found or update failed, ticketId=" + ticketId);
        }
    }

    @Recover
    @Transactional
    public void recoverSync(Exception e, Long orderId, Long ticketId, int quantity) {
        log.warn("[StockSync] all retries failed, recording: orderId={}, ticketId={}, qty={}, err={}",
                orderId, ticketId, quantity, e.getMessage());
        failedRepo.save(StockSyncFailed.builder()
                .ticketId(ticketId)
                .delta(-quantity)
                .orderId(orderId)
                .retryCount(3)
                .lastError(truncate(e.getMessage(), 500))
                .build());
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
