package com.mannschaft.app.payment.escrow;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * F22.1 謝礼決済: 複式記帳台帳リポジトリ（追記専用）。
 *
 * <p>記帳行は追記専用（UPDATE/DELETE しない）。第三陣 C2（リコンシリエーション）で複式検算・
 * ModeB 補完候補の抽出に用いる読み取りクエリを追加する（設計書 02 §6.3）。</p>
 */
public interface LedgerEntryRepository extends JpaRepository<LedgerEntryEntity, UUID> {

    /** 取引に紐づく記帳行を追記順に取得する（整合検算用）。 */
    List<LedgerEntryEntity> findByEscrowTransactionIdOrderByCreatedAtAsc(UUID escrowTransactionId);

    /**
     * 記帳が 1 件以上ある（=金が動いた）全取引の {@code escrow_transaction_id} を重複なく返す（複式検算の母集合・§6.3 (b)）。
     *
     * <p>複式検算（借方合計＝貸方合計）は記帳のある取引のみが対象。escrow テーブル全件ではなく
     * 台帳に行が立った取引に限定することで、未記帳（与信のみ等）を検算対象から外す。
     * 件数は決済発生取引に比例し、15 分間隔バッチの母集合として現実的な規模に収まる。</p>
     *
     * <p>JPQL 本体には注記を書かない（HQL パース事故回避・コメントは本 Javadoc に集約）。</p>
     */
    @Query("SELECT DISTINCT l.escrowTransactionId FROM LedgerEntryEntity l")
    List<UUID> findDistinctEscrowTransactionIds();

    /**
     * ModeB（受取側負担）返金が記帳済みだが {@link LedgerEntryType#RECOVERY} 仕訳が未計上の取引 ID を返す
     * （C1 で balance_transaction 未確定により実手数料計上を先送りした「補完待ち」候補・§6.3 (a)）。
     *
     * <p><b>識別ロジック（症状を隠さない・marker 列を増やさない導出）:</b> ModeB 返金は記帳上
     * {@code D PAYER（REFUND）} を必ず持つ（ModeA は {@code D PAYEE / C PAYER}）。C1 が実手数料を計上できた取引には
     * {@link LedgerEntryType#RECOVERY} 行が存在する。よって「ModeB 返金記帳あり（{@code REFUND/D/PAYER}）かつ
     * {@code RECOVERY} 行なし」が補完待ち候補となる。balance_transaction が確定し次第、C1 と同一会計で補完する。</p>
     *
     * <p>JPQL 本体には注記を書かない（HQL パース事故回避・コメントは本 Javadoc に集約）。</p>
     */
    @Query("SELECT DISTINCT l.escrowTransactionId FROM LedgerEntryEntity l "
            + "WHERE l.entryType = com.mannschaft.app.payment.escrow.LedgerEntryType.REFUND "
            + "AND l.direction = com.mannschaft.app.payment.escrow.LedgerDirection.D "
            + "AND l.account = com.mannschaft.app.payment.escrow.LedgerAccount.PAYER "
            + "AND l.escrowTransactionId NOT IN ("
            + "  SELECT r.escrowTransactionId FROM LedgerEntryEntity r "
            + "  WHERE r.entryType = com.mannschaft.app.payment.escrow.LedgerEntryType.RECOVERY)")
    List<UUID> findModeBRefundEscrowsWithoutRecovery();

    /**
     * 指定取引の ModeB 返金で支払者へ戻したグロス額（{@code REFUND/D/PAYER} の金額）を Stripe Refund ID 単位で返す
     * （補完時の比例計上の分子＝C1 と同一基準を記帳から復元する・§6.3 (a)）。
     *
     * <p>1 取引に複数回の部分 ModeB 返金がありうるため Refund ID（{@code stripe_object_id}）ごとに 1 行で返す。
     * C1 の {@code recordModeBStripeFeeRecovery} は各返金の {@code grossRefund} に比例して計上したため、補完でも
     * 同じ分子（{@code D PAYER REFUND} の額）を返金ごとに用いることで重複・取りこぼしなく一致させる。</p>
     *
     * <p>JPQL 本体には注記を書かない（HQL パース事故回避・コメントは本 Javadoc に集約）。</p>
     */
    @Query("SELECT l.stripeObjectId, SUM(l.amount) FROM LedgerEntryEntity l "
            + "WHERE l.escrowTransactionId = :escrowId "
            + "AND l.entryType = com.mannschaft.app.payment.escrow.LedgerEntryType.REFUND "
            + "AND l.direction = com.mannschaft.app.payment.escrow.LedgerDirection.D "
            + "AND l.account = com.mannschaft.app.payment.escrow.LedgerAccount.PAYER "
            + "GROUP BY l.stripeObjectId")
    List<Object[]> findModeBGrossRefundByRefundId(@Param("escrowId") UUID escrowId);
}
