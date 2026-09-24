package com.example.ticket.checkout;

import com.example.ticket.metrics.TicketMetrics;
import com.example.ticket.order.dto.OrderResponse;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/checkout")
@RequiredArgsConstructor
public class CheckoutController {

    private final CheckoutService checkoutService;
    private final TicketMetrics metrics;

    @PostMapping
    public OrderResponse checkout(Authentication authentication) {
        Long userId = Long.valueOf((String) authentication.getPrincipal());
        // 結帳成功/失敗/耗時在此(鎖外)量;reason 取自 CheckoutException。
        // 指標記錄一律包 safe,任何指標例外都不得影響結帳結果(monitoring.md F-5)。
        Timer.Sample sample = metrics.startCheckoutTimer();
        try {
            OrderResponse resp = checkoutService.checkout(userId);
            safe(() -> metrics.recordCheckoutSuccess(sample));
            return resp;
        } catch (CheckoutException e) {
            safe(() -> metrics.recordCheckoutFail(e.getReason(), sample));
            throw e;
        }
    }

    private static void safe(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ignored) {
            // 指標失敗絕不中斷結帳
        }
    }
}
