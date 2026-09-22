package com.example.ticket.checkout;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/stock-sync")
@RequiredArgsConstructor
public class AdminStockSyncController {

    private final StockSyncRetryWorker retryWorker;
    private final StockSyncFailedRepository failedRepo;

    /**
     * 手動觸發庫存回寫重試。
     * 直接呼叫 {@link StockSyncRetryWorker#retryBatch()},<b>不經過帶 {@code @SchedulerLock} 的
     * 排程方法</b>——admin 手動重試不應被排程的分散式鎖靜默跳過(否則會回報誤導的 before==after)。
     */
    @PostMapping("/retry")
    public Map<String, Object> retry() {
        int before = failedRepo.findTop100ByResolvedAtIsNullOrderByIdAsc().size();
        int processed = retryWorker.retryBatch();
        int after = failedRepo.findTop100ByResolvedAtIsNullOrderByIdAsc().size();
        return Map.of("pendingBefore", before, "processed", processed, "pendingAfter", after);
    }
}
