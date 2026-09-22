package com.example.ticket.checkout;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class CheckoutException extends RuntimeException {

    public CheckoutException(String message) {
        super(message);
    }
}
