package com.mannschaft.app.recruitment.repository;

import com.mannschaft.app.recruitment.CancellationPaymentStatus;
import com.mannschaft.app.recruitment.dto.RecruitmentCancellationRecordSummaryResponse;
import com.mannschaft.app.recruitment.entity.RecruitmentCancellationRecordEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * F03.11 募集型予約: キャンセル記録リポジトリ (Phase 5a)。
 */
public interface RecruitmentCancellationRecordRepository extends JpaRepository<RecruitmentCancellationRecordEntity, Long> {

    /**
     * §5.2 ステップ5 申込時の未払いキャンセル料チェック。
     */
    boolean existsByUserIdAndPaymentStatusIn(Long userId, Collection<CancellationPaymentStatus> statuses);

    List<RecruitmentCancellationRecordEntity> findByUserIdOrderByCancelledAtDesc(Long userId);

    List<RecruitmentCancellationRecordEntity> findByListingIdOrderByCancelledAtDesc(Long listingId);

    /**
     * §Phase5a 決済リトライバッチ: FAILED かつリトライ回数が上限未満のレコードを
     * <b>キーセットページング</b>（{@code id > cursor}）で id 昇順に取得する。
     *
     * <p>このバッチはループ内で {@code paymentRetryCount} をインクリメントするため、
     * 上限に達した行はその場で絞り込み（{@code paymentRetryCount < :maxRetries}）から
     * 外れる。OFFSET ページングで「ページ番号を進める」方式にすると、母集合が縮んだ分
     * だけ後続の行が OFFSET の網から漏れて読み飛ばされる。カーソルを直前チャンクの
     * 最終 {@code id} まで前進させることで、この読み飛ばしを防ぐ。</p>
     *
     * <p>ソートキーを {@code cancelledAt}（一意でない）から {@code id}（一意）へ変更している。
     * {@code cancelledAt} はキーセットのカーソルとして使えない（同一値の行が複数存在し得ると
     * カーソル前進の一意性を保証できない）ため。リトライ処理は1件ごとに独立しており
     * 処理順序に依存しない（順序依存が無いことは呼び出し元バッチのコメント参照）。</p>
     *
     * @param maxRetries リトライ上限回数
     * @param cursor     直前チャンクの最終 ID（初回は 0）
     * @param pageable   ページング情報（サイズのみ使用。ソートは本クエリで固定）
     */
    @Query("""
            SELECT r FROM RecruitmentCancellationRecordEntity r
            WHERE r.paymentStatus = com.mannschaft.app.recruitment.CancellationPaymentStatus.FAILED
              AND r.paymentRetryCount < :maxRetries
              AND r.id > :cursor
            ORDER BY r.id ASC
            """)
    List<RecruitmentCancellationRecordEntity> findFailedForRetryAfterId(
            @Param("maxRetries") int maxRetries,
            @Param("cursor") Long cursor,
            Pageable pageable);

    /**
     * §12（免除 UI）一覧: {@code SYSTEM_ADMIN} 向け全件一覧。
     *
     * <p>受取先による絞り込みは行わない（呼び出し元が {@code SYSTEM_ADMIN} であることは
     * Service 層で検証済みの前提）。</p>
     */
    @Query("""
            SELECT new com.mannschaft.app.recruitment.dto.RecruitmentCancellationRecordSummaryResponse(
                r.id, r.listingId, l.title, r.participantId, r.userId,
                r.feeAmount, CAST(r.paymentStatus AS string), r.cancelledAt, r.hoursBeforeStart)
            FROM RecruitmentCancellationRecordEntity r, RecruitmentListingEntity l
            WHERE l.id = r.listingId
              AND r.paymentStatus IN :statuses
            ORDER BY r.cancelledAt DESC
            """)
    Page<RecruitmentCancellationRecordSummaryResponse> findAllForSystemAdmin(
            @Param("statuses") Collection<CancellationPaymentStatus> statuses,
            Pageable pageable);

    /**
     * §12（免除 UI）一覧: 受取先側の管理者・受取先本人向けの絞り込み一覧。
     *
     * <p><b>この絞り込みは payment ドメイン（escrow）を一切読まない。</b> 受取先の判定は
     * recruitment 自身が持つ {@code recruitment_listings.payeeKind}/{@code payeeUserId}/
     * {@code scopeId} だけで行う（listingId で JOIN。§3.4 のクロスドメイン禁止に抵触しない
     * ——recruitment が読むのは recruitment 自身のテーブルである）。</p>
     *
     * <p><b>これは「絞り込まれた閲覧」に過ぎず、認可の最終的な権威ではない。</b> listing の
     * 受取先情報と escrow（実際に金を受け取る Connect account）の受取先が万が一食い違っても、
     * ここで見えるだけでは免除できない——免除の実行は {@code RecruitmentCancellationFeeWaiveService}
     * が payment ドメインの {@code ConnectChargeService#isPayeeSettlementManager} で必ず再検証する。
     * 逆（一覧で絞ったから免除側の検証を省略する）は禁止。</p>
     *
     * @param actorUserId  操作者ユーザー ID（{@code payeeKind=USER} の一致判定に使用）
     * @param teamScopeIds 操作者が精算管理者である TEAM の scopeId 集合（無ければ空集合可）
     * @param orgScopeIds  操作者が精算管理者である ORG の scopeId 集合（無ければ空集合可）
     * @param statuses     絞り込む決済ステータス
     */
    @Query("""
            SELECT new com.mannschaft.app.recruitment.dto.RecruitmentCancellationRecordSummaryResponse(
                r.id, r.listingId, l.title, r.participantId, r.userId,
                r.feeAmount, CAST(r.paymentStatus AS string), r.cancelledAt, r.hoursBeforeStart)
            FROM RecruitmentCancellationRecordEntity r, RecruitmentListingEntity l
            WHERE l.id = r.listingId
              AND r.paymentStatus IN :statuses
              AND (
                   (l.payeeKind = 'USER' AND l.payeeUserId = :actorUserId)
                OR (l.payeeKind = 'TEAM' AND l.scopeId IN :teamScopeIds)
                OR (l.payeeKind = 'ORG' AND l.scopeId IN :orgScopeIds)
              )
            ORDER BY r.cancelledAt DESC
            """)
    Page<RecruitmentCancellationRecordSummaryResponse> findVisibleToPayee(
            @Param("actorUserId") Long actorUserId,
            @Param("teamScopeIds") Collection<Long> teamScopeIds,
            @Param("orgScopeIds") Collection<Long> orgScopeIds,
            @Param("statuses") Collection<CancellationPaymentStatus> statuses,
            Pageable pageable);
}
