package com.example.ticket.ticket.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TicketRequest 的 Bean Validation（v0.5-purchase-limit AC-1、第 8 節「欄位驗證 null / ≥1 / 0 / 負數」）。
 * purchaseLimit 用 @Min(1)：null 合法（不限購）、≥1 合法、0 與負數應產生 violation。
 * controller 以 @Valid 掛上這層,故這是唯一寫入入口的權威驗證。
 */
class TicketRequestValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    private TicketRequest base() {
        TicketRequest req = new TicketRequest();
        req.setName("演唱會A");
        req.setPrice(new BigDecimal("100.00"));
        req.setStock(50);
        return req;
    }

    private boolean hasPurchaseLimitViolation(TicketRequest req) {
        return validator.validate(req).stream()
                .anyMatch(v -> v.getPropertyPath().toString().equals("purchaseLimit"));
    }

    @Test
    void purchaseLimit_null_isValid() {
        TicketRequest req = base();
        req.setPurchaseLimit(null);
        assertFalse(hasPurchaseLimitViolation(req));
    }

    @Test
    void purchaseLimit_positive_isValid() {
        TicketRequest req = base();
        req.setPurchaseLimit(1);
        assertFalse(hasPurchaseLimitViolation(req));
        req.setPurchaseLimit(10);
        assertFalse(hasPurchaseLimitViolation(req));
    }

    @Test
    void purchaseLimit_zero_isRejected() {
        TicketRequest req = base();
        req.setPurchaseLimit(0);
        assertTrue(hasPurchaseLimitViolation(req), "purchaseLimit=0 應被 @Min(1) 拒絕");
    }

    @Test
    void purchaseLimit_negative_isRejected() {
        TicketRequest req = base();
        req.setPurchaseLimit(-3);
        assertTrue(hasPurchaseLimitViolation(req), "purchaseLimit 負數應被 @Min(1) 拒絕");
    }
}
