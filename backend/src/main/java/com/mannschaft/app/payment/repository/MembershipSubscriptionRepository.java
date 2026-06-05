package com.mannschaft.app.payment.repository;

import com.mannschaft.app.common.repository.AbstractTenantAwareRepository;
import com.mannschaft.app.payment.MembershipSubscriptionStatus;
import com.mannschaft.app.payment.entity.MembershipSubscriptionEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 会員継続課金リポジトリ（membership_subscriptions）。
 *
 * <p>テナント（organization_id）スコープのため {@link AbstractTenantAwareRepository} を継承する
 * （CLAUDE.md 原則7）。払い手視点の一覧・チーム（受領主体）視点の一覧・Webhook の subscription 引き当てを提供する。</p>
 *
 * <p>設計書: docs/features/F08.9_membership_billing_paywall/01_data_model.md §2.1 / 02_api_design.md §4</p>
 */
public interface MembershipSubscriptionRepository
        extends AbstractTenantAwareRepository<MembershipSubscriptionEntity, UUID> {

    /**
     * ID で引く（論理削除を除外）。解約/スキップ/再開で使用する。
     */
    Optional<MembershipSubscriptionEntity> findByIdAndDeletedAtIsNull(UUID id);

    /**
     * Stripe Subscription ID で引く（論理削除を除外）。Webhook（invoice.* / subscription.deleted）の引き当て。
     */
    Optional<MembershipSubscriptionEntity> findByStripeSubscriptionIdAndDeletedAtIsNull(String stripeSubscriptionId);

    /**
     * Stripe Subscription ID で引き、行を {@code PESSIMISTIC_WRITE} ロックして取得する（Webhook の read-then-write 直列化）。
     *
     * <p>継続課金 Webhook（{@code invoice.paid} の状態遷移＋current_period 更新／{@code invoice.payment_failed}／
     * {@code subscription.deleted}）は「subscription を read → status 書き換え」を行う。同一 subscription への
     * 並行/再送イベントが AUTHORIZED 状態を同時に読むと二重遷移しうるため、行ロックで直列化する（設計書 02 §4.2・
     * EscrowWebhookService の流儀）。論理削除を除外する。
     *
     * <p>JPQL 本体には注記を書かない（HQL パース事故回避・コメントは本 Javadoc に集約する）。</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM MembershipSubscriptionEntity s "
            + "WHERE s.stripeSubscriptionId = :stripeSubscriptionId AND s.deletedAt IS NULL")
    Optional<MembershipSubscriptionEntity> findByStripeSubscriptionIdForUpdate(
            @Param("stripeSubscriptionId") String stripeSubscriptionId);

    /**
     * ID で引き、行を {@code PESSIMISTIC_WRITE} ロックして取得する（初回 charge CAPTURED 経由の PENDING→ACTIVE 直列化）。
     *
     * <p>初回単発 charge の CAPTURED（{@code EscrowCapturedEvent} 経由）と、稀に先着しうる次サイクル
     * {@code invoice.paid} が同一 subscription を同時に PENDING で読むと二重 ACTIVE 化しうるため行ロックで直列化する。
     * 論理削除を除外する。</p>
     *
     * <p>JPQL 本体には注記を書かない（HQL パース事故回避・コメントは本 Javadoc に集約する）。</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM MembershipSubscriptionEntity s WHERE s.id = :id AND s.deletedAt IS NULL")
    Optional<MembershipSubscriptionEntity> findByIdForUpdate(@Param("id") UUID id);

    /**
     * 払い手視点の継続課金一覧（指定状態・idx_ms_payer で引く）。
     * 「自分が払い手の継続課金一覧」API の本体。
     */
    List<MembershipSubscriptionEntity> findByPayerUserIdAndStatusInAndDeletedAtIsNullOrderByCreatedAtDesc(
            Long payerUserId, Collection<MembershipSubscriptionStatus> statuses);

    /**
     * 払い手視点の継続課金一覧（全状態・idx_ms_payer で引く）。
     */
    List<MembershipSubscriptionEntity> findByPayerUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(Long payerUserId);

    /**
     * 受益者視点の継続課金一覧（idx_ms_beneficiary で引く）。受益者の有効サブスク確認に使用する。
     */
    List<MembershipSubscriptionEntity> findByBeneficiaryUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(
            Long beneficiaryUserId);

    /**
     * 受益者×項目に、終端でない（指定状態の）継続課金が存在するか（subscribe の二重加入防止・冪等）。
     *
     * <p>{@code statuses=[PENDING, ACTIVE, PAST_DUE]} で呼び、既に有効/移行中のサブスクがあれば
     * {@code SUBSCRIPTION_ALREADY_EXISTS}（409）で拒否する。CANCELLED/EXPIRED の終端行は対象外（再加入可）。</p>
     */
    boolean existsByBeneficiaryUserIdAndPaymentItemIdAndStatusInAndDeletedAtIsNull(
            Long beneficiaryUserId, Long paymentItemId, Collection<MembershipSubscriptionStatus> statuses);

    /**
     * 受領主体（チーム/組織）の継続課金一覧（指定状態・idx_ms_org / scope で引く）。
     * 管理者向けのチーム/組織継続課金一覧 API の本体。
     */
    List<MembershipSubscriptionEntity> findByScopeKindAndScopeIdAndStatusInAndDeletedAtIsNullOrderByCreatedAtDesc(
            com.mannschaft.app.payment.connect.ScopeKind scopeKind, Long scopeId,
            Collection<MembershipSubscriptionStatus> statuses);

    /**
     * 受領主体（チーム/組織）の継続課金一覧（全状態）。
     */
    List<MembershipSubscriptionEntity> findByScopeKindAndScopeIdAndDeletedAtIsNullOrderByCreatedAtDesc(
            com.mannschaft.app.payment.connect.ScopeKind scopeKind, Long scopeId);
}
