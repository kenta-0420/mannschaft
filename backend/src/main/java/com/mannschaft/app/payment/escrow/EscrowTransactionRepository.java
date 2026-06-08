package com.mannschaft.app.payment.escrow;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * 主キーで escrow 行を {@code PESSIMISTIC_WRITE} ロックして取得する（capture/webhook の read-then-write 直列化）。
     *
     * <p>capture（同期フック・AFTER_COMMIT 後）と {@code payment_intent.succeeded} webhook はどちらも
     * 「escrow を read → CAPTURED 書き＋ledger 追記」を行う。無ロックだと両経路が AUTHORIZED を同時に読み、
     * 双方が CAPTURED 書き＋ledger 追記を行う二重記帳の競合が理論上ありうる。本メソッドで行ロックを取得し、
     * ロック取得後に status を再判定（CAPTURED なら no-op）することで read-then-write をアトミック化し、
     * ledger 二重記帳を物理的に防ぐ（設計書 02 §5.3）。</p>
     *
     * <p>JPQL 本体には注記を書かない（HQL パース事故回避・コメントは本 Javadoc に集約する）。</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM EscrowTransactionEntity e WHERE e.id = :id")
    Optional<EscrowTransactionEntity> findByIdForUpdate(@Param("id") UUID id);

    /**
     * PaymentIntent ID から escrow 行を {@code PESSIMISTIC_WRITE} ロックして取得する（webhook の
     * read-then-write 直列化）。{@link #findByIdForUpdate(UUID)} と同じく capture × webhook の競合を防ぐ。
     *
     * <p>JPQL 本体には注記を書かない（HQL パース事故回避・コメントは本 Javadoc に集約する）。</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM EscrowTransactionEntity e WHERE e.stripePaymentIntentId = :paymentIntentId")
    Optional<EscrowTransactionEntity> findByStripePaymentIntentIdForUpdate(
            @Param("paymentIntentId") String paymentIntentId);

    /** 出所（source_kind × source_id）に紐づく取引を取得する。 */
    List<EscrowTransactionEntity> findBySourceKindAndSourceId(
            EscrowSourceKind sourceKind, Long sourceId);

    /**
     * 業務冪等キー（{@code Idempotency-Key} ヘッダ起源）で即時 charge の既存 escrow を取得する（R2-2）。
     *
     * <p>会費（即時 charge）の二重起票防止に用いる。{@code (source_kind, source_id)} は P5（payment_item_id）と
     * P7（team_id）で名前空間が衝突しうるため、呼び出し側が渡す一意な idempotencyKey で dedup する。
     * 同一リクエストの二重送信のみが同一キーを再利用するため、別取引（別キー）は別 escrow になる。</p>
     */
    Optional<EscrowTransactionEntity> findByStripeIdempotencyKey(String stripeIdempotencyKey);

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
