package com.example.ticket.cart;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface CartItemRepository extends JpaRepository<CartItem, Long> {

    List<CartItem> findByUserIdOrderByIdAsc(Long userId);

    Optional<CartItem> findByUserIdAndTicketId(Long userId, Long ticketId);

    @Transactional
    void deleteByUserId(Long userId);

    /** 管理員刪除票券時清掉所有使用者購物車裡的孤兒項目 */
    @Transactional
    long deleteByTicketId(Long ticketId);
}
