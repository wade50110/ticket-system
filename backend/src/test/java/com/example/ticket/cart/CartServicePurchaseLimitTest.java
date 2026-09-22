package com.example.ticket.cart;

import com.example.ticket.cart.dto.AddToCartRequest;
import com.example.ticket.stock.QuotaRedisRepository;
import com.example.ticket.stock.StockRedisRepository;
import com.example.ticket.ticket.Ticket;
import com.example.ticket.ticket.TicketRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * 購物車的限購預檢（v0.5-purchase-limit AC-9）：
 * 目前持有 + 購物車內同票數量（含本次）不得超過限購數；僅友善提示、不佔額度。
 */
@ExtendWith(MockitoExtension.class)
class CartServicePurchaseLimitTest {

    @Mock CartItemRepository cartItemRepository;
    @Mock TicketRepository ticketRepository;
    @Mock StockRedisRepository stockRedis;
    @Mock QuotaRedisRepository quotaRedis;

    @InjectMocks CartService cartService;

    private static final long USER_ID = 10L;
    private static final long TICKET_ID = 1L;

    private Ticket ticket(Integer purchaseLimit) {
        return Ticket.builder().id(TICKET_ID).name("演唱會A").price(new BigDecimal("100.00"))
                .stock(50).purchaseLimit(purchaseLimit).build();
    }

    private AddToCartRequest addReq(int qty) {
        AddToCartRequest req = new AddToCartRequest();
        req.setTicketId(TICKET_ID);
        req.setQuantity(qty);
        return req;
    }

    @Test
    void add_withinLimit_succeeds() {
        Ticket t = ticket(4);
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(t));
        when(cartItemRepository.findByUserIdAndTicketId(USER_ID, TICKET_ID))
                .thenReturn(Optional.of(CartItem.builder().userId(USER_ID).ticketId(TICKET_ID).quantity(1).build()));
        when(stockRedis.get(TICKET_ID)).thenReturn(50);
        when(quotaRedis.getHeld(TICKET_ID, USER_ID)).thenReturn(2);
        when(cartItemRepository.save(any(CartItem.class))).thenAnswer(inv -> inv.getArgument(0));

        // 持有 2 + 車內(1+1)=2 → 剛好 4，允許
        assertDoesNotThrow(() -> cartService.addOrIncrement(USER_ID, addReq(1)));
        verify(cartItemRepository).save(any(CartItem.class));
    }

    @Test
    void add_exceedingLimit_throwsWithFriendlyMessage() {
        Ticket t = ticket(4);
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(t));
        when(cartItemRepository.findByUserIdAndTicketId(USER_ID, TICKET_ID)).thenReturn(Optional.empty());
        when(stockRedis.get(TICKET_ID)).thenReturn(50);
        when(quotaRedis.getHeld(TICKET_ID, USER_ID)).thenReturn(3);

        // 持有 3 + 本次 2 > 4 → 擋
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> cartService.addOrIncrement(USER_ID, addReq(2)));

        assertTrue(ex.getMessage().contains("已達限購上限"), ex.getMessage());
        assertTrue(ex.getMessage().contains("演唱會A"), ex.getMessage());
        assertTrue(ex.getMessage().contains("你已持有 3 張"), ex.getMessage());
        verify(cartItemRepository, never()).save(any());
    }

    @Test
    void updateQuantity_exceedingLimit_throwsAndQuantityUnchanged() {
        Ticket t = ticket(2);
        CartItem item = CartItem.builder().id(9L).userId(USER_ID).ticketId(TICKET_ID).quantity(1).build();
        when(cartItemRepository.findById(9L)).thenReturn(Optional.of(item));
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(t));
        when(stockRedis.get(TICKET_ID)).thenReturn(50);
        when(quotaRedis.getHeld(TICKET_ID, USER_ID)).thenReturn(0);

        // 持有 0 + 更新後 3 > 2 → 擋
        assertThrows(IllegalArgumentException.class,
                () -> cartService.updateQuantity(USER_ID, 9L, 3));
        assertEquals(1, item.getQuantity());
    }

    @Test
    void add_noLimit_skipsQuotaLookup() {
        Ticket t = ticket(null);
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(t));
        when(cartItemRepository.findByUserIdAndTicketId(USER_ID, TICKET_ID)).thenReturn(Optional.empty());
        when(stockRedis.get(TICKET_ID)).thenReturn(50);
        when(cartItemRepository.save(any(CartItem.class))).thenAnswer(inv -> inv.getArgument(0));

        assertDoesNotThrow(() -> cartService.addOrIncrement(USER_ID, addReq(5)));
        verify(quotaRedis, never()).getHeld(anyLong(), anyLong());
    }
}
