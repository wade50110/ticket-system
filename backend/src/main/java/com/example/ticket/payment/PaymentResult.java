package com.example.ticket.payment;

public record PaymentResult(boolean success, String transactionId, String message) {

    public static PaymentResult ok(String transactionId) {
        return new PaymentResult(true, transactionId, "ok");
    }

    public static PaymentResult fail(String message) {
        return new PaymentResult(false, null, message);
    }
}
