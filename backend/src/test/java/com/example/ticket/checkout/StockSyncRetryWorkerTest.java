package com.example.ticket.checkout;

import com.example.ticket.ticket.TicketRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * 庫存回寫失敗記錄批次重試。修復自呼叫交易失效後,結構為:
 * retryBatch(非交易,逐筆委派 self.retryOne)+ retryOne(REQUIRES_NEW,單筆獨立交易)。
 *
 * <p>單元測試驗業務邏輯與委派/隔離行為;「REQUIRES_NEW 真的讓一筆 DB 例外只回滾那筆、
 * 不污染整批」屬交易隔離,需 DB 整合測試(專案暫無 H2/testcontainers,記為待補)。
 */
@ExtendWith(MockitoExtension.class)
class StockSyncRetryWorkerTest {

    @Mock StockSyncFailedRepository failedRepo;
    @Mock TicketRepository ticketRepo;
    @Mock StockSyncRetryWorker self;   // 模擬 self proxy,測 retryBatch 的逐筆委派

    private StockSyncFailed rec(long id, long ticketId, int delta) {
        return StockSyncFailed.builder()
                .id(id).ticketId(ticketId).delta(delta).retryCount(3).build();
    }

    /** retryOne 不碰 self,可傳 null */
    private StockSyncRetryWorker workerForOne() {
        return new StockSyncRetryWorker(failedRepo, ticketRepo, null);
    }

    /** retryBatch 用 self mock 驗證委派 */
    private StockSyncRetryWorker workerForBatch() {
        return new StockSyncRetryWorker(failedRepo, ticketRepo, self);
    }

    // ---- retryOne:單筆邏輯 ----

    @Test
    void retryOne_success_setsResolvedAt() {
        StockSyncFailed f = rec(1L, 10L, -2);   // 扣減:qty = 2
        when(failedRepo.findByIdForUpdate(1L)).thenReturn(Optional.of(f));
        when(ticketRepo.decrementStock(10L, 2)).thenReturn(1);

        workerForOne().retryOne(1L);

        assertNotNull(f.getResolvedAt());
    }

    @Test
    void retryOne_updatedZero_leavesUnresolved() {
        StockSyncFailed f = rec(1L, 10L, -2);
        when(failedRepo.findByIdForUpdate(1L)).thenReturn(Optional.of(f));
        when(ticketRepo.decrementStock(10L, 2)).thenReturn(0);   // 條件不成立,非例外

        workerForOne().retryOne(1L);

        assertNull(f.getResolvedAt(), "未成功不設 resolvedAt,留待下輪重掃");
    }

    @Test
    void retryOne_alreadyResolved_skipsWork() {
        StockSyncFailed f = rec(1L, 10L, -2);
        f.setResolvedAt(LocalDateTime.now());
        when(failedRepo.findByIdForUpdate(1L)).thenReturn(Optional.of(f));

        workerForOne().retryOne(1L);

        verifyNoInteractions(ticketRepo);   // 已解決的不重做(防重掃期間的併發重複)
    }

    @Test
    void retryOne_notFound_noop() {
        when(failedRepo.findByIdForUpdate(1L)).thenReturn(Optional.empty());
        workerForOne().retryOne(1L);
        verifyNoInteractions(ticketRepo);
    }

    @Test
    void retryOne_refundPositiveDelta_passesNegativeQty() {
        StockSyncFailed f = rec(1L, 10L, 3);    // 退票:qty = -3(decrementStock 負值=加回)
        when(failedRepo.findByIdForUpdate(1L)).thenReturn(Optional.of(f));
        when(ticketRepo.decrementStock(10L, -3)).thenReturn(1);

        workerForOne().retryOne(1L);

        assertNotNull(f.getResolvedAt());
        verify(ticketRepo).decrementStock(10L, -3);
    }

    @Test
    void retryOne_dbException_propagatesAndLeavesUnresolved() {
        StockSyncFailed f = rec(1L, 10L, -2);
        when(failedRepo.findByIdForUpdate(1L)).thenReturn(Optional.of(f));
        when(ticketRepo.decrementStock(10L, 2)).thenThrow(new RuntimeException("db down"));

        // 例外往外拋(交由 REQUIRES_NEW 回滾該筆),retryBatch 會 catch 記 log
        assertThrows(RuntimeException.class, () -> workerForOne().retryOne(1L));
        assertNull(f.getResolvedAt());
    }

    // ---- retryBatch:委派與隔離 ----

    @Test
    void retryBatch_empty_returnsZeroAndDelegatesNothing() {
        when(failedRepo.findTop100ByResolvedAtIsNullOrderByIdAsc()).thenReturn(List.of());
        assertEquals(0, workerForBatch().retryBatch());
        verify(self, never()).retryOne(anyLong());
    }

    @Test
    void retryBatch_delegatesEachIdOnce() {
        when(failedRepo.findTop100ByResolvedAtIsNullOrderByIdAsc())
                .thenReturn(List.of(rec(1L, 10L, -2), rec(2L, 20L, -1)));

        assertEquals(2, workerForBatch().retryBatch());

        verify(self).retryOne(1L);
        verify(self).retryOne(2L);
    }

    @Test
    void retryBatch_oneRecordFailing_doesNotStopTheRest() {
        when(failedRepo.findTop100ByResolvedAtIsNullOrderByIdAsc())
                .thenReturn(List.of(rec(1L, 10L, -2), rec(2L, 20L, -1)));
        doThrow(new RuntimeException("boom")).when(self).retryOne(1L);

        // 第一筆例外不應中斷迴圈,第二筆仍要被處理(這正是逐筆獨立交易要保證的隔離)
        assertEquals(2, workerForBatch().retryBatch());
        verify(self).retryOne(1L);
        verify(self).retryOne(2L);
    }
}
