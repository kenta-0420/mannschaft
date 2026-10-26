package com.mannschaft.app.analytics.event;

import com.mannschaft.app.analytics.PageViewContentType;
import com.mannschaft.app.analytics.PageViewScopeType;
import com.mannschaft.app.analytics.entity.PageViewLogEntity;
import com.mannschaft.app.analytics.repository.PageViewLogRepository;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F10.8 ページビュー計測リスナーの結合テスト（Testcontainers MySQL）。
 *
 * <p>{@link PageViewRecordListener#onPageViewRecorded} の scope 実在チェック・生ログ INSERT・
 * title 無害化を実 MySQL 上で検証する（設計書 §3.1 / §5.1・AC-01/02/05/06/23）。</p>
 *
 * <h2>非同期を同期化する方法（設計書 §2.4 の DiscardPolicy + Awaitility 偽 green 回避）</h2>
 * <p>{@code @Async("page-view-pool")} + {@code DiscardPolicy} 下では {@code Awaitility} が「捨てられた」ことを
 * 区別できず偽 green になりうる。本テストは <b>プロキシを経由しない非同期リスナーインスタンスを
 * テスト内で直接 new して同期呼び出し</b>することで、{@code @Async} を通さず決定論的に検証する
 * （実 Repository は Spring コンテキストから注入したものを渡す）。これにより
 * {@code @TestConfiguration} での Bean 差し替え（共有 ApplicationContext を分岐させる）を避けつつ、
 * リスナー本体のロジック（実在チェック・INSERT・sanitize）を実 DB で確実に踏む。</p>
 */
@DisplayName("PageView 計測リスナー 結合テスト (F10.8)")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class PageViewRecordListenerIT extends AbstractMySqlIntegrationTest {

    /** 制御文字（改行・タブ・復帰）。ソースへのリテラル制御文字混入を避けるため char 値で構築する。 */
    private static final String LF = String.valueOf((char) 0x0A);
    private static final String TAB = String.valueOf((char) 0x09);
    private static final String CR = String.valueOf((char) 0x0D);

    @Autowired
    private PageViewLogRepository logRepository;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    /** プロキシを介さない同期リスナー（@Async をバイパスして決定論化）。 */
    private PageViewRecordListener listener;

    private Long teamId;

    @BeforeEach
    void setUp() {
        logRepository.deleteAll();
        listener = new PageViewRecordListener(logRepository, teamRepository, organizationRepository);
        // 共有 DB（singleton container）ではテスト間でチーム行が残るため、slug をテスト毎に一意にして
        // UNIQUE(slug) 衝突を避ける（最大 30 文字制約に収める）。
        String suffix = Long.toString(System.nanoTime(), 36);
        String slug = ("pv-it-" + suffix);
        slug = slug.substring(0, Math.min(30, slug.length()));
        teamId = teamRepository.saveAndFlush(TeamEntity.builder()
                .slug(slug)
                .name("PVリスナーIT")
                .visibility(TeamEntity.Visibility.PUBLIC)
                .supporterEnabled(false)
                .build()).getId();
    }

    private PageViewRecordedEvent event(Long userId, String visitorId, String title) {
        return new PageViewRecordedEvent(
                PageViewScopeType.TEAM,
                teamId,
                PageViewContentType.ARTICLE,
                4567L,
                "/teams/pv-listener-it/articles/4567",
                title,
                userId,
                visitorId,
                LocalDateTime.of(2026, 7, 1, 9, 0));
    }

    @Test
    @DisplayName("AC-01: 認証済みメンバーのイベントで生ログ 1 件（user_id + visitor_id セット）が記録される")
    void record_authenticated_writesLogWithUserId() {
        listener.onPageViewRecorded(event(42L, "vid-1", "春合宿のお知らせ"));

        List<PageViewLogEntity> logs = logRepository.findAll();
        assertThat(logs).hasSize(1);
        PageViewLogEntity log = logs.get(0);
        assertThat(log.getUserId()).isEqualTo(42L);
        assertThat(log.getVisitorId()).isEqualTo("vid-1");
        assertThat(log.getScopeType()).isEqualTo(PageViewScopeType.TEAM);
        assertThat(log.getScopeId()).isEqualTo(teamId);
    }

    @Test
    @DisplayName("AC-02: 未ログインゲストのイベントで生ログ 1 件（user_id = NULL）が記録される")
    void record_guest_writesLogWithNullUserId() {
        listener.onPageViewRecorded(event(null, "vid-guest", "公開ページ"));

        List<PageViewLogEntity> logs = logRepository.findAll();
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getUserId()).isNull();
        assertThat(logs.get(0).getVisitorId()).isEqualTo("vid-guest");
    }

    @Test
    @DisplayName("AC-05: 存在しないスコープのイベントは破棄され生ログ 0 件（ログ注入防御）")
    void record_nonExistentScope_discarded() {
        Long ghostScopeId = teamId + 999_999L;
        listener.onPageViewRecorded(new PageViewRecordedEvent(
                PageViewScopeType.TEAM,
                ghostScopeId,
                PageViewContentType.ARTICLE,
                1L,
                "/teams/ghost/articles/1",
                "存在しないスコープ",
                1L,
                "vid-x",
                LocalDateTime.of(2026, 7, 1, 9, 0)));

        assertThat(logRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("AC-06: 同一 visitor から同一コンテンツへ複数回 → 生ログは複数行入る")
    void record_duplicate_writesMultipleRows() {
        listener.onPageViewRecorded(event(null, "vid-dup", "記事"));
        listener.onPageViewRecorded(event(null, "vid-dup", "記事"));
        listener.onPageViewRecorded(event(null, "vid-dup", "記事"));

        assertThat(logRepository.findAll()).hasSize(3);
    }

    @Test
    @DisplayName("AC-23: title の制御文字は除去され 255 文字に切り詰めて保存される（ログ注入防止）")
    void record_sanitizesTitle() {
        // 制御文字（改行・タブ・復帰）を挟んだタイトル。通常の空白(0x20)は制御文字ではないため対象外。
        String malicious = "危険" + LF + TAB + CR + "改行混入";
        String longTitle = "あ".repeat(300);

        listener.onPageViewRecorded(event(null, "vid-ctrl", malicious));
        listener.onPageViewRecorded(event(null, "vid-long", longTitle));

        List<PageViewLogEntity> logs = logRepository.findAll();
        assertThat(logs).hasSize(2);

        PageViewLogEntity sanitized = logs.stream()
                .filter(l -> "vid-ctrl".equals(l.getVisitorId())).findFirst().orElseThrow();
        assertThat(sanitized.getTitle()).isEqualTo("危険改行混入");
        assertThat(sanitized.getTitle()).doesNotContain(LF, TAB, CR);

        PageViewLogEntity truncated = logs.stream()
                .filter(l -> "vid-long".equals(l.getVisitorId())).findFirst().orElseThrow();
        assertThat(truncated.getTitle()).hasSize(255);
    }
}
