package com.example.ticket.ticket;

import com.example.ticket.ticket.dto.TicketRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TicketService {

    private final TicketRepository ticketRepository;

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
                .visibleAt(req.getVisibleAt())
                .visibleUntil(req.getVisibleUntil())
                .build();
        return ticketRepository.save(ticket);
    }

    @Transactional
    public Ticket update(Long id, TicketRequest req) {
        validate(req);
        Ticket ticket = findById(id);
        ticket.setName(req.getName());
        ticket.setDescription(req.getDescription());
        ticket.setPrice(req.getPrice());
        ticket.setStock(req.getStock());
        ticket.setVisibleAt(req.getVisibleAt());
        ticket.setVisibleUntil(req.getVisibleUntil());
        return ticket;
    }

    @Transactional
    public void delete(Long id) {
        if (!ticketRepository.existsById(id)) {
            throw new IllegalArgumentException("票券不存在");
        }
        ticketRepository.deleteById(id);
    }

    private void validate(TicketRequest req) {
        if (req.getVisibleAt() != null && req.getVisibleUntil() != null
                && req.getVisibleUntil().isBefore(req.getVisibleAt())) {
            throw new IllegalArgumentException("下架時間不可早於上架時間");
        }
    }
}
