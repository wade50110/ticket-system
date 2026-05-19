package com.example.ticket.cart;

import com.example.ticket.cart.dto.AddToCartRequest;
import com.example.ticket.cart.dto.CartItemResponse;
import com.example.ticket.ticket.Ticket;
import com.example.ticket.ticket.TicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CartService {

    private final CartItemRepository cartItemRepository;
    private final TicketRepository ticketRepository;

    @Transactional(readOnly = true)
    public List<CartItemResponse> listByUser(Long userId) {
        List<CartItem> items = cartItemRepository.findByUserIdOrderByIdAsc(userId);
        return items.stream()
                .map(item -> {
                    Ticket ticket = ticketRepository.findById(item.getTicketId())
                            .orElseThrow(() -> new IllegalArgumentException("票券不存在: id=" + item.getTicketId()));
                    return CartItemResponse.from(item, ticket);
                })
                .toList();
    }

    @Transactional
    public CartItemResponse addOrIncrement(Long userId, AddToCartRequest req) {
        Ticket ticket = ticketRepository.findById(req.getTicketId())
                .orElseThrow(() -> new IllegalArgumentException("票券不存在"));
        ensureVisible(ticket);
        CartItem item = cartItemRepository.findByUserIdAndTicketId(userId, req.getTicketId())
                .orElseGet(() -> CartItem.builder()
                        .userId(userId)
                        .ticketId(req.getTicketId())
                        .quantity(0)
                        .build());
        int newQty = item.getQuantity() + req.getQuantity();
        if (newQty > ticket.getStock()) {
            throw new IllegalArgumentException("超出庫存：剩餘 " + ticket.getStock() + " 張");
        }
        item.setQuantity(newQty);
        CartItem saved = cartItemRepository.save(item);
        return CartItemResponse.from(saved, ticket);
    }

    @Transactional
    public CartItemResponse updateQuantity(Long userId, Long cartItemId, Integer quantity) {
        CartItem item = cartItemRepository.findById(cartItemId)
                .orElseThrow(() -> new IllegalArgumentException("購物車項目不存在"));
        if (!item.getUserId().equals(userId)) {
            throw new IllegalArgumentException("購物車項目不存在");
        }
        Ticket ticket = ticketRepository.findById(item.getTicketId())
                .orElseThrow(() -> new IllegalArgumentException("票券不存在"));
        if (quantity > ticket.getStock()) {
            throw new IllegalArgumentException("超出庫存：剩餘 " + ticket.getStock() + " 張");
        }
        item.setQuantity(quantity);
        return CartItemResponse.from(item, ticket);
    }

    @Transactional
    public void remove(Long userId, Long cartItemId) {
        CartItem item = cartItemRepository.findById(cartItemId)
                .orElseThrow(() -> new IllegalArgumentException("購物車項目不存在"));
        if (!item.getUserId().equals(userId)) {
            throw new IllegalArgumentException("購物車項目不存在");
        }
        cartItemRepository.delete(item);
    }

    @Transactional
    public void clear(Long userId) {
        cartItemRepository.deleteByUserId(userId);
    }

    private void ensureVisible(Ticket ticket) {
        LocalDateTime now = LocalDateTime.now();
        if (ticket.getVisibleAt() != null && now.isBefore(ticket.getVisibleAt())) {
            throw new IllegalArgumentException("票券尚未開賣");
        }
        if (ticket.getVisibleUntil() != null && now.isAfter(ticket.getVisibleUntil())) {
            throw new IllegalArgumentException("票券已下架");
        }
    }
}
