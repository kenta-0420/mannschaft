package com.mannschaft.app.payment.escrow;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * F22.1 謝礼決済: エスクロー取引リポジトリ。
 *
 * <p>{@code escrow_transactions} は監査証跡として物理保持し {@code deleted_at} を持たない
 * （設計書 §3.2）。このため {@code AbstractTenantAwareRepository}（derived query が
 * {@code deletedAt} 前提）は継承できず、テナント絞り込みは {@code organization_id} ベースの
 * derived finder で個別実装する。将来のシャーディングでは organization_id をルーティングキーとする。</p>
 *
 * <p>このフェーズでは Repo 骨格のみ（Service は次陣）。</p>
 */
public interface EscrowTransactionRepository
        extends JpaRepository<EscrowTransactionEntity, UUID> {

    /** PaymentIntent ID（pi_xxx）から逆引きする（Webhook ハンドラ用）。 */
    Optional<EscrowTransactionEntity> findByStripePaymentIntentId(String stripePaymentIntentId);

    /** 出所（source_kind × source_id）に紐づく取引を取得する。 */
    List<EscrowTransactionEntity> findBySourceKindAndSourceId(
            EscrowSourceKind sourceKind, Long sourceId);

    /**
     * 出所＋参加者（source_kind × source_id × source_participant_id）の取引を取得する。
     *
     * <p>与信の冪等判定に用いる（同一応募の二重与信を 1 件に収束させる・設計書 02 §9）。</p>
     */
    Optional<EscrowTransactionEntity> findBySourceKindAndSourceIdAndSourceParticipantId(
            EscrowSourceKind sourceKind, Long sourceId, Long sourceParticipantId);

    /** テナント（organization_id）スコープの取引を取得する。 */
    List<EscrowTransactionEntity> findByOrganizationId(Long organizationId);

    /** 状態ごとの取引を取得する（自動 capture/cancel バッチ用）。 */
    List<EscrowTransactionEntity> findByStatus(EscrowStatus status);
}
