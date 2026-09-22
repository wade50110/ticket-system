package com.example.ticket.checkout;

import com.example.ticket.ticket.TicketRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 庫存回寫失敗記錄的批次重試。
 *
 * <p>兩個刻意的設計:
 * <ul>
 *   <li><b>抽成獨立 bean</b>:{@code @Transactional} 只在經 Spring AOP proxy 的外部呼叫時生效。
 *       原本 {@code StockSyncListener} 內 {@code this.retryBatch()} 同類別自呼叫,proxy 被略過、
 *       交易不生效(既有 bug)。由 listener 注入後外部呼叫,交易才會真的生效。</li>
 *   <li><b>逐筆獨立交易(REQUIRES_NEW)</b>:若把整批放在一個交易裡,任一筆 {@code decrementStock}
 *       拋 DB 例外(連線中斷/deadlock/timeout)會把交易標成 rollback-only,commit 時整批(含已成功
 *       設 {@code resolvedAt} 的)全部回滾。改成每筆一個交易,一筆壞只回滾那一筆,不污染同批其他筆。
 *       {@code retryOne} 必須經 proxy 呼叫 REQUIRES_NEW 才生效,故透過 {@code @Lazy} 注入自身 proxy。</li>
 * </ul>
 * 觀測性:單筆失敗由 {@link #retryBatch()} 記 log(不再逐筆寫 retryCount/lastError,避免又要一個交易);
 * 未成功的記錄 {@code resolvedAt} 仍為 null,下一輪排程會重掃。
 */
@Slf4j
@Component
public class StockSyncRetryWorker {

    private final StockSyncFailedRepository failedRepo;
    private final TicketRepository ticketRepo;
    private final StockSyncRetryWorker self;

    public StockSyncRetryWorker(StockSyncFailedRepository failedRepo,
                                TicketRepository ticketRepo,
                                @Lazy StockSyncRetryWorker self) {
        this.failedRepo = failedRepo;
        this.ticketRepo = ticketRepo;
        this.self = self;
    }

    /** 掃一批未處理的失敗記錄,逐筆以獨立交易重試,回傳這批筆數(0 表示沒有待處理)。 */
    public int retryBatch() {
        List<Long> ids = failedRepo.findTop100ByResolvedAtIsNullOrderByIdAsc()
                .stream().map(StockSyncFailed::getId).toList();
        for (Long id : ids) {
            try {
                self.retryOne(id);
            } catch (Exception e) {
                // 該筆的獨立交易已回滾;只記 log,下一輪排程會重掃(resolvedAt 仍為 null)
                log.warn("[StockSync] retryOne failed, id={}, err={}", id,
                        e.getMessage() == null ? "unknown" : e.getMessage());
            }
        }
        return ids.size();
    }

    /**
     * 單筆重試,獨立交易。成功(updated>0)→ 設 resolvedAt;
     * updated==0(條件不成立,如票券已刪/DB 庫存不足)→ 不設、留待下輪;
     * DB 例外 → 本交易回滾並往外拋(由 retryBatch 記 log,不影響其他筆)。
     * delta 語意:負=結帳要扣、正=退票要加回;qty = -delta(退票時 qty 為負,decrementStock 等效加回)。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void retryOne(Long failedId) {
        // 悲觀寫鎖:序列化同一 id 的併發重試(排程 vs admin 手動繞鎖),防重複扣減 DB 庫存
        StockSyncFailed f = failedRepo.findByIdForUpdate(failedId).orElse(null);
        if (f == null || f.getResolvedAt() != null) return;
        int qty = -f.getDelta();
        int updated = ticketRepo.decrementStock(f.getTicketId(), qty);
        if (updated > 0) {
            f.setResolvedAt(LocalDateTime.now());
        }
    }
}
