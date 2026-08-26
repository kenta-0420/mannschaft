package com.mannschaft.app.analytics.repository;

import com.mannschaft.app.analytics.PageViewContentType;
import com.mannschaft.app.analytics.PageViewScopeType;
import com.mannschaft.app.analytics.entity.PageViewLogEntity;
import com.mannschaft.app.analytics.repository.PageViewLogRepository.ContentRankingProjection;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F10.8 第2弾 人気コンテンツランキング集計クエリ
 * {@link PageViewLogRepository#findTopContent} の結合テスト（Testcontainers MySQL）。
 *
 * <p>{@code GROUP_CONCAT} / {@code SUBSTRING_INDEX} 方言を使うため H2 では検証不能。必ず MySQL 上で走らせる
 * （設計書 §5.3 第2弾 / §4.2 生ログスキーマ）。</p>
 *
 * <p>検証する受け入れ条件:</p>
 * <ul>
 *   <li>AC-P2-1: {@code (content_type, content_id)} で集計し views / uniqueVisitors を正しく数える</li>
 *   <li>AC-P2-2: 11 件投入で上位 10 件のみ・views 降順で返る</li>
 *   <li>AC-P2-3: 同一 content_id で改題された場合、最新 viewed_at の title / url が代表値になる</li>
 *   <li>AC-P2-4: 生ログ 0 件のスコープは空リスト（例外にならない）</li>
 *   <li>AC-P2-7: 別スコープのログは混入しない</li>
 * </ul>
 */
@DisplayName("PageView 人気コンテンツ集計クエリ 結合テスト (F10.8 第2弾)")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class PageViewTopContentRepositoryIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private PageViewLogRepository logRepository;

    private static final Long TEAM_ID = 3003L;
    private static final Long OTHER_TEAM_ID = 4004L;

    // 保持期間内に収まるよう、集計範囲を直近日付で固定する。
    private final LocalDateTime baseTime = LocalDateTime.now().minusDays(1).withHour(9).withMinute(0).withSecond(0).withNano(0);
    private final LocalDateTime from = baseTime.minusDays(1);
    private final LocalDateTime to = baseTime.plusDays(1);

    @BeforeEach
    void clean() {
        logRepository.deleteAll();
    }

    /** 生ログ 1 件を投入するヘルパー。 */
    private void insertLog(
            Long scopeId, PageViewContentType contentType, Long contentId,
            String url, String title, Long userId, String visitorId, LocalDateTime viewedAt) {
        logRepository.saveAndFlush(PageViewLogEntity.builder()
                .scopeType(PageViewScopeType.TEAM).scopeId(scopeId)
                .contentType(contentType).contentId(contentId)
                .url(url).title(title)
                .userId(userId).visitorId(visitorId).viewedAt(viewedAt).build());
    }

    @Test
    @DisplayName("AC-P2-1: (content_type, content_id) 別に views / uniqueVisitors を正しく集計する")
    void findTopContent_aggregatesViewsAndUniqueVisitors() {
        // content A(ARTICLE,1): user10 が 2 回 + guest g1 が 1 回 → views 3 / unique 2
        insertLog(TEAM_ID, PageViewContentType.ARTICLE, 1L, "/a/1", "記事A", 10L, "v-x", baseTime.plusMinutes(1));
        insertLog(TEAM_ID, PageViewContentType.ARTICLE, 1L, "/a/1", "記事A", 10L, "v-x", baseTime.plusMinutes(2));
        insertLog(TEAM_ID, PageViewContentType.ARTICLE, 1L, "/a/1", "記事A", null, "g1", baseTime.plusMinutes(3));
        // content B(ARTICLE,2): user11 が 1 回 → views 1 / unique 1
        insertLog(TEAM_ID, PageViewContentType.ARTICLE, 2L, "/a/2", "記事B", 11L, "v-y", baseTime.plusMinutes(4));

        List<ContentRankingProjection> result = logRepository.findTopContent(
                PageViewScopeType.TEAM.name(), TEAM_ID, from, to, 10);

        assertThat(result).hasSize(2);
        ContentRankingProjection first = result.get(0);
        assertThat(first.getContentType()).isEqualTo("ARTICLE");
        assertThat(first.getContentId()).isEqualTo(1L);
        assertThat(first.getTitle()).isEqualTo("記事A");
        assertThat(first.getUrl()).isEqualTo("/a/1");
        assertThat(first.getViews()).isEqualTo(3L);
        assertThat(first.getUniqueVisitors()).isEqualTo(2L);

        ContentRankingProjection second = result.get(1);
        assertThat(second.getContentId()).isEqualTo(2L);
        assertThat(second.getViews()).isEqualTo(1L);
        assertThat(second.getUniqueVisitors()).isEqualTo(1L);
    }

    @Test
    @DisplayName("AC-P2-2: 11 コンテンツ投入で上位 10 件のみ・views 降順で返る")
    void findTopContent_limitsToTenOrderedByViews() {
        // content k (k=1..11) を k 回ずつ閲覧（すべて別ゲスト訪問者）。views は k、11 が最多・1 が最少。
        for (long k = 1; k <= 11; k++) {
            for (int i = 0; i < k; i++) {
                insertLog(TEAM_ID, PageViewContentType.ARTICLE, k,
                        "/a/" + k, "記事" + k, null, "g-" + k + "-" + i, baseTime.plusSeconds(k * 100 + i));
            }
        }

        List<ContentRankingProjection> result = logRepository.findTopContent(
                PageViewScopeType.TEAM.name(), TEAM_ID, from, to, 10);

        assertThat(result).hasSize(10);
        // views 降順: 先頭は k=11(views 11)、10 番目は k=2(views 2)。k=1(views 1)は圏外。
        assertThat(result.get(0).getContentId()).isEqualTo(11L);
        assertThat(result.get(0).getViews()).isEqualTo(11L);
        assertThat(result).extracting(ContentRankingProjection::getViews)
                .isSortedAccordingTo((a, b) -> Long.compare(b, a));
        assertThat(result).extracting(ContentRankingProjection::getContentId)
                .doesNotContain(1L);
    }

    @Test
    @DisplayName("AC-P2-3: 同一 content_id が改題された場合、最新 viewed_at の title / url が代表値になる")
    void findTopContent_representativeTitleIsLatest() {
        insertLog(TEAM_ID, PageViewContentType.ARTICLE, 1L, "/a/old", "旧タイトル", null, "g1", baseTime.plusMinutes(1));
        insertLog(TEAM_ID, PageViewContentType.ARTICLE, 1L, "/a/new", "新タイトル", null, "g2", baseTime.plusMinutes(5));
        insertLog(TEAM_ID, PageViewContentType.ARTICLE, 1L, "/a/mid", "中タイトル", null, "g3", baseTime.plusMinutes(3));

        List<ContentRankingProjection> result = logRepository.findTopContent(
                PageViewScopeType.TEAM.name(), TEAM_ID, from, to, 10);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTitle()).isEqualTo("新タイトル");
        assertThat(result.get(0).getUrl()).isEqualTo("/a/new");
        assertThat(result.get(0).getViews()).isEqualTo(3L);
    }

    @Test
    @DisplayName("AC-P2-4: 生ログ 0 件のスコープは空リストを返す（例外にならない）")
    void findTopContent_emptyScope_returnsEmpty() {
        List<ContentRankingProjection> result = logRepository.findTopContent(
                PageViewScopeType.TEAM.name(), 99999L, from, to, 10);

        assertThat(result).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("AC-P2-7: 別スコープのログは混入しない")
    void findTopContent_isolatesByScope() {
        insertLog(TEAM_ID, PageViewContentType.ARTICLE, 1L, "/a/1", "自分の記事", null, "g1", baseTime.plusMinutes(1));
        // 別スコープに大量投入しても対象スコープの結果には出ない
        for (int i = 0; i < 5; i++) {
            insertLog(OTHER_TEAM_ID, PageViewContentType.ARTICLE, 1L, "/a/1", "他スコープ記事", null, "og-" + i, baseTime.plusMinutes(i));
        }

        List<ContentRankingProjection> result = logRepository.findTopContent(
                PageViewScopeType.TEAM.name(), TEAM_ID, from, to, 10);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTitle()).isEqualTo("自分の記事");
        assertThat(result.get(0).getViews()).isEqualTo(1L);
    }
}
