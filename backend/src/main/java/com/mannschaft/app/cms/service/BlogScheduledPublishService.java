package com.mannschaft.app.cms.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ブログ予約公開の<b>実処理</b>サービス（issue #2616・F06.1 §2210-2226）。
 *
 * <p><b>本クラスは試練（テスト先行）が置いた空シグネチャである。</b>
 * 中身は出陣（実装）で埋めること。ロジックを書かずに契約だけを固定している。</p>
 *
 * <h2>設計方針（覆さないこと）</h2>
 * <p>予約中の記事は {@code status = DRAFT} のまま {@code published_at} に未来時刻を持つ。
 * {@code PostStatus.SCHEDULED} は<b>新設しない</b>。公開系クエリ（{@code status = PUBLISHED}
 * の等値判定）は一切変更せず、予約中記事は「まだ DRAFT だから公開系に出ない」という
 * 構造的な理由で漏れない（F06.1 §155 / §949 / §2023）。</p>
 *
 * <h2>役割分担</h2>
 * <p>本クラスは<b>1 件の遷移</b>を {@code @Transactional(propagation = REQUIRES_NEW)} で
 * 独立コミットする（{@code ReservationPendingExpireService} と同じ作法）。
 * スケジュール宣言とループ制御は {@link BlogScheduledPublishBatchService} が持つ。</p>
 */
@Service
@RequiredArgsConstructor
public class BlogScheduledPublishService {

    /**
     * 1 回の起動で処理する記事数の上限（件数非依存の固定クエリ本数を担保する・AC-15）。
     *
     * <p>上限を設けないと、予約が大量に溜まった際に 1 回の起動が延々と走り続けて
     * ShedLock の {@code lockAtMostFor} を超え、次回起動と二重処理になる。
     * 残りは次回起動が拾う（遷移条件は時刻経過なので自己修復する）。</p>
     */
    public static final int MAX_POSTS_PER_RUN = 500;

    /**
     * 予約公開の対象になっている記事 ID を取得する。
     *
     * <p>対象条件: {@code status = DRAFT AND published_at IS NOT NULL
     * AND published_at <= :baseTime AND deleted_at IS NULL}。
     * 取得件数は {@link #MAX_POSTS_PER_RUN} 件で上限化し、{@code published_at ASC} で
     * 古い予約から処理する。</p>
     *
     * @param baseTime 判定基準時刻（境界を厳密に固定できるよう引数で受ける）
     * @return 対象記事 ID（最大 {@link #MAX_POSTS_PER_RUN} 件）
     */
    public List<Long> findDuePostIds(LocalDateTime baseTime) {
        throw new UnsupportedOperationException("未実装（試練・issue #2616）");
    }

    /**
     * 予約記事 1 件を公開へ遷移させる（独立トランザクション）。
     *
     * <p>{@code REQUIRES_NEW} で 1 件ずつコミットするため、途中の 1 件が失敗しても
     * 他の件のコミットは巻き戻らない（AC-11）。</p>
     *
     * @param postId   対象記事 ID
     * @param baseTime 判定基準時刻（再判定に使う。取得後・遷移前に条件が崩れていた場合は遷移しない）
     * @return 実際に {@code PUBLISHED} へ遷移させた場合 true
     */
    public boolean publishScheduledPost(Long postId, LocalDateTime baseTime) {
        throw new UnsupportedOperationException("未実装（試練・issue #2616）");
    }
}
