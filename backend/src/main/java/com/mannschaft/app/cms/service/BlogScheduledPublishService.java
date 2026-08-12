package com.mannschaft.app.cms.service;

import com.mannschaft.app.cms.PostStatus;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.cms.repository.BlogPostRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * ブログ予約公開の<b>実処理</b>サービス（issue #2616・F06.1 §2210-2226）。
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
 *
 * <h2>なぜ 2 クラスに分けるのか（トランザクション境界の根治）</h2>
 * <p>バッチ全体を 1 つの {@code @Transactional} で囲むと、行単位 try/catch は<b>機能しない</b>。
 * 内側の {@code @Transactional} メソッドから例外が抜けた時点で Spring は参加中トランザクションを
 * rollback-only にマークするため、呼び出し元が例外を握っても最終コミットが
 * {@code UnexpectedRollbackException} で失敗し「1 件の失敗が全件を巻き込む」ことになる。
 * また {@code REQUIRES_NEW} は<b>プロキシを経由しないと効かない</b>ため、
 * 同一 Bean 内の自己呼び出しでは伝播が無視される。ゆえに別 Bean へ切り出している（AC-11）。</p>
 */
@Slf4j
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

    private final BlogPostRepository postRepository;

    /**
     * 予約公開の対象になっている記事 ID を取得する。
     *
     * <p>対象条件: {@code status = DRAFT AND published_at IS NOT NULL
     * AND published_at <= :baseTime AND deleted_at IS NULL}。
     * 取得件数は {@link #MAX_POSTS_PER_RUN} 件で上限化し、{@code published_at ASC} で
     * 古い予約から処理する。発行クエリは<b>常に 1 本</b>で、対象件数に比例しない（AC-15）。</p>
     *
     * @param baseTime 判定基準時刻（境界を厳密に固定できるよう引数で受ける）
     * @return 対象記事 ID（最大 {@link #MAX_POSTS_PER_RUN} 件）
     */
    @Transactional(readOnly = true)
    public List<Long> findDuePostIds(LocalDateTime baseTime) {
        return postRepository.findDueScheduledPostIds(baseTime, PageRequest.of(0, MAX_POSTS_PER_RUN));
    }

    /**
     * 予約記事 1 件を公開へ遷移させる（独立トランザクション）。
     *
     * <p>{@code REQUIRES_NEW} で 1 件ずつコミットするため、途中の 1 件が失敗しても
     * 他の件のコミットは巻き戻らない（AC-11）。</p>
     *
     * <p>抽出は別トランザクションであり、抽出後・遷移前に記事が公開済み・下書きへ戻された・
     * 論理削除された可能性がある。エンティティではなく <b>ID を受け取って本トランザクションで
     * 取り直し</b>、冒頭で対象条件を再確認して二重公開・復活公開を防ぐ
     * （{@code ReservationPendingExpireService#expireUnit} と同じ作法）。</p>
     *
     * @param postId   対象記事 ID
     * @param baseTime 判定基準時刻（再判定に使う。取得後・遷移前に条件が崩れていた場合は遷移しない）
     * @return 実際に {@code PUBLISHED} へ遷移させた場合 true
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean publishScheduledPost(Long postId, LocalDateTime baseTime) {
        // 論理削除済みは @SQLRestriction("deleted_at IS NULL") により空が返る（AC-13）。
        Optional<BlogPostEntity> found = postRepository.findById(postId);
        if (found.isEmpty()) {
            log.debug("予約公開スキップ: 抽出後に削除された postId={}", postId);
            return false;
        }

        BlogPostEntity post = found.get();
        if (post.getStatus() != PostStatus.DRAFT) {
            log.debug("予約公開スキップ: 抽出後にステータスが変化していた postId={}, status={}",
                    postId, post.getStatus());
            return false;
        }
        if (post.getPublishedAt() == null || post.getPublishedAt().isAfter(baseTime)) {
            // 抽出後に予約時刻が未来へ変更された（AC-12 の published_at NULL 化も含む）。
            log.debug("予約公開スキップ: 抽出後に公開時刻が変化していた postId={}, publishedAt={}",
                    postId, post.getPublishedAt());
            return false;
        }

        post.completeScheduledPublish();
        postRepository.save(post);
        log.info("予約公開: postId={}, publishedAt={}", postId, post.getPublishedAt());
        return true;
    }
}
