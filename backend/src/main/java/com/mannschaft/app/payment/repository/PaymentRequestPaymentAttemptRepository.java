package com.mannschaft.app.payment.repository;

import com.mannschaft.app.payment.entity.PaymentRequestPaymentAttemptEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface PaymentRequestPaymentAttemptRepository extends JpaRepository<PaymentRequestPaymentAttemptEntity, UUID> {
    Optional<PaymentRequestPaymentAttemptEntity> findByPaymentRequestIdAndClientKeyHash(
            UUID paymentRequestId, String clientKeyHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from PaymentRequestPaymentAttemptEntity a where a.id = :id")
    Optional<PaymentRequestPaymentAttemptEntity> findByIdForUpdate(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from PaymentRequestPaymentAttemptEntity a where a.stripePaymentIntentId = :paymentIntentId")
    Optional<PaymentRequestPaymentAttemptEntity> findByStripePaymentIntentIdForUpdate(String paymentIntentId);
}
