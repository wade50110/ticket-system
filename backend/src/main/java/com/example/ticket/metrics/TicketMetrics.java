package com.example.ticket.metrics;

import com.example.ticket.checkout.CheckoutException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * 搶購/退票相關的自訂業務指標(v0.6 監控)。
 *
 * <p>集中封裝並保證 <b>exception-safe</b>:任何指標操作失敗都<b>不得</b>中斷結帳/退票流程
 * (需求書 monitoring.md F-5)。所有 meter 於建構時<b>預先註冊並持有參照</b>,呼叫路徑
 * 純記憶體 O(1)、<b>無任何 Redis/DB I/O</b>(F-6);標籤值只用有界列舉,無高基數識別碼(F-9)。
 *
 * <p>埋點位置(F-7):結帳成功/失敗/耗時由 {@code CheckoutController}(鎖外)呼叫;
 * 只有 {@code oversell_guard} 由 {@code CheckoutService}(鎖臨界區)呼叫;退票由 {@code RefundService} 呼叫。
 */
@Component
public class TicketMetrics {

    private static final String CHECKOUT_TOTAL = "ticket_checkout_total";
    private static final String OVERSELL_GUARD = "ticket_oversell_guard_total";
    private static final String REFUND_TOTAL = "ticket_refund_total";
    private static final String CHECKOUT_DURATION = "ticket_checkout_duration";

    private final MeterRegistry registry;

    private final Counter checkoutSuccess;
    private final Counter checkoutFailSoldOut;
    private final Counter checkoutFailLockFailed;
    private final Counter checkoutFailQuota;
    private final Counter checkoutFailOther;
    private final Counter oversellStockInsufficient;
    private final Counter oversellRollback;
    private final Counter refundSuccess;
    private final Counter refundFail;
    private final Timer checkoutDuration;

    public TicketMetrics(MeterRegistry registry) {
        this.registry = registry;
        // 同一 metric name 下所有系列的「標籤鍵集合」必須一致,否則 PrometheusMeterRegistry
        // 會以第一個註冊者的鍵集為準、scrape 時丟棄其餘系列(fail 會整組消失)。
        // 因此 success 也帶 reason=none,與 fail 系列的 {result,reason} 對齊。
        this.checkoutSuccess = Counter.builder(CHECKOUT_TOTAL)
                .tag("result", "success").tag("reason", "none").description("結帳結果計數").register(registry);
        this.checkoutFailSoldOut = checkoutFail(registry, "sold_out");
        this.checkoutFailLockFailed = checkoutFail(registry, "lock_failed");
        this.checkoutFailQuota = checkoutFail(registry, "quota_exceeded");
        this.checkoutFailOther = checkoutFail(registry, "other");
        this.oversellStockInsufficient = Counter.builder(OVERSELL_GUARD)
                .tag("type", "stock_insufficient").description("超賣防護觸發次數").register(registry);
        this.oversellRollback = Counter.builder(OVERSELL_GUARD)
                .tag("type", "rollback")
                .description("補償回滾總數(涵蓋售罄/限購/付款失敗等所有回滾路徑,非僅超賣)").register(registry);
        this.refundSuccess = Counter.builder(REFUND_TOTAL).tag("result", "success").description("退票結果計數").register(registry);
        this.refundFail = Counter.builder(REFUND_TOTAL).tag("result", "fail").register(registry);
        this.checkoutDuration = Timer.builder(CHECKOUT_DURATION)
                .description("結帳處理耗時").publishPercentileHistogram().register(registry);
    }

    private static Counter checkoutFail(MeterRegistry r, String reason) {
        return Counter.builder(CHECKOUT_TOTAL).tag("result", "fail").tag("reason", reason).register(r);
    }

    /** 開始計時;失敗回 null(呼叫端 stop 對 null 是 no-op)。 */
    public Timer.Sample startCheckoutTimer() {
        try {
            return Timer.start(registry);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public void recordCheckoutSuccess(Timer.Sample sample) {
        safe(checkoutSuccess::increment);
        stop(sample);
    }

    public void recordCheckoutFail(CheckoutException.Reason reason, Timer.Sample sample) {
        safe(counterFor(reason)::increment);
        stop(sample);
    }

    /** 超賣防護:Lua 扣減判定庫存不足時觸發(結帳臨界區內呼叫,務必 exception-safe)。 */
    public void recordOversellStockInsufficient() {
        safe(oversellStockInsufficient::increment);
    }

    /** 補償回滾發生一次。 */
    public void recordRollback() {
        safe(oversellRollback::increment);
    }

    public void recordRefundSuccess() {
        safe(refundSuccess::increment);
    }

    public void recordRefundFail() {
        safe(refundFail::increment);
    }

    private Counter counterFor(CheckoutException.Reason reason) {
        if (reason == null) {
            return checkoutFailOther;
        }
        return switch (reason) {
            case SOLD_OUT -> checkoutFailSoldOut;
            case LOCK_FAILED -> checkoutFailLockFailed;
            case QUOTA_EXCEEDED -> checkoutFailQuota;
            case OTHER -> checkoutFailOther;
        };
    }

    private void stop(Timer.Sample sample) {
        if (sample == null) {
            return;
        }
        try {
            sample.stop(checkoutDuration);
        } catch (RuntimeException e) {
            // 計時失敗不得影響業務
        }
    }

    /** 任何指標操作都不得讓例外逸出而中斷業務流程(F-5)。 */
    private static void safe(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            // 指標失敗絕不中斷結帳/退票
        }
    }
}
