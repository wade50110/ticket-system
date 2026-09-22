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
 * 退票的 DB 庫存「加回」，對稱於 {@link StockSyncWorker}（扣減）。
 * 獨立成一個 bean，讓 @Retryable / @Recover 的 proxy 生效，且與扣減用的 @Recover 不會因相同簽章而衝突。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockRestoreWorker {

    private final TicketRepository ticketRepo;
    private final StockSyncFailedRepository failedRepo;

    @Retryable(
            retryFor = Exception.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 500, multiplier = 2.0)
    )
    @Transactional
    public void restoreOne(Long orderId, Long ticketId, int quantity) {
        int updated = ticketRepo.incrementStock(ticketId, quantity);
        if (updated == 0) {
            throw new IllegalStateException("ticket not found or update failed, ticketId=" + ticketId);
        }
    }

    @Recover
    @Transactional
    public void recoverRestore(Exception e, Long orderId, Long ticketId, int quantity) {
        log.warn("[StockRestore] all retries failed, recording: orderId={}, ticketId={}, qty={}, err={}",
                orderId, ticketId, quantity, e.getMessage());
        failedRepo.save(StockSyncFailed.builder()
                .ticketId(ticketId)
                .delta(quantity)   // 正數 = 要加回 DB 的張數（排程 retry 會依 delta 正負決定加/扣）
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
