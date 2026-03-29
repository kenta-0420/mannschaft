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
     * 支払い項目の未払い（PENDING）ユーザーIDリストを取得する。
     */
    @Query("SELECT mp.userId FROM MemberPaymentEntity mp WHERE mp.paymentItemId = :paymentItemId AND mp.status = 'PENDING'")
    List<Long> findUnpaidUserIdsByPaymentItemId(@Param("paymentItemId") Long paymentItemId);

    /**
     * ID と支払い項目 ID で支払い記録を取得する（ロック付き）。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<MemberPaymentEntity> findByIdAndPaymentItemId(Long id, Long paymentItemId);

    // === Analytics 集計用クエリ ===

    /**
     * 指定日に支払われた PAID レコードの合計額を取得する。
     */
    @Query("SELECT COALESCE(SUM(mp.amountPaid), 0) FROM MemberPaymentEntity mp " +
            "WHERE mp.status = 'PAID' AND CAST(mp.paidAt AS localdate) = :date")
    java.math.BigDecimal sumPaidAmountByDate(@Param("date") java.time.LocalDate date);

    /**
     * 指定日に返金された REFUNDED レコードの合計額を取得する。
     */
    @Query("SELECT COALESCE(SUM(mp.amountPaid), 0) FROM MemberPaymentEntity mp " +
            "WHERE mp.status = 'REFUNDED' AND CAST(mp.refundedAt AS localdate) = :date")
    java.math.BigDecimal sumRefundedAmountByDate(@Param("date") java.time.LocalDate date);

    /**
     * 指定日に支払われた PAID レコードの件数を取得する。
     */
    @Query("SELECT COUNT(mp) FROM MemberPaymentEntity mp " +
            "WHERE mp.status = 'PAID' AND CAST(mp.paidAt AS localdate) = :date")
    int countPaidByDate(@Param("date") java.time.LocalDate date);

    /**
     * 指定日時点で有効な PAID レコードを持つユニークユーザー数を取得する。
     */
    @Query("SELECT COUNT(DISTINCT mp.userId) FROM MemberPaymentEntity mp " +
            "WHERE mp.status = 'PAID' " +
            "AND (mp.validUntil IS NULL OR mp.validUntil >= :date) " +
            "AND (mp.validFrom IS NULL OR mp.validFrom <= :date)")
    int countDistinctPayingUsersByDate(@Param("date") java.time.LocalDate date);

    /**
     * 指定月に支払われた PAID レコードの合計額をコホート用に取得する（ユーザーID群指定）。
     */
    @Query("SELECT COALESCE(SUM(mp.amountPaid), 0) FROM MemberPaymentEntity mp " +
            "WHERE mp.status = 'PAID' " +
            "AND mp.userId IN :userIds " +
            "AND CAST(mp.paidAt AS localdate) BETWEEN :monthStart AND :monthEnd")
    java.math.BigDecimal sumPaidAmountByUserIdsAndMonth(
            @Param("userIds") List<Long> userIds,
            @Param("monthStart") java.time.LocalDate monthStart,
            @Param("monthEnd") java.time.LocalDate monthEnd);
}
