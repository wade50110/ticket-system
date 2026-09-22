package com.example.ticket.stock;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 限購額度維運 API（ADMIN）。
 */
@RestController
@RequestMapping("/api/admin/quota")
@RequiredArgsConstructor
public class AdminQuotaController {

    private final QuotaReconcileService reconcileService;

    /**
     * 額度對帳:以 DB 彙總覆寫全部 quota key,回傳更新(含刪除)筆數。
     * ⚠️ 僅可於無結帳流量的維護窗口執行,詳見 {@link QuotaReconcileService}。
     */
    @PostMapping("/reconcile")
    public Map<String, Object> reconcile() {
        int updated = reconcileService.reconcile();
        return Map.of(
                "updated", updated,
                "warning", "僅可於無結帳流量時執行;有併發結帳時對帳可能覆蓋剛成立的扣減而造成超限");
    }
}
