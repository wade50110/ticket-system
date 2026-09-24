package com.example.ticket.checkout;

import com.example.ticket.metrics.TicketMetrics;
import com.example.ticket.order.dto.OrderResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * v0.6 監控 T-1 / T-2:結帳指標在 controller 層(鎖外)正確累計,且指標埋點例外
 * 絕不影響結帳結果(monitoring.md AC-3 / AC-4 / F-5)。
 */
class CheckoutControllerTest {

    CheckoutService checkoutService;
    SimpleMeterRegistry registry;
    TicketMetrics metrics;
    CheckoutController controller;
    Authentication auth;

    @BeforeEach
    void setUp() {
        checkoutService = mock(CheckoutService.class);
        registry = new SimpleMeterRegistry();
        metrics = new TicketMetrics(registry);
        controller = new CheckoutController(checkoutService, metrics);
        auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn("10");
    }

    private double count(String name, String... tags) {
        return registry.get(name).tags(tags).counter().count();
    }

    // T-1:成功 → success 計數 + duration 有記錄
    @Test
    void checkout_success_incrementsSuccessCounterAndTimer() {
        OrderResponse resp = mock(OrderResponse.class);
        when(checkoutService.checkout(10L)).thenReturn(resp);

        OrderResponse out = controller.checkout(auth);

        assertSame(resp, out);
        assertEquals(1.0, count("ticket_checkout_total", "result", "success"), 0.0001);
        assertEquals(1L, registry.get("ticket_checkout_duration").timer().count());
    }

    // T-1:售罄失敗 → fail + reason=sold_out
    @Test
    void checkout_soldOut_incrementsFailSoldOut() {
        when(checkoutService.checkout(10L))
                .thenThrow(new CheckoutException("售罄", CheckoutException.Reason.SOLD_OUT));

        assertThrows(CheckoutException.class, () -> controller.checkout(auth));

        assertEquals(1.0, count("ticket_checkout_total", "result", "fail", "reason", "sold_out"), 0.0001);
        assertEquals(0.0, count("ticket_checkout_total", "result", "success"), 0.0001);
    }

    // T-1:取鎖失敗 → fail + reason=lock_failed
    @Test
    void checkout_lockFailed_incrementsFailLockFailed() {
        when(checkoutService.checkout(10L))
                .thenThrow(new CheckoutException("人潮過多", CheckoutException.Reason.LOCK_FAILED));

        assertThrows(CheckoutException.class, () -> controller.checkout(auth));

        assertEquals(1.0, count("ticket_checkout_total", "result", "fail", "reason", "lock_failed"), 0.0001);
    }

    // T-1:限購失敗 → fail + reason=quota_exceeded
    @Test
    void checkout_quotaExceeded_incrementsFailQuota() {
        when(checkoutService.checkout(10L))
                .thenThrow(new CheckoutException("超過限購", CheckoutException.Reason.QUOTA_EXCEEDED));

        assertThrows(CheckoutException.class, () -> controller.checkout(auth));

        assertEquals(1.0, count("ticket_checkout_total", "result", "fail", "reason", "quota_exceeded"), 0.0001);
    }

    // T-2(最關鍵):指標記錄路徑拋例外時,結帳仍成功、不因指標而失敗(AC-4 / F-5)
    @Test
    void checkout_metricRecordingThrows_doesNotBreakCheckout() {
        TicketMetrics throwingMetrics = mock(TicketMetrics.class);
        when(throwingMetrics.startCheckoutTimer()).thenReturn(null);
        doThrow(new RuntimeException("metric backend down"))
                .when(throwingMetrics).recordCheckoutSuccess(any());
        CheckoutController c = new CheckoutController(checkoutService, throwingMetrics);

        OrderResponse resp = mock(OrderResponse.class);
        when(checkoutService.checkout(10L)).thenReturn(resp);

        // controller 的 safe() 必須吞掉指標例外,結帳照常回傳
        OrderResponse out = assertDoesNotThrow(() -> c.checkout(auth));
        assertSame(resp, out);
    }

    // T-2:業務例外(CheckoutException)本身仍要正常往外拋(不被指標邏輯吞掉)
    @Test
    void checkout_businessException_stillPropagates_evenIfMetricThrows() {
        TicketMetrics throwingMetrics = mock(TicketMetrics.class);
        when(throwingMetrics.startCheckoutTimer()).thenReturn(null);
        doThrow(new RuntimeException("metric backend down"))
                .when(throwingMetrics).recordCheckoutFail(any(), any());
        CheckoutController c = new CheckoutController(checkoutService, throwingMetrics);

        when(checkoutService.checkout(10L))
                .thenThrow(new CheckoutException("售罄", CheckoutException.Reason.SOLD_OUT));

        assertThrows(CheckoutException.class, () -> c.checkout(auth));
    }

    // 防迴歸:真 PrometheusMeterRegistry 的 scrape 必須同時吐出 success 與 fail/reason 系列。
    // (SimpleMeterRegistry 不會發生標籤鍵集塌縮,守不到;此顆直接擋住 ticket_checkout_total 標籤鍵不一致的 bug)
    @Test
    void prometheusScrape_containsBothSuccessAndFailReasonSeries() {
        PrometheusMeterRegistry prom = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        TicketMetrics m = new TicketMetrics(prom);
        CheckoutController c = new CheckoutController(checkoutService, m);

        when(checkoutService.checkout(10L)).thenReturn(mock(OrderResponse.class));
        c.checkout(auth); // 一次成功

        when(checkoutService.checkout(10L))
                .thenThrow(new CheckoutException("售罄", CheckoutException.Reason.SOLD_OUT));
        assertThrows(CheckoutException.class, () -> c.checkout(auth)); // 一次售罄失敗

        String scrape = prom.scrape();
        assertTrue(scrape.contains("result=\"success\""), scrape);
        assertTrue(scrape.contains("reason=\"sold_out\""), scrape);
    }
}
