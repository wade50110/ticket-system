package com.example.ticket.payment;

import java.math.BigDecimal;

public interface PaymentService {

    PaymentResult charge(Long orderId, BigDecimal amount);

    PaymentResult refund(Long orderId, BigDecimal amount);
}
