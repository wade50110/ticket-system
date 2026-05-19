package com.example.ticket.cart;

import com.example.ticket.cart.dto.AddToCartRequest;
import com.example.ticket.cart.dto.CartItemResponse;
import com.example.ticket.cart.dto.UpdateCartItemRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/cart")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;

    @GetMapping
    public List<CartItemResponse> list(Authentication authentication) {
        return cartService.listByUser(currentUserId(authentication));
    }

    @PostMapping
    public CartItemResponse add(Authentication authentication, @Valid @RequestBody AddToCartRequest req) {
        return cartService.addOrIncrement(currentUserId(authentication), req);
    }

    @PatchMapping("/{id}")
    public CartItemResponse update(Authentication authentication,
                                   @PathVariable Long id,
                                   @Valid @RequestBody UpdateCartItemRequest req) {
        return cartService.updateQuantity(currentUserId(authentication), id, req.getQuantity());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> remove(Authentication authentication, @PathVariable Long id) {
        cartService.remove(currentUserId(authentication), id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    public ResponseEntity<Void> clear(Authentication authentication) {
        cartService.clear(currentUserId(authentication));
        return ResponseEntity.noContent().build();
    }

    private Long currentUserId(Authentication authentication) {
        return Long.valueOf((String) authentication.getPrincipal());
    }
}
