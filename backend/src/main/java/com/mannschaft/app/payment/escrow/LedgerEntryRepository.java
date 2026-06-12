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

    /**
     * 当該 escrow に既に立っている <b>回収実行（A 陣）</b>の純額（{@code RECOVERY/D/PAYEE} − {@code RECOVERY/C/PAYEE}）を返す
     * （§6.3 第四陣 A・冪等判定＋再返金時の再計上額）。
     *
     * <p><b>仕訳の向きで C1/C2 と峻別する:</b> C1/C2 の RECOVERY は「未回収の発生」で {@code D PLATFORM_FEE = C PAYEE}
     * （C PAYEE 側）。A 陣の RECOVERY は「回収の実行」で {@code D PAYEE = C PLATFORM_FEE}（D PAYEE 側）。さらに A 陣で
     * 回収した charge が ModeB 返金されたときの<b>再計上（回収を無かったことにする）</b>は逆仕訳
     * {@code D PLATFORM_FEE = C PAYEE}（C PAYEE 側）で打ち消す。よって本 escrow の「今いくら回収済みで残っているか」は
     * {@code RECOVERY×PAYEE} の {@code D 合計 − C 合計} で求まる:</p>
     * <ul>
     *   <li>回収実行のみ（未返金）→ {@code D PAYEE} のみ → 正値（= 現在回収中の額）。</li>
     *   <li>回収後に再計上（ModeB 返金で打ち消し済み）→ {@code D − C = 0} → 二重再計上しない。</li>
     *   <li>回収実行なし（通常 charge・outstanding=0）→ 0 → 上乗せ未適用＝冪等で再回収しない。</li>
     * </ul>
     *
     * <p>{@code COALESCE} で 0 を返し null を避ける（行が 1 件もない escrow でも 0）。
     * JPQL 本体には注記を書かない（HQL パース事故回避・コメントは本 Javadoc に集約）。</p>
     *
     * @param escrowId 対象 escrow
     * @return 当該 escrow に現在計上されている回収実行の純額（minor・0 なら未回収/打ち消し済み）
     */
    @Query("SELECT COALESCE(SUM(CASE WHEN l.direction = com.mannschaft.app.payment.escrow.LedgerDirection.D "
            + "THEN l.amount ELSE -l.amount END), 0) FROM LedgerEntryEntity l "
            + "WHERE l.escrowTransactionId = :escrowId "
            + "AND l.entryType = com.mannschaft.app.payment.escrow.LedgerEntryType.RECOVERY "
            + "AND l.account = com.mannschaft.app.payment.escrow.LedgerAccount.PAYEE")
    long sumAppliedRecoveryNetOnEscrow(@Param("escrowId") UUID escrowId);
}
