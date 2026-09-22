package com.example.ticket.order;

import com.example.ticket.order.dto.OrderResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrderService {

    private static final DateTimeFormatter ORDER_NO_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private final OrderRepository orderRepository;

    @Transactional(readOnly = true)
    public List<OrderResponse> listByUser(Long userId) {
        return orderRepository.findByUserIdOrderByIdDesc(userId).stream()
                .map(OrderResponse::summary)
                .toList();
    }

    @Transactional(readOnly = true)
    public OrderResponse getOwn(Long userId, Long orderId) {
        Order order = orderRepository.findWithItemsById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("訂單不存在"));
        if (!order.getUserId().equals(userId)) {
            throw new IllegalArgumentException("訂單不存在");
        }
        return OrderResponse.from(order);
    }

    public static String generateOrderNo() {
        String ts = LocalDateTime.now().format(ORDER_NO_FMT);
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return "ORD-" + ts + "-" + suffix;
    }
}
