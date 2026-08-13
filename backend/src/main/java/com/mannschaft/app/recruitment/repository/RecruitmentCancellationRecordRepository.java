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
     * <p><b>この絞り込みは最適化であって認可の権威ではない。</b> 最終判定は payment ドメインの
     * {@code ConnectChargeService#filterPayeeSettlementManaged}（escrow の payee）が行う
     * ——同一募集でも参加者ごとに escrow は別行であり、募集単位の絞り込みでは参加者単位の
     * 判定までは決まらないためである。</p>
     *
     * <p><b>{@code listingIds} は escrow から導出した集合でなければならない</b>
     * （{@code ConnectChargeService#findSourceIdsWithPayeeSettlementManaged}）。
     * {@code recruitment_listings.payeeKind}/{@code payeeUserId}/{@code scopeId} で絞ってはならない
     * ——これらは<b>募集の作成後に変更できる可変の値</b>であり、変更した瞬間に
     * 本来の債権者が自分の記録を見失う（事前絞り込みが権威ある集合の上位集合でなくなる）。
     * 事前絞り込みは「権威ある集合を必ず包含する」ことが安全の条件である。</p>
     *
     * <p>ソートキー・カーソルの扱いは {@link #findChunkForSystemAdmin} と同一
     * （{@code (cancelledAt DESC, id DESC)} の一意な複合キー）。</p>
     *
     * @param listingIds 操作者が escrow 上の受取先である募集 ID の集合（空なら番人値を渡すこと）
     * @param statuses   絞り込む決済ステータス
     */
    @Query("""
            SELECT new com.mannschaft.app.recruitment.dto.RecruitmentCancellationRecordSummaryResponse(
                r.id, r.listingId, l.title, r.participantId, r.userId,
                r.feeAmount, CAST(r.paymentStatus AS string), r.cancelledAt, r.hoursBeforeStart)
            FROM RecruitmentCancellationRecordEntity r, RecruitmentListingEntity l
            WHERE l.id = r.listingId
              AND r.paymentStatus IN :statuses
              AND r.listingId IN :listingIds
              AND (r.cancelledAt < :cursorCancelledAt
                   OR (r.cancelledAt = :cursorCancelledAt AND r.id < :cursorId))
            ORDER BY r.cancelledAt DESC, r.id DESC
            """)
    List<RecruitmentCancellationRecordSummaryResponse> findChunkOfPayeeCandidates(
            @Param("listingIds") Collection<Long> listingIds,
            @Param("statuses") Collection<CancellationPaymentStatus> statuses,
            @Param("cursorCancelledAt") LocalDateTime cursorCancelledAt,
            @Param("cursorId") Long cursorId,
            Pageable pageable);
}
