package com.mannschaft.app.cms.service;

import com.mannschaft.app.cms.PostPriority;
import com.mannschaft.app.cms.PostStatus;
import com.mannschaft.app.cms.PostType;
import com.mannschaft.app.cms.Visibility;
import com.mannschaft.app.cms.dto.SharePostRequest;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.cms.repository.BlogPostRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.publicview.dto.PublicPostCommentRequest;
import com.mannschaft.app.publicview.service.PublicPostCommentService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ブログ予約公開の実 MySQL 結合テスト（issue #2616・試練）。
 *
 * <h2>受け入れ条件の対応</h2>
 * <ul>
 *   <li><b>AC-4</b>: 予約中（DRAFT ＋ 未来 published_at）の記事は公開一覧に出現しない</li>
 *   <li><b>AC-5</b>: 同記事は sitemap・RSS/Atom・公開プロフィール投稿一覧にも出現しない</li>
 *   <li><b>AC-6</b>: 同記事は共有不可・公開コメント不可</li>
 *   <li><b>AC-7</b>: バッチが対象を拾い PUBLISHED へ遷移させる</li>
 *   <li><b>AC-8</b>: 遷移後は公開一覧に出現する</li>
 *   <li><b>AC-9</b>: 境界値 —（基準時刻と同値なら遷移する／1 秒先は遷移しない）</li>
 *   <li><b>AC-12</b>: published_at が NULL の DRAFT は遷移しない</li>
 *   <li><b>AC-13</b>: 論理削除済み（deleted_at 非 NULL）は遷移しない</li>
 *   <li><b>AC-15</b>: 対象抽出が件数比例のクエリ（N+1）を出さず、取得件数に上限がある</li>
 * </ul>
 *
 * <p><b>クラスに {@code @Transactional} を付けない</b> — 実処理は
 * {@code REQUIRES_NEW} で 1 件ずつコミットするため、テストの tx に巻き込むと
 * コミットの実観測ができなくなる（{@code ReservationPendingExpirePersistenceIntegrationTest} と同じ理由）。</p>
 */
@DisplayName("ブログ予約公開 永続化結合テスト（実MySQL・issue #2616）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class BlogScheduledPublishPersistenceIntegrationTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private BlogScheduledPublishBatchService batchService;
    @Autowired
    private BlogScheduledPublishService scheduledPublishService;
    @Autowired
    private BlogPostRepository postRepository;
    @Autowired
    private BlogPostShareService shareService;
    @Autowired
    private PublicPostCommentService publicPostCommentService;
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    /** 他テストのシードと混ざらないための採番。 */
    private static final AtomicLong SEQ = new AtomicLong(970_000L);

    private static long nextId() {
        return SEQ.incrementAndGet();
    }

    // ────────────────────────────────────────────────────────────
    // シードヘルパー
    // ────────────────────────────────────────────────────────────

    /** 予約中の記事（DRAFT ＋ published_at）を作る。publishedAt に null を渡すと純粋な下書きになる。 */
    private BlogPostEntity seedPost(Long teamId, Long authorId, PostStatus status,
                                    LocalDateTime publishedAt) {
        return postRepository.saveAndFlush(BlogPostEntity.builder()
                .teamId(teamId)
                .authorId(authorId)
                .title("予約記事")
                .slug("scheduled-" + nextId())
                .body("本文")
                .postType(PostType.BLOG)
                .visibility(Visibility.PUBLIC)
                .priority(PostPriority.NORMAL)
                .status(status)
                .publishedAt(publishedAt)
                .readingTimeMinutes((short) 1)
                .build());
    }

    private BlogPostEntity reload(Long postId) {
        return postRepository.findById(postId).orElseThrow();
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    // ────────────────────────────────────────────────────────────
    // AC-4 / AC-5 / AC-8: 公開系クエリからの露出
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-4 / AC-5 / AC-8: 予約中は公開系クエリに一切出ず、バッチ遷移後に出現する")
    void ac4_ac5_ac8_予約中は公開系に出ず遷移後に出る() {
        Long teamId = nextId();
        Long authorId = nextId();
        LocalDateTime future = LocalDateTime.now().plusHours(3);
        BlogPostEntity scheduled = seedPost(teamId, authorId, PostStatus.DRAFT, future);
        Long postId = scheduled.getId();

        // --- AC-4: 公開一覧（チーム）
        assertThat(postRepository.findPublicPostsByTeamId(teamId, PageRequest.of(0, 20)))
                .as("予約中記事は公開一覧に出ない（status が DRAFT のため構造的に漏れない）")
                .isEmpty();
        assertThat(postRepository.findPublicPostByTeamIdAndId(teamId, postId))
                .as("単票取得も 404 相当（空）")
                .isEmpty();

        // --- AC-5: sitemap / RSS / 公開プロフィール
        assertThat(postRepository.findAllPublicPostsByTeam(List.of(teamId)))
                .as("sitemap に予約中記事の URL を配ってはならない")
                .isEmpty();
        assertThat(postRepository.findTop20ByTeamIdAndStatusOrderByPublishedAtDesc(
                teamId, PostStatus.PUBLISHED))
                .as("RSS/Atom フィードにも出ない")
                .isEmpty();
        assertThat(postRepository.findPublicPostsByAuthorId(authorId, PageRequest.of(0, 20)))
                .as("公開プロフィールの投稿一覧にも出ない")
                .isEmpty();

        // --- AC-8: 公開時刻を過ぎたものとしてバッチを回すと公開系に出現する
        // published_at を過去へ倒して「公開時刻に達した」状態を作る
        postRepository.saveAndFlush(reload(postId).toBuilder()
                .publishedAt(LocalDateTime.now().minusMinutes(1))
                .build());

        Integer published = batchService.publishScheduledPosts();

        // バッチはスコープ横断（全チーム・全組織）で対象を拾うため、件数を「ちょうど 1」で
        // 固定してはならない。本クラスは @Transactional を付けず実 DB にコミットするため、
        // 同一 DB を共有する兄弟テスト（AC-9 が基準時刻ちょうどの予約記事を残す等）が
        // 同じ回に拾われる。ここで検証すべきは「対象記事が公開されたこと」であり、
        // それは直後の status / 公開一覧の assert が担う。
        assertThat(published).as("バッチが公開を行った（本記事を含む）").isPositive();
        assertThat(reload(postId).getStatus()).isEqualTo(PostStatus.PUBLISHED);
        Page<BlogPostEntity> publicPosts =
                postRepository.findPublicPostsByTeamId(teamId, PageRequest.of(0, 20));
        assertThat(publicPosts.getContent())
                .as("遷移後は公開一覧に出現する")
                .extracting(BlogPostEntity::getId)
                .contains(postId);
    }

    // ────────────────────────────────────────────────────────────
    // AC-6: 共有・公開コメント
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-6: 予約中記事は共有できず、公開コメントも受け付けない")
    void ac6_予約中は共有もコメントもできない() {
        Long teamId = nextId();
        Long authorId = nextId();
        BlogPostEntity scheduled =
                seedPost(teamId, authorId, PostStatus.DRAFT, LocalDateTime.now().plusHours(3));
        Long postId = scheduled.getId();

        assertThatThrownBy(() ->
                shareService.sharePost(postId, authorId, new SharePostRequest(nextId(), null)))
                .as("公開前（予約中）の記事を他スコープへ共有させてはならない")
                .isInstanceOf(BusinessException.class);

        assertThatThrownBy(() -> publicPostCommentService.postComment(
                postId, authorId, new PublicPostCommentRequest("コメント")))
                .as("予約中記事は status != PUBLISHED なので公開コメントを受け付けない")
                .isInstanceOf(BusinessException.class);
    }

    // ────────────────────────────────────────────────────────────
    // AC-9: 境界値
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-9: published_at が基準時刻と同値なら対象、1秒先は対象外")
    void ac9_境界値は同値を含み1秒先を含まない() {
        Long teamId = nextId();
        Long authorId = nextId();
        // DATETIME はマイクロ秒未満を丸めるため、秒精度に切り捨てて厳密に比較する
        LocalDateTime base = LocalDateTime.now().withNano(0);
        Long exactId = seedPost(teamId, authorId, PostStatus.DRAFT, base).getId();
        Long oneSecondLaterId =
                seedPost(teamId, authorId, PostStatus.DRAFT, base.plusSeconds(1)).getId();

        List<Long> due = scheduledPublishService.findDuePostIds(base);

        assertThat(due)
                .as("published_at <= baseTime（同値を含む）が対象")
                .contains(exactId)
                .doesNotContain(oneSecondLaterId);
    }

    // ────────────────────────────────────────────────────────────
    // AC-12 / AC-13: 対象外条件
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-12: published_at が NULL の DRAFT は遷移しない")
    void ac12_publishedAtがNULLの下書きは対象外() {
        Long teamId = nextId();
        Long authorId = nextId();
        Long draftId = seedPost(teamId, authorId, PostStatus.DRAFT, null).getId();

        List<Long> due = scheduledPublishService.findDuePostIds(LocalDateTime.now());
        assertThat(due).as("published_at IS NOT NULL が対象条件").doesNotContain(draftId);

        batchService.publishScheduledPosts();
        assertThat(reload(draftId).getStatus())
                .as("純粋な下書きが勝手に公開されてはならない")
                .isEqualTo(PostStatus.DRAFT);
    }

    @Test
    @DisplayName("AC-13: 論理削除済みの予約記事は遷移しない")
    void ac13_論理削除済みは対象外() {
        Long teamId = nextId();
        Long authorId = nextId();
        BlogPostEntity post =
                seedPost(teamId, authorId, PostStatus.DRAFT, LocalDateTime.now().minusMinutes(1));
        Long postId = post.getId();
        post.softDelete();
        postRepository.saveAndFlush(post);

        List<Long> due = scheduledPublishService.findDuePostIds(LocalDateTime.now());

        assertThat(due)
                .as("deleted_at IS NULL が対象条件（削除済み記事が復活公開されると事故になる）")
                .doesNotContain(postId);
    }

    // ────────────────────────────────────────────────────────────
    // AC-15: 性能（固定クエリ本数・上限付き）
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-15: 対象抽出のクエリ本数が件数に比例しない（N+1 なし）")
    void ac15_対象抽出はN加1を出さない() {
        Long teamId = nextId();
        Long authorId = nextId();
        LocalDateTime past = LocalDateTime.now().minusMinutes(5);

        Statistics stats = statistics();
        stats.setStatisticsEnabled(true);

        seedPost(teamId, authorId, PostStatus.DRAFT, past);
        stats.clear();
        scheduledPublishService.findDuePostIds(LocalDateTime.now());
        long queriesForOne = stats.getPrepareStatementCount();

        for (int i = 0; i < 9; i++) {
            seedPost(nextId(), nextId(), PostStatus.DRAFT, past);
        }
        stats.clear();
        scheduledPublishService.findDuePostIds(LocalDateTime.now());
        long queriesForTen = stats.getPrepareStatementCount();

        assertThat(queriesForTen)
                .as("対象が 1 件でも 10 件でも発行クエリ本数は同じ（件数比例＝N+1 を禁じる）")
                .isEqualTo(queriesForOne);
    }

    @Test
    @DisplayName("AC-15: 1回の起動で処理する件数に上限がある（ロック超過による二重処理を防ぐ）")
    void ac15_取得件数に上限がある() {
        assertThat(BlogScheduledPublishService.MAX_POSTS_PER_RUN)
                .as("上限が無いと 1 回の起動が lockAtMostFor を超え、次回起動と二重処理になる")
                .isPositive();

        List<Long> due = scheduledPublishService.findDuePostIds(LocalDateTime.now());

        assertThat(due.size())
                .as("取得件数は MAX_POSTS_PER_RUN で頭打ちになる")
                .isLessThanOrEqualTo(BlogScheduledPublishService.MAX_POSTS_PER_RUN);
    }
}
