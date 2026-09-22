package com.example.ticket.checkout;

import com.example.ticket.order.dto.OrderResponse;
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

    @PostMapping
    public OrderResponse checkout(Authentication authentication) {
        Long userId = Long.valueOf((String) authentication.getPrincipal());
        return checkoutService.checkout(userId);
    }
}
