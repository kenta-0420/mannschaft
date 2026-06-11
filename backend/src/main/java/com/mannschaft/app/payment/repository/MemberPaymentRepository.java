package com.mannschaft.app.payment.repository;

import com.mannschaft.app.payment.PaymentStatus;
import com.mannschaft.app.payment.entity.MemberPaymentEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
     * 指定チームの代表（ADMIN/DEPUTY_ADMIN）のいずれかが、当該 payment_item に対して
     * 有効な PAID レコードを持つかを判定する（F08.7.1/07 大会参加費の未払いゲート用）。
     *
     * <p>大会参加費は「チーム単位の費用を代表が支払う」モデルのため、チームの支払い済み判定は
     * 「team の ADMIN/DEPUTY_ADMIN のいずれかが払っているか」で行う。クロスドメインは ID 参照の
     * JOIN のみ（原則1）。{@code validUntil} による grace_period / 有効期限も考慮する。</p>
     */
    @Query(value = "SELECT COUNT(*) > 0 FROM member_payments mp " +
            "JOIN user_roles ur ON ur.user_id = mp.user_id AND ur.team_id = :teamId " +
            "JOIN roles r ON r.id = ur.role_id " +
            "WHERE mp.payment_item_id = :paymentItemId " +
            "  AND mp.status = 'PAID' " +
            "  AND (mp.valid_until IS NULL OR mp.valid_until >= CURRENT_DATE) " +
            "  AND r.name IN ('ADMIN', 'DEPUTY_ADMIN')",
            nativeQuery = true)
    boolean existsValidPaidPaymentByTeamRepresentative(@Param("teamId") Long teamId,
                                                       @Param("paymentItemId") Long paymentItemId);

    /**
     * 受益者×項目の有効な PAID レコードを 1 件取得する（F08.9 P2 後見まとめ払いの paidBy 解決用）。
     *
     * <p>{@link #existsValidPaidPayment(Long, Long)} が真のとき、誰が払ったか（payer_user_id）・いつ払ったか
     * （paid_at）を表示するために用いる。有効期限（validUntil）が切れていない PAID を支払い日時の新しい順で 1 件返す。
     * 複数の有効 PAID が存在しうる不整合データでは最新の支払いを採る。</p>
     */
    @Query("SELECT mp FROM MemberPaymentEntity mp " +
            "WHERE mp.userId = :userId AND mp.paymentItemId = :paymentItemId " +
            "AND mp.status = 'PAID' " +
            "AND (mp.validUntil IS NULL OR mp.validUntil >= CURRENT_DATE) " +
            "ORDER BY mp.paidAt DESC, mp.createdAt DESC")
    List<MemberPaymentEntity> findValidPaidPayments(@Param("userId") Long userId,
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
     * 支払い項目の期限切れ PAID 件数を取得する（F08.9 P8 サマリー拡張）。
     *
     * <p>valid_until が過去日（&lt; 指定日）かつ status = PAID の件数を返す。
     * valid_until が NULL の場合（ITEM/DONATION 等の永続タイプ）は期限切れに該当しない。</p>
     */
    @Query("SELECT COUNT(mp) FROM MemberPaymentEntity mp " +
            "WHERE mp.paymentItemId = :paymentItemId " +
            "AND mp.status = 'PAID' " +
            "AND mp.validUntil IS NOT NULL " +
            "AND mp.validUntil < :referenceDate")
    long countExpiredPaidByPaymentItemId(@Param("paymentItemId") Long paymentItemId,
                                         @Param("referenceDate") java.time.LocalDate referenceDate);

    /**
     * ユーザーの全支払い記録を取得する（チーム/組織横断）。
     */
    List<MemberPaymentEntity> findByUserId(Long userId);

    /**
     * 物理削除バッチ用: 退会ユーザーのuserIdをSENTINEL_USER_IDに差し替える（匿名化）。
     */
    @Modifying
    @Query("UPDATE MemberPaymentEntity mp SET mp.userId = :sentinelId WHERE mp.userId = :userId")
    int anonymizeUserId(@Param("userId") Long userId, @Param("sentinelId") Long sentinelId);

    /**
     * 孤児補正バッチ用: 退会済みユーザー（users テーブルに存在しない）の
     * member_payments.user_id を sentinelUserId に置換する。
     * AccountPurgedEvent 処理漏れ検出・補正のために夜次バッチから呼ぶ。
     */
    @Modifying
    @Query(value = """
            UPDATE member_payments mp
            LEFT JOIN users u ON mp.user_id = u.id
            SET mp.user_id = :sentinelUserId
            WHERE mp.user_id != :sentinelUserId AND u.id IS NULL
            """, nativeQuery = true)
    int anonymizeOrphanUserId(@Param("sentinelUserId") Long sentinelUserId);

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

    // === F08.9 P1 Wave2: 払い手分離・money rail 連結クエリ（V74.001 追加列対応）===

    /**
     * escrow_transaction_id で支払い記録を取得する（F22.1 money rail 連結用）。
     *
     * <p>Connect 決済完了時に escrow から member_payment へ遡ってステータスを更新する際に使用する。
     * NULL の場合は手動記録のため本メソッドで引くことはない。
     * ペイウォール判定は引き続き {@link #existsValidPaidPayment(Long, Long)} を使うこと。</p>
     */
    Optional<MemberPaymentEntity> findByEscrowTransactionId(UUID escrowTransactionId);

    /**
     * payer_user_id で支払い記録一覧を取得する（払い手視点の履歴表示用）。
     *
     * <p>受益者視点の履歴は {@link #findByUserId(Long)} を使うこと。</p>
     */
    List<MemberPaymentEntity> findByPayerUserId(Long payerUserId);

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
