package com.mannschaft.app.recruitment.repository;

import com.mannschaft.app.recruitment.CancellationPaymentStatus;
import com.mannschaft.app.recruitment.dto.RecruitmentCancellationRecordSummaryResponse;
import com.mannschaft.app.recruitment.entity.RecruitmentCancellationRecordEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
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
     * §12（免除 UI）一覧: {@code SYSTEM_ADMIN} 向け全件一覧のチャンク取得（キーセットページング）。
     *
     * <p>受取先による絞り込みは行わない（呼び出し元が {@code SYSTEM_ADMIN} であることは
     * Service 層で検証済みの前提）。</p>
     *
     * <p><b>ソートキーは {@code (cancelledAt DESC, id DESC)} の複合であり一意である。</b>
     * {@code cancelledAt} 単独では同一時刻の行の順序が不定になり、ページ境界で行が重複・欠落する。
     * 一意でない列だけでキーセットのカーソルを進めることはできないため、末尾に主キーを足している。</p>
     *
     * <p>カーソルは「この位置より後（＝より古い側）」を意味する。先頭ページは呼び出し側が
     * 番人値（{@code cancelledAt} に遠未来・{@code id} に {@code Long.MAX_VALUE}）を渡す
     * ——{@code :param IS NULL} を JPQL に書かずに済ませ、条件式を 1 本に保つため。</p>
     *
     * @param statuses         絞り込む決済ステータス
     * @param cursorCancelledAt カーソル位置の {@code cancelledAt}（先頭は遠未来の番人値）
     * @param cursorId         カーソル位置の {@code id}（先頭は {@code Long.MAX_VALUE}）
     * @param pageable         取得件数のみ使用（ソートは本クエリで固定）
     */
    @Query("""
            SELECT new com.mannschaft.app.recruitment.dto.RecruitmentCancellationRecordSummaryResponse(
                r.id, r.listingId, l.title, r.participantId, r.userId,
                r.feeAmount, CAST(r.paymentStatus AS string), r.cancelledAt, r.hoursBeforeStart)
            FROM RecruitmentCancellationRecordEntity r, RecruitmentListingEntity l
            WHERE l.id = r.listingId
              AND r.paymentStatus IN :statuses
              AND (r.cancelledAt < :cursorCancelledAt
                   OR (r.cancelledAt = :cursorCancelledAt AND r.id < :cursorId))
            ORDER BY r.cancelledAt DESC, r.id DESC
            """)
    List<RecruitmentCancellationRecordSummaryResponse> findChunkForSystemAdmin(
            @Param("statuses") Collection<CancellationPaymentStatus> statuses,
            @Param("cursorCancelledAt") LocalDateTime cursorCancelledAt,
            @Param("cursorId") Long cursorId,
            Pageable pageable);

    /**
     * §12（免除 UI）一覧: <b>安価な事前絞り込み</b>としての受取先候補チャンク取得（キーセットページング）。
     *
     * <p><b>この絞り込みは最適化であって認可の権威ではない。</b> ここで使う
     * {@code recruitment_listings.payeeKind}/{@code payeeUserId}/{@code scopeId} は
     * <b>募集の作成後に変更できる可変の値</b>であり、実際に金を受け取る先（escrow の payee）と
     * 食い違いうる。したがって本クエリの結果をそのまま利用者へ返してはならない
     * ——{@code RecruitmentCancellationRecordQueryService} が payment ドメインの
     * {@code ConnectChargeService#filterPayeeSettlementManaged}（権威ある受取先）で
     * <b>必ず最終的に絞り込む</b>。本クエリの役割は、その権威ある判定へ渡す候補を
     * DB 側で安く減らすことだけである。</p>
     *
     * <p>本クエリを「見えてよいものの集合」として単独で使うと、受取先を後から差し替えた募集で
     * 現在の受取先に従前の記録が混じる。権威ある絞り込みを外さないこと。</p>
     *
     * <p>ソートキー・カーソルの扱いは {@link #findChunkForSystemAdmin} と同一
     * （{@code (cancelledAt DESC, id DESC)} の一意な複合キー）。</p>
     *
     * @param actorUserId  操作者ユーザー ID（{@code payeeKind=USER} の一致判定に使用）
     * @param teamScopeIds 操作者が支払い管理権限を持つ TEAM の scopeId 集合（無ければ番人値）
     * @param orgScopeIds  操作者が管理者・支払い管理権限を持つ ORG の scopeId 集合（無ければ番人値）
     * @param statuses     絞り込む決済ステータス
     */
    @Query("""
            SELECT new com.mannschaft.app.recruitment.dto.RecruitmentCancellationRecordSummaryResponse(
                r.id, r.listingId, l.title, r.participantId, r.userId,
                r.feeAmount, CAST(r.paymentStatus AS string), r.cancelledAt, r.hoursBeforeStart)
            FROM RecruitmentCancellationRecordEntity r, RecruitmentListingEntity l
            WHERE l.id = r.listingId
              AND r.paymentStatus IN :statuses
              AND (r.cancelledAt < :cursorCancelledAt
                   OR (r.cancelledAt = :cursorCancelledAt AND r.id < :cursorId))
              AND (
                   (l.payeeKind = 'USER' AND l.payeeUserId = :actorUserId)
                OR (l.payeeKind = 'TEAM' AND l.scopeId IN :teamScopeIds)
                OR (l.payeeKind = 'ORG' AND l.scopeId IN :orgScopeIds)
              )
            ORDER BY r.cancelledAt DESC, r.id DESC
            """)
    List<RecruitmentCancellationRecordSummaryResponse> findChunkOfPayeeCandidates(
            @Param("actorUserId") Long actorUserId,
            @Param("teamScopeIds") Collection<Long> teamScopeIds,
            @Param("orgScopeIds") Collection<Long> orgScopeIds,
            @Param("statuses") Collection<CancellationPaymentStatus> statuses,
            @Param("cursorCancelledAt") LocalDateTime cursorCancelledAt,
            @Param("cursorId") Long cursorId,
            Pageable pageable);
}
