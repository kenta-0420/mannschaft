package com.mannschaft.app.payment.escrow;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
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
 * <p>エスクロー取引の引き当ては {@code ConnectChargeService} から利用される。</p>
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

    /**
     * 出所（source_kind）と複数の source_id に紐づく取引を<b>一括で</b>取得する。
     *
     * <p>一覧画面が「この行の受取先は誰か」を行ごとに問い合わせると、ページ内の件数ぶんの
     * ラウンドトリップになる。本 finder は 1 往復で解決するためにある
     * （{@link ConnectChargeService#filterPayeeSettlementManaged} が利用）。
     * {@code source_participant_id} での絞り込みは呼び出し側がメモリ上で行う
     * （組の数だけ OR 条件を並べるより、source_id の IN 一発の方が索引が効く）。</p>
     */
    List<EscrowTransactionEntity> findBySourceKindAndSourceIdIn(
            EscrowSourceKind sourceKind, java.util.Collection<Long> sourceIds);

    /** テナント（organization_id）スコープの取引を取得する。 */
    List<EscrowTransactionEntity> findByOrganizationId(Long organizationId);

    /** 状態ごとの取引を取得する（自動 capture/cancel バッチ用）。 */
    List<EscrowTransactionEntity> findByStatus(EscrowStatus status);

    /**
     * 指定状態かつ {@code captured_at} が期間 {@code [from, to)} に入る取引を取得する
     * （日次純益突合バッチ用・第三陣 C2・設計書 02 §6.3）。
     *
     * <p>{@code application_fee_amount}（5% 徴収）と Stripe 実手数料（{@code balance_transaction.fee}）の差＝
     * Mannschaft 純益を日次集計する母集合。capture 済（CAPTURED 以降）取引のみが純益確定対象のため
     * {@code captured_at} で窓を切る。{@code escrow_transactions} は {@code deleted_at} を持たない（物理保持）。</p>
     */
    List<EscrowTransactionEntity> findByStatusAndCapturedAtGreaterThanEqualAndCapturedAtLessThan(
            EscrowStatus status, LocalDateTime from, LocalDateTime to);

    /**
     * 指定状態かつ {@code created_at} が基準時刻より前（=確認猶予超過）の取引を取得する（escrow ライフサイクル
     * バッチ用・第三陣）。{@link EscrowStatus#PENDING_CONFIRMATION}（札主未 confirm 放置）の自動取消抽出に用いる。
     *
     * <p>{@code hold_expires_at} は AUTHORIZED 昇格時（札主 confirm 後）にのみ刻まれ PENDING_CONFIRMATION では
     * NULL のため、確認放置は {@code created_at} 起点の経過時間で判定する（第一陣 status 意味論の根治と整合）。</p>
     */
    List<EscrowTransactionEntity> findByStatusAndCreatedAtBefore(EscrowStatus status, LocalDateTime createdBefore);

    /**
     * 指定状態かつ {@code hold_expires_at} が基準時刻以前（=hold 失効/間近）の取引を取得する（escrow ライフサイクル
     * バッチ用・第三陣・設計書 02 §5.2 / §5.4）。{@link EscrowStatus#HELD}（onboarding 未完で 72h 超過）および
     * {@link EscrowStatus#AUTHORIZED}（与信失効間近で未 capture）の抽出に用いる。
     */
    List<EscrowTransactionEntity> findByStatusAndHoldExpiresAtLessThanEqual(
            EscrowStatus status, LocalDateTime threshold);

    /**
     * 受取側 Connect 口座（{@code payee_connect_account_id} 論理参照）に紐づく指定状態の取引を取得する
     * （HELD 昇格用・第三陣・設計書 02 §5.2）。{@code account.updated} で payouts_enabled が true へ遷移した
     * connect_account を payee とする {@link EscrowStatus#HELD} escrow を昇格対象として引く。
     */
    List<EscrowTransactionEntity> findByPayeeConnectAccountIdAndStatus(UUID payeeConnectAccountId, EscrowStatus status);

    /**
     * 受取側 Connect アカウント（{@code payee_connect_account_id}）の指定月における
     * {@code application_fee_amount} の合計を返す（月次手数料明細用）。
     *
     * <p>F08.9 P8 月次手数料明細: チームが受取側として関与した謝礼取引の手数料を当月単位で集計する。
     * {@code escrow_transactions} は {@code deleted_at} を持たない（監査証跡として物理保持）ため、
     * 全ステータスの取引を対象とする。Connect アカウント UUID への解決は呼び出し側（Service 層）で行う。</p>
     *
     * @param payeeConnectAccountId 受取側 Connect アカウントの UUID
     * @param year                  集計対象年（西暦）
     * @param month                 集計対象月（1〜12）
     * @return 当月合計手数料（件数が 0 件の場合は 0）
     */
    @Query("SELECT COALESCE(SUM(e.applicationFeeAmount), 0) FROM EscrowTransactionEntity e " +
           "WHERE e.payeeConnectAccountId = :payeeConnectAccountId " +
           "AND YEAR(e.createdAt) = :year AND MONTH(e.createdAt) = :month")
    Long sumApplicationFeeByPayeeConnectAccountAndPeriod(
            @Param("payeeConnectAccountId") UUID payeeConnectAccountId,
            @Param("year") int year,
            @Param("month") int month);

    /**
     * 受取側 Connect 口座（{@code payee_connect_account_id} 論理参照）に紐づく取引をページングで取得する
     * （受取側エスクロー一覧 EP・フォロー Wave A・設計書 02 §1 / 03 §1）。{@code created_at} 降順で返す
     * （新しい取引が先頭）。返金管理画面が「受け取った謝礼」を一覧するための finder。
     */
    org.springframework.data.domain.Page<EscrowTransactionEntity>
            findByPayeeConnectAccountIdOrderByCreatedAtDesc(
                    UUID payeeConnectAccountId, org.springframework.data.domain.Pageable pageable);

    /**
     * 受取側 Connect 口座＋状態でフィルタした取引をページングで取得する（受取側エスクロー一覧 EP の status
     * フィルタ・フォロー Wave A）。{@code created_at} 降順で返す。
     */
    org.springframework.data.domain.Page<EscrowTransactionEntity>
            findByPayeeConnectAccountIdAndStatusOrderByCreatedAtDesc(
                    UUID payeeConnectAccountId, EscrowStatus status,
                    org.springframework.data.domain.Pageable pageable);
}
