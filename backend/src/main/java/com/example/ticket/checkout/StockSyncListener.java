package com.example.ticket.checkout;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class StockSyncListener {

    private final StockSyncWorker worker;
    private final StockRestoreWorker restoreWorker;
    private final StockSyncRetryWorker retryWorker;

    @Async
    @EventListener
    public void onStockChanged(StockChangedEvent event) {
        for (StockChangedEvent.Change c : event.changes()) {
            try {
                worker.syncOne(event.orderId(), c.ticketId(), c.quantity());
            } catch (Exception e) {
                log.error("syncOne fell through after recover, orderId={} ticketId={}",
                        event.orderId(), c.ticketId(), e);
            }
        }
    }

    /** 退票：把庫存加回 DB。 */
    @Async
    @EventListener
    public void onStockRestored(StockRestoredEvent event) {
        for (StockChangedEvent.Change c : event.changes()) {
            try {
                restoreWorker.restoreOne(event.orderId(), c.ticketId(), c.quantity());
            } catch (Exception e) {
                log.error("restoreOne fell through after recover, orderId={} ticketId={}",
                        event.orderId(), c.ticketId(), e);
            }
        }
    }

    /**
     * 每 60 秒掃未處理的失敗記錄重試。
     *
     * <p>實際批次邏輯委派給 {@link StockSyncRetryWorker}(獨立 bean,交易才生效,見該類註解)。
     * <p>{@code @SchedulerLock}:多 pod(k8s autoscale)下全叢集同一時刻只有一個 pod 執行,
     * 用 Redis 分散式鎖防重複掃描重試(見 docs/deployment/scheduled-jobs-shedlock.md)。
     * lockAtMostFor 是保險絲(執行者當機時鎖最長存活),要比排程最壞執行時間長。
     */
    @Scheduled(fixedDelay = 60_000L, initialDelay = 30_000L)
    @SchedulerLock(name = "retryFailedRecords", lockAtMostFor = "PT2M", lockAtLeastFor = "PT5S")
    public void retryFailedRecords() {
        int count = retryWorker.retryBatch();
        if (count > 0) {
            log.info("[StockSync] retried {} failed records", count);
        }
    }
}
