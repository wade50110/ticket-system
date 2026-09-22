package com.example.ticket.cart;

import com.example.ticket.cart.dto.AddToCartRequest;
import com.example.ticket.cart.dto.CartItemResponse;
import com.example.ticket.stock.QuotaRedisRepository;
import com.example.ticket.stock.StockRedisRepository;
import com.example.ticket.ticket.Ticket;
import com.example.ticket.ticket.TicketRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class CartService {

    private final CartItemRepository cartItemRepository;
    private final TicketRepository ticketRepository;
    private final StockRedisRepository stockRedis;
    private final QuotaRedisRepository quotaRedis;

    /**
     * 列出購物車。
     * 若購物車引用的票券已被刪除（理論上 TicketService.delete 會清掉，但作為兜底）：
     * 順手把該 cart_item 刪掉，並 log；前端不會看到該項目。
     */
    @Transactional
    public List<CartItemResponse> listByUser(Long userId) {
        List<CartItem> items = cartItemRepository.findByUserIdOrderByIdAsc(userId);
        return items.stream()
                .map(item -> {
                    Ticket ticket = ticketRepository.findById(item.getTicketId()).orElse(null);
                    if (ticket == null) {
                        log.warn("orphan cart_item: id={}, userId={}, ticketId={} 已不存在，自動清除",
                                item.getId(), userId, item.getTicketId());
                        cartItemRepository.delete(item);
                        return null;
                    }
                    return CartItemResponse.from(item, ticket);
                })
                .filter(Objects::nonNull)
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
        int available = currentStock(ticket);
        if (newQty > available) {
            throw new IllegalArgumentException("超出庫存：剩餘 " + available + " 張");
        }
        checkPurchaseLimit(userId, ticket, newQty);
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
        int available = currentStock(ticket);
        if (quantity > available) {
            throw new IllegalArgumentException("超出庫存：剩餘 " + available + " 張");
        }
        checkPurchaseLimit(userId, ticket, quantity);
        item.setQuantity(quantity);
        return CartItemResponse.from(item, ticket);
    }

    /** Redis 為庫存正源；若 Redis 沒值（極端情況）退回 DB stock。 */
    private int currentStock(Ticket ticket) {
        Integer fromRedis = stockRedis.get(ticket.getId());
        return fromRedis != null ? fromRedis : ticket.getStock();
    }

    /**
     * 限購預檢（友善提示，不佔額度）：目前持有 + 購物車內同票數量（含本次）不得超過限購數。
     * 結帳時的 Lua 原子檢查才是權威閘門，這裡擋不到的併發情況由結帳兜底。
     */
    private void checkPurchaseLimit(Long userId, Ticket ticket, int cartQty) {
        Integer limit = ticket.getPurchaseLimit();
        if (limit == null) return;
        int held = quotaRedis.getHeld(ticket.getId(), userId);
        if (held + cartQty > limit) {
            throw new IllegalArgumentException(
                    "已達限購上限：「" + ticket.getName() + "」每人限購 " + limit + " 張，你已持有 " + held + " 張");
        }
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
