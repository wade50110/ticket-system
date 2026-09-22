package com.example.ticket.order;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 退票業務規則違反（如：非已付款狀態、重複退票）。回應 409。
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class RefundException extends RuntimeException {

    public RefundException(String message) {
        super(message);
    }
}
