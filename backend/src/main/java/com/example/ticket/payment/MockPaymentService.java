package com.example.ticket.payment;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

@Slf4j
@Service
public class MockPaymentService implements PaymentService {

    @Override
    public PaymentResult charge(Long orderId, BigDecimal amount) {
        String txn = "MOCK-" + UUID.randomUUID();
        log.info("[MOCK PAY] order={} amount={} -> success, txn={}", orderId, amount, txn);
        return PaymentResult.ok(txn);
    }

    @Override
    public PaymentResult refund(Long orderId, BigDecimal amount) {
        String txn = "MOCK-REFUND-" + UUID.randomUUID();
        log.info("[MOCK REFUND] order={} amount={} -> success, txn={}", orderId, amount, txn);
        return PaymentResult.ok(txn);
    }
}
