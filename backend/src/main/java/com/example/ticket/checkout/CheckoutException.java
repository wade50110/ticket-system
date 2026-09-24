package com.example.ticket.checkout;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class CheckoutException extends RuntimeException {

    /**
     * 結帳失敗原因分類,供 v0.6 監控指標打標籤(有界列舉,避免基數爆炸)。
     * 純為觀測用途,不影響控制流程。
     */
    public enum Reason {
        /** 票券下架 / 庫存不足(售罄類)。 */
        SOLD_OUT,
        /** 取鎖失敗(搶票人潮過多)。 */
        LOCK_FAILED,
        /** 超過單帳號限購。 */
        QUOTA_EXCEEDED,
        /** 其他(空車、已刪除票券、付款失敗、未預期例外等)。 */
        OTHER
    }

    private final Reason reason;

    public CheckoutException(String message) {
        this(message, Reason.OTHER);
    }

    public CheckoutException(String message, Reason reason) {
        super(message);
        this.reason = reason == null ? Reason.OTHER : reason;
    }

    public Reason getReason() {
        return reason;
    }
}
