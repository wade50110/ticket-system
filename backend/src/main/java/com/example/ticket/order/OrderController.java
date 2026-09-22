package com.example.ticket.order;

import com.example.ticket.order.dto.OrderResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    private final RefundService refundService;

    @GetMapping
    public List<OrderResponse> list(Authentication authentication) {
        return orderService.listByUser(currentUserId(authentication));
    }

    @GetMapping("/{id}")
    public OrderResponse detail(Authentication authentication, @PathVariable Long id) {
        return orderService.getOwn(currentUserId(authentication), id);
    }

    @PostMapping("/{id}/refund")
    public OrderResponse refund(Authentication authentication, @PathVariable Long id) {
        return refundService.refund(currentUserId(authentication), id);
    }

    private Long currentUserId(Authentication authentication) {
        return Long.valueOf((String) authentication.getPrincipal());
    }
}
