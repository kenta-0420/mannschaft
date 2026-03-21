package com.mannschaft.app.payment.repository;

import com.mannschaft.app.payment.PaymentStatus;
import com.mannschaft.app.payment.entity.MemberPaymentEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

/**
 * 支払い記録リポジトリ。
 */
public interface MemberPaymentRepository extends JpaRepository<MemberPaymentEntity, Long> {

    /**
     * 支払い項目ごとの支払い記録をページング取得する。
     */
    Page<MemberPaymentEntity> findByPaymentItemId(Long paymentItemId, Pageable pageable);

    /**
     * 支払い項目とステータスで支払い記録をページング取得する。
     */
    Page<MemberPaymentEntity> findByPaymentItemIdAndStatus(Long paymentItemId, PaymentStatus status, Pageable pageable);

    /**
     * ユーザーの支払い記録をページング取得する（支払い完了日降順）。
     */
    Page<MemberPaymentEntity> findByUserIdOrderByPaidAtDescCreatedAtDesc(Long userId, Pageable pageable);

    /**
     * ユーザーの有効な PAID レコードが存在するか確認する。
     */
    @Query("SELECT COUNT(mp) > 0 FROM MemberPaymentEntity mp " +
            "WHERE mp.userId = :userId AND mp.paymentItemId = :paymentItemId " +
            "AND mp.status = 'PAID' " +
            "AND (mp.validUntil IS NULL OR mp.validUntil >= CURRENT_DATE)")
    boolean existsValidPaidPayment(@Param("userId") Long userId,
                                   @Param("paymentItemId") Long paymentItemId);

    /**
     * Stripe Checkout Session ID で支払い記録を取得する（ロック付き）。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<MemberPaymentEntity> findByStripeCheckoutSessionId(String stripeCheckoutSessionId);

    /**
     * Stripe Payment Intent ID で支払い記録を取得する（ロック付き）。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<MemberPaymentEntity> findByStripePaymentIntentId(String stripePaymentIntentId);

    /**
     * 支払い項目の PAID 合計額を取得する。
     */
    @Query("SELECT COALESCE(SUM(mp.amountPaid), 0) FROM MemberPaymentEntity mp " +
            "WHERE mp.paymentItemId = :paymentItemId AND mp.status = 'PAID'")
    java.math.BigDecimal sumPaidAmountByPaymentItemId(@Param("paymentItemId") Long paymentItemId);

    /**
     * 支払い項目の PAID 件数を取得する。
     */
    long countByPaymentItemIdAndStatus(Long paymentItemId, PaymentStatus status);

    /**
     * ユーザーの全支払い記録を取得する（チーム/組織横断）。
     */
    List<MemberPaymentEntity> findByUserId(Long userId);

    /**
     * 支払い項目に対する全支払い記録を取得する。
     */
    List<MemberPaymentEntity> findByPaymentItemId(Long paymentItemId);

    /**
     * ID と支払い項目 ID で支払い記録を取得する（ロック付き）。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<MemberPaymentEntity> findByIdAndPaymentItemId(Long id, Long paymentItemId);
}
