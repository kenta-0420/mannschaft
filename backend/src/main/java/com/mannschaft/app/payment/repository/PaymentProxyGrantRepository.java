package com.mannschaft.app.payment.repository;

import com.mannschaft.app.common.repository.AbstractTenantAwareRepository;
import com.mannschaft.app.payment.PaymentProxyGrantStatus;
import com.mannschaft.app.payment.entity.PaymentProxyGrantEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 第三者代理払い許可リポジトリ。
 *
 * <p>テナントスコープのクエリは {@link AbstractTenantAwareRepository} 基底を利用する。
 * ペイウォールゲート（支払い可否判定）に使う主要クエリを提供する。</p>
 *
 * <p>設計書: docs/features/F08.9_membership_billing_paywall/01_data_model.md §2.3</p>
 */
public interface PaymentProxyGrantRepository
        extends AbstractTenantAwareRepository<PaymentProxyGrantEntity, UUID> {

    /**
     * 受益者・払い手・対象項目・有効期間で ACTIVE な grant を 1 件引き当てる（ペイウォールゲート用）。
     *
     * <p>ゲート判定: 支払い前に「この払い手がこの受益者のこの項目を払ってよいか」を確認する際に使用する。
     * {@code payment_item_id IS NULL}（包括 grant）も対象に含めるため、
     * {@code (ppg.paymentItemId = :paymentItemId OR ppg.paymentItemId IS NULL)} で絞り込む。
     * 有効期間は {@code :now} が {@code [effectiveFrom, effectiveUntil]} の範囲内であることを確認。
     * {@code effectiveUntil IS NULL} は無期限 grant（取消まで有効）を示す。</p>
     *
     * @param beneficiaryUserId 受益者ユーザーID
     * @param payerUserId       払い手ユーザーID
     * @param paymentItemId     支払い対象の payment_items.id
     * @param now               判定基準日時（通常は LocalDateTime.now()）
     * @return ACTIVE かつ有効期間内の grant（存在しない場合は empty）
     */
    @Query("SELECT ppg FROM PaymentProxyGrantEntity ppg " +
            "WHERE ppg.beneficiaryUserId = :beneficiaryUserId " +
            "AND ppg.payerUserId = :payerUserId " +
            "AND (ppg.paymentItemId = :paymentItemId OR ppg.paymentItemId IS NULL) " +
            "AND ppg.status = :status " +
            "AND ppg.effectiveFrom <= :now " +
            "AND (ppg.effectiveUntil IS NULL OR ppg.effectiveUntil >= :now)")
    Optional<PaymentProxyGrantEntity> findActiveGrant(
            @Param("beneficiaryUserId") Long beneficiaryUserId,
            @Param("payerUserId") Long payerUserId,
            @Param("paymentItemId") Long paymentItemId,
            @Param("status") PaymentProxyGrantStatus status,
            @Param("now") LocalDateTime now);

    /**
     * 受益者のすべての grant をステータスで取得する（受益者視点の管理画面用）。
     */
    List<PaymentProxyGrantEntity> findByBeneficiaryUserIdAndStatus(Long beneficiaryUserId,
                                                                    PaymentProxyGrantStatus status);

    /**
     * 払い手のすべての grant をステータスで取得する（払い手視点の管理画面用）。
     */
    List<PaymentProxyGrantEntity> findByPayerUserIdAndStatus(Long payerUserId,
                                                              PaymentProxyGrantStatus status);

    /**
     * 日次バッチ用: effective_until が指定日時より前で ACTIVE な grant を取得する（期限切れ掃き用）。
     */
    List<PaymentProxyGrantEntity> findByStatusAndEffectiveUntilBefore(PaymentProxyGrantStatus status,
                                                                       LocalDateTime threshold);
}
