package com.example.ticket.ticket;

import com.example.ticket.cart.CartItemRepository;
import com.example.ticket.stock.QuotaRedisRepository;
import com.example.ticket.stock.StockRedisRepository;
import com.example.ticket.ticket.dto.TicketRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 票券限購欄位（v0.5-purchase-limit AC-1）與刪票清 quota key。
 */
@ExtendWith(MockitoExtension.class)
class TicketServicePurchaseLimitTest {

    @Mock TicketRepository ticketRepository;
    @Mock StockRedisRepository stockRedis;
    @Mock QuotaRedisRepository quotaRedis;
    @Mock CartItemRepository cartItemRepository;

    @InjectMocks TicketService ticketService;

    private TicketRequest req(Integer purchaseLimit) {
        TicketRequest req = new TicketRequest();
        req.setName("演唱會A");
        req.setPrice(new BigDecimal("100.00"));
        req.setStock(50);
        req.setPurchaseLimit(purchaseLimit);
        return req;
    }

    @Test
    void create_persistsPurchaseLimit() {
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(inv -> {
            Ticket t = inv.getArgument(0);
            t.setId(7L);
            return t;
        });

        Ticket saved = ticketService.create(req(3));

        assertEquals(3, saved.getPurchaseLimit());
        verify(stockRedis).set(7L, 50);
    }

    @Test
    void update_canClearPurchaseLimit() {
        Ticket existing = Ticket.builder().id(7L).name("舊名").price(new BigDecimal("50.00"))
                .stock(10).purchaseLimit(2).build();
        when(ticketRepository.findById(7L)).thenReturn(Optional.of(existing));

        Ticket updated = ticketService.update(7L, req(null));

        assertNull(updated.getPurchaseLimit());
    }

    @Test
    void delete_removesQuotaKeysAlongWithStockKey() {
        when(ticketRepository.existsById(7L)).thenReturn(true);
        when(cartItemRepository.deleteByTicketId(7L)).thenReturn(0L);
        when(quotaRedis.deleteByTicket(7L)).thenReturn(2L);

        ticketService.delete(7L);

        verify(stockRedis).delete(7L);
        verify(quotaRedis).deleteByTicket(7L);
    }
}
