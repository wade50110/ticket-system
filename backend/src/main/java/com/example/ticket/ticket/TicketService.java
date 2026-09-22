package com.example.ticket.ticket;

import com.example.ticket.cart.CartItemRepository;
import com.example.ticket.stock.QuotaRedisRepository;
import com.example.ticket.stock.StockRedisRepository;
import com.example.ticket.ticket.dto.TicketRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class TicketService {

    private final TicketRepository ticketRepository;
    private final StockRedisRepository stockRedis;
    private final QuotaRedisRepository quotaRedis;
    private final CartItemRepository cartItemRepository;

    public List<Ticket> findAll() {
        return ticketRepository.findAll();
    }

    public List<Ticket> findVisibleNow() {
        return ticketRepository.findVisibleAt(LocalDateTime.now());
    }

    public Ticket findById(Long id) {
        return ticketRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("票券不存在"));
    }

    @Transactional
    public Ticket create(TicketRequest req) {
        validate(req);
        Ticket ticket = Ticket.builder()
                .name(req.getName())
                .description(req.getDescription())
                .price(req.getPrice())
                .stock(req.getStock())
                .purchaseLimit(req.getPurchaseLimit())
                .visibleAt(req.getVisibleAt())
                .visibleUntil(req.getVisibleUntil())
                .build();
        Ticket saved = ticketRepository.save(ticket);
        stockRedis.set(saved.getId(), saved.getStock());
        return saved;
    }

    @Transactional
    public Ticket update(Long id, TicketRequest req) {
        validate(req);
        Ticket ticket = findById(id);
        ticket.setName(req.getName());
        ticket.setDescription(req.getDescription());
        ticket.setPrice(req.getPrice());
        ticket.setStock(req.getStock());
        ticket.setPurchaseLimit(req.getPurchaseLimit());
        ticket.setVisibleAt(req.getVisibleAt());
        ticket.setVisibleUntil(req.getVisibleUntil());
        stockRedis.set(ticket.getId(), ticket.getStock());
        return ticket;
    }

    @Transactional
    public void delete(Long id) {
        if (!ticketRepository.existsById(id)) {
            throw new IllegalArgumentException("票券不存在");
        }
        long removed = cartItemRepository.deleteByTicketId(id);
        if (removed > 0) {
            log.info("Removed {} cart_items referencing deleted ticketId={}", removed, id);
        }
        ticketRepository.deleteById(id);
        stockRedis.delete(id);
        long quotaRemoved = quotaRedis.deleteByTicket(id);
        if (quotaRemoved > 0) {
            log.info("Removed {} quota keys for deleted ticketId={}", quotaRemoved, id);
        }
    }

    private void validate(TicketRequest req) {
        if (req.getVisibleAt() != null && req.getVisibleUntil() != null
                && req.getVisibleUntil().isBefore(req.getVisibleAt())) {
            throw new IllegalArgumentException("下架時間不可早於上架時間");
        }
    }
}
