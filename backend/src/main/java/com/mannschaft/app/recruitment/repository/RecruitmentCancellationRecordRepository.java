package com.mannschaft.app.recruitment.repository;

import com.mannschaft.app.recruitment.CancellationPaymentStatus;
import com.mannschaft.app.recruitment.entity.RecruitmentCancellationRecordEntity;
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
}
