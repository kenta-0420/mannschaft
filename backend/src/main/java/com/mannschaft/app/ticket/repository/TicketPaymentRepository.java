package com.mannschaft.app.ticket.repository;

import com.mannschaft.app.ticket.entity.TicketPaymentEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 回数券決済記録リポジトリ。
 */
public interface TicketPaymentRepository extends JpaRepository<TicketPaymentEntity, Long> {

    /**
     * Stripe Checkout Session ID で決済を取得する。
     */
    Optional<TicketPaymentEntity> findByStripeCheckoutSessionId(String sessionId);

    /**
     * Stripe PaymentIntent ID で決済を取得する。
     */
    Optional<TicketPaymentEntity> findByStripePaymentIntentId(String paymentIntentId);

    /**
     * チームの決済一覧をページング取得する。
     */
    Page<TicketPaymentEntity> findByTeamIdOrderByCreatedAtDesc(Long teamId, Pageable pageable);
}
