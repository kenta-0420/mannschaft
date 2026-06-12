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
     * <p><b>識別ロジック（recovery_kind で確実に峻別・検分🔴根治後）:</b> ModeB 返金は記帳上
     * {@code D PAYER（REFUND）} を必ず持つ（ModeA は {@code D PAYEE / C PAYER}）。C1 が実手数料を計上できた取引には
     * <b>発生計上の RECOVERY 行</b>（{@link RecoveryKind#C1_ACCRUAL} または {@link RecoveryKind#C2_COMPLETION}）が存在する。
     * よって「ModeB 返金記帳あり（{@code REFUND/D/PAYER}）かつ <b>C1/C2 発生計上 RECOVERY 行なし</b>」が補完待ち候補となる。
     * 「RECOVERY 行なし」ではなく「C1/C2 発生計上なし」で判定するのが要点で、A 経路（回収実行/再計上）の RECOVERY 行が
     * 立っている escrow（他者債務を回収した charge が自己 ModeB 返金された等）でも、自身の C1 が pending で未計上なら
     * 正しく補完候補に拾える。balance_transaction が確定し次第、C1 と同一会計で補完する。</p>
     *
     * <p><b>NULL 安全（{@code NOT IN} ではなく {@code NOT EXISTS} 相関サブクエリ・実 MySQL 冪等）:</b>
     * {@code NOT IN (... サブクエリ ...)} は SQL 三値論理によりサブクエリ結果に 1 件でも NULL が含まれると
     * 全行で UNKNOWN となり「除外が効かず候補に残り続ける」二重補完の温床になる。本クエリは外側の補完済み行を
     * {@code EXISTS} で相関判定し {@code NOT EXISTS} で除外する（NULL を比較値に取らないため三値論理の罠を回避）。
     * これにより C2_COMPLETION 補完後の escrow は確実に候補から外れ、二重補完しない。
     * JPQL 本体には注記を書かない（HQL パース事故回避・コメントは本 Javadoc に集約）。</p>
     */
    @Query("SELECT DISTINCT l.escrowTransactionId FROM LedgerEntryEntity l "
            + "WHERE l.entryType = com.mannschaft.app.payment.escrow.LedgerEntryType.REFUND "
            + "AND l.direction = com.mannschaft.app.payment.escrow.LedgerDirection.D "
            + "AND l.account = com.mannschaft.app.payment.escrow.LedgerAccount.PAYER "
            + "AND NOT EXISTS ("
            + "  SELECT 1 FROM LedgerEntryEntity r "
            + "  WHERE r.escrowTransactionId = l.escrowTransactionId "
            + "  AND r.entryType = com.mannschaft.app.payment.escrow.LedgerEntryType.RECOVERY "
            + "  AND r.recoveryKind IN ("
            + "    com.mannschaft.app.payment.escrow.RecoveryKind.C1_ACCRUAL, "
            + "    com.mannschaft.app.payment.escrow.RecoveryKind.C2_COMPLETION))")
    List<UUID> findModeBRefundEscrowsWithoutRecovery();

    /**
     * 当該 escrow に既に C1/C2 発生計上の {@link LedgerEntryType#RECOVERY} 行が存在するかを返す
     * （{@code completePendingRecovery} のメソッド単位 冪等ガード・§6.3 (a)）。
     *
     * <p>{@link #findModeBRefundEscrowsWithoutRecovery} はバッチ抽出（母集合フィルタ）であり、補完単位処理
     * （{@code completePendingRecovery}）を抽出を経ずに直接呼んだ場合（再実行・テスト・将来の別経路）には
     * 効かない。補完が二重計上しない冪等性を <b>メソッド自身</b>でも担保するため、書込前に「自身が立てる
     * C1_ACCRUAL/C2_COMPLETION の RECOVERY 行が既にあるか」を本クエリで確認し、あれば SKIP する。
     * 抽出条件（C1/C2 発生計上の有無）と同一述語で揃え、抽出側除外と単位処理ガードのズレを作らない。</p>
     *
     * <p>JPQL 本体には注記を書かない（HQL パース事故回避・コメントは本 Javadoc に集約）。</p>
     */
    @Query("SELECT CASE WHEN COUNT(r) > 0 THEN true ELSE false END FROM LedgerEntryEntity r "
            + "WHERE r.escrowTransactionId = :escrowId "
            + "AND r.entryType = com.mannschaft.app.payment.escrow.LedgerEntryType.RECOVERY "
            + "AND r.recoveryKind IN ("
            + "com.mannschaft.app.payment.escrow.RecoveryKind.C1_ACCRUAL, "
            + "com.mannschaft.app.payment.escrow.RecoveryKind.C2_COMPLETION)")
    boolean existsAccrualRecovery(@Param("escrowId") UUID escrowId);

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
     * 当該 escrow に既に立っている <b>回収実行（A 陣）の純額</b>を返す
     * （§6.3 第四陣 A・冪等判定＋再返金時の再計上額・<b>検分🔴根治後の正準実装</b>）。
     *
     * <p><b>recovery_kind で確実に峻別する（向きだけに依存しない）:</b> RECOVERY×PAYEE は勘定の向き（D/C）だけでは
     * C1/C2 発生計上と A 回収実行/再計上を峻別できない。具体的には自己返金（A で回収を上乗せした charge を ModeB 返金）時、
     * 同一 refund 処理内で C1 発生計上（{@code C PAYEE}・その escrow 自身の手数料）と A 再計上（{@code C PAYEE}）が同居し、
     * 旧実装（{@code RECOVERY×PAYEE} の単純 {@code D − C}）は C1 の {@code C PAYEE} を混入して純額を過小評価していた
     * （回収 360 − C1 400 = −40 ≤ 0 → A 再計上が早期 return → 回収済み金が outstanding に戻らず<b>消失</b>）。</p>
     *
     * <p>本クエリは A 経路（{@link RecoveryKind#A_EXECUTION}=回収実行＝{@code D PAYEE}・
     * {@link RecoveryKind#A_RECAPITALIZE}=再計上＝{@code C PAYEE}）<b>のみ</b>を {@code recovery_kind} で抽出し、
     * C1（{@link RecoveryKind#C1_ACCRUAL}）/C2（{@link RecoveryKind#C2_COMPLETION}）の発生計上を確実に除外する。
     * これにより「当該 escrow に上乗せ適用した回収の純額」だけが {@code D − C} で求まる:</p>
     * <ul>
     *   <li>回収実行のみ（未返金）→ A_EXECUTION {@code D PAYEE} のみ → 正値（= 現在回収中の額）。</li>
     *   <li>回収後に再計上（ModeB 返金で打ち消し済み）→ A_EXECUTION D − A_RECAPITALIZE C = 0 → 二重再計上しない。</li>
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
            + "AND l.account = com.mannschaft.app.payment.escrow.LedgerAccount.PAYEE "
            + "AND l.recoveryKind IN ("
            + "com.mannschaft.app.payment.escrow.RecoveryKind.A_EXECUTION, "
            + "com.mannschaft.app.payment.escrow.RecoveryKind.A_RECAPITALIZE)")
    long sumAppliedRecoveryNetOnEscrow(@Param("escrowId") UUID escrowId);
}
