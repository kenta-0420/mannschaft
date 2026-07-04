package com.mannschaft.app.matching.controller;

import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.matching.ActivityType;
import com.mannschaft.app.matching.MatchCategory;
import com.mannschaft.app.matching.MatchLevel;
import com.mannschaft.app.matching.MatchRequestStatus;
import com.mannschaft.app.matching.MatchVisibility;
import com.mannschaft.app.matching.dto.MatchRequestResponse;
import com.mannschaft.app.matching.entity.MatchRequestEntity;
import com.mannschaft.app.matching.entity.NgTeamEntity;
import com.mannschaft.app.matching.repository.MatchRequestRepository;
import com.mannschaft.app.matching.repository.NgTeamRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MatchRequestController} のキーワード複合検索 統合テスト。
 *
 * <p><b>目的（根治対象）</b>: {@code GET /api/v1/matching/requests} は {@code keyword} 指定時に
 * {@code searchByKeyword} 分岐へ入り、prefecture/city/activityType/category/level/visibility の
 * 絞り込みを全て無視していた。本テストは keyword（FULLTEXT）と全条件を同時に AND で絞れることを検証する。</p>
 *
 * <h3>FULLTEXT インデックスについて</h3>
 * <p>テストプロファイルは {@code ddl-auto: create}（Flyway 無効）でスキーマを生成するため、
 * 本番 DDL（{@code V8.003}）で定義される FULLTEXT インデックス {@code ft_mr_search} が
 * Hibernate では作成されない。{@code MATCH ... AGAINST} を成立させるため、
 * {@link #ensureFulltextIndex()} で本番同等の FULLTEXT インデックスを補完する（症状隠しではなく、
 * 本番スキーマの再現）。</p>
 *
 * <h3>キーワードの制約（ngram パーサ未指定）</h3>
 * <p>{@code ft_mr_search} は ngram パーサ未指定のため日本語部分一致は効かない。テストデータの
 * title/activity_detail は英語スペース区切りトークンにし、keyword も 3 文字以上のトークンを使う
 * （MySQL InnoDB の {@code innodb_ft_min_token_size} 既定=3）。BOOLEAN MODE 前提。</p>
 */
@DisplayName("MatchRequestController キーワード複合検索 統合テスト（F08.1）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class MatchRequestControllerKeywordSearchIntegrationTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private MatchRequestController controller;

    @Autowired
    private MatchRequestRepository requestRepository;

    @Autowired
    private NgTeamRepository ngTeamRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 検索を行うチーム（＝認証済みプリンシパル）。 */
    private static final Long SEARCHER = 700_001L;
    private static final Long TEAM_A = 700_101L;
    private static final Long TEAM_B = 700_102L;
    private static final Long TEAM_C = 700_103L;
    private static final Long TEAM_NG = 700_199L;

    private static final String KEYWORD = "practice";

    @BeforeEach
    void setUp() {
        ensureFulltextIndex();
        // トランザクションを張らず（FULLTEXT 索引は commit 後に検索可能なため）、毎回 native DELETE で掃除する。
        jdbcTemplate.update("DELETE FROM match_requests");
        jdbcTemplate.update("DELETE FROM ng_teams");
        authenticateAs(SEARCHER);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ============================================================
    // AC-1: keyword + prefectureCode
    // ============================================================
    @Test
    @DisplayName("AC-1: keyword＋prefectureCode でキーワード一致かつ指定県のみに絞られる")
    void keywordAndPrefecture() {
        MatchRequestEntity hit = persistOpen(TEAM_A, "Soccer practice session", "13", null,
                MatchCategory.ANY, MatchLevel.ANY, MatchVisibility.PLATFORM);
        persistOpen(TEAM_B, "Weekend practice match", "27", null,
                MatchCategory.ANY, MatchLevel.ANY, MatchVisibility.PLATFORM); // 他県 → 除外
        persistOpen(TEAM_C, "Baseball tournament final", "13", null,
                MatchCategory.ANY, MatchLevel.ANY, MatchVisibility.PLATFORM); // 非一致 → 除外

        List<MatchRequestResponse> content = search(KEYWORD, "13", null, null, null, null, null, 0, 20);

        assertThat(content).extracting(MatchRequestResponse::getId).containsExactly(hit.getId());
    }

    // ============================================================
    // AC-2: keyword + cityCode
    // ============================================================
    @Test
    @DisplayName("AC-2: keyword＋cityCode で絞られる")
    void keywordAndCity() {
        MatchRequestEntity hit = persistOpen(TEAM_A, "Soccer practice session", "13", "13101",
                MatchCategory.ANY, MatchLevel.ANY, MatchVisibility.PLATFORM);
        persistOpen(TEAM_B, "Evening practice drill", "13", "13102",
                MatchCategory.ANY, MatchLevel.ANY, MatchVisibility.PLATFORM); // 別市 → 除外

        List<MatchRequestResponse> content = search(KEYWORD, null, "13101", null, null, null, null, 0, 20);

        assertThat(content).extracting(MatchRequestResponse::getId).containsExactly(hit.getId());
    }

    // ============================================================
    // AC-3: keyword + category
    // ============================================================
    @Test
    @DisplayName("AC-3: keyword＋category で絞られる")
    void keywordAndCategory() {
        MatchRequestEntity hit = persistOpen(TEAM_A, "Junior practice camp", "13", null,
                MatchCategory.JUNIOR_HIGH, MatchLevel.ANY, MatchVisibility.PLATFORM);
        persistOpen(TEAM_B, "Adult practice league", "13", null,
                MatchCategory.ADULT, MatchLevel.ANY, MatchVisibility.PLATFORM); // 別カテゴリ → 除外

        List<MatchRequestResponse> content = search(KEYWORD, null, null, null, "JUNIOR_HIGH", null, null, 0, 20);

        assertThat(content).extracting(MatchRequestResponse::getId).containsExactly(hit.getId());
    }

    // ============================================================
    // AC-4: keyword + level
    // ============================================================
    @Test
    @DisplayName("AC-4: keyword＋level で絞られる")
    void keywordAndLevel() {
        MatchRequestEntity hit = persistOpen(TEAM_A, "Beginner practice class", "13", null,
                MatchCategory.ANY, MatchLevel.BEGINNER, MatchVisibility.PLATFORM);
        persistOpen(TEAM_B, "Advanced practice squad", "13", null,
                MatchCategory.ANY, MatchLevel.ADVANCED, MatchVisibility.PLATFORM); // 別レベル → 除外

        List<MatchRequestResponse> content = search(KEYWORD, null, null, null, null, "BEGINNER", null, 0, 20);

        assertThat(content).extracting(MatchRequestResponse::getId).containsExactly(hit.getId());
    }

    // ============================================================
    // AC-5: keyword + visibility
    // ============================================================
    @Test
    @DisplayName("AC-5: keyword＋visibility で絞られる")
    void keywordAndVisibility() {
        MatchRequestEntity hit = persistOpen(TEAM_A, "Open practice event", "13", null,
                MatchCategory.ANY, MatchLevel.ANY, MatchVisibility.PLATFORM);
        persistOpen(TEAM_B, "Internal practice meeting", "13", null,
                MatchCategory.ANY, MatchLevel.ANY, MatchVisibility.ORGANIZATION); // 別公開範囲 → 除外

        List<MatchRequestResponse> content = search(KEYWORD, null, null, null, null, null, "PLATFORM", 0, 20);

        assertThat(content).extracting(MatchRequestResponse::getId).containsExactly(hit.getId());
    }

    // ============================================================
    // AC-6: keyword + (prefecture + category) 複合 AND
    // ============================================================
    @Test
    @DisplayName("AC-6: keyword＋(prefecture＋category) の複合 AND で絞られる")
    void keywordAndPrefectureAndCategory() {
        MatchRequestEntity hit = persistOpen(TEAM_A, "Junior practice camp", "13", null,
                MatchCategory.JUNIOR_HIGH, MatchLevel.ANY, MatchVisibility.PLATFORM);
        persistOpen(TEAM_B, "Junior practice camp", "27", null,
                MatchCategory.JUNIOR_HIGH, MatchLevel.ANY, MatchVisibility.PLATFORM); // 県違い → 除外
        persistOpen(TEAM_C, "Adult practice camp", "13", null,
                MatchCategory.ADULT, MatchLevel.ANY, MatchVisibility.PLATFORM); // カテゴリ違い → 除外

        List<MatchRequestResponse> content = search(KEYWORD, "13", null, null, "JUNIOR_HIGH", null, null, 0, 20);

        assertThat(content).extracting(MatchRequestResponse::getId).containsExactly(hit.getId());
    }

    // ============================================================
    // AC-7: keyword 未指定は従来条件検索と同一（回帰ゼロ）
    // ============================================================
    @Test
    @DisplayName("AC-7: keyword 未指定は従来条件検索と同一結果（prefecture のみで絞られる）")
    void noKeywordUsesConditionSearch() {
        MatchRequestEntity in1 = persistOpen(TEAM_A, "Any title one", "13", null,
                MatchCategory.ANY, MatchLevel.ANY, MatchVisibility.PLATFORM);
        MatchRequestEntity in2 = persistOpen(TEAM_B, "Any title two", "13", null,
                MatchCategory.ANY, MatchLevel.ANY, MatchVisibility.PLATFORM);
        persistOpen(TEAM_C, "Any title three", "27", null,
                MatchCategory.ANY, MatchLevel.ANY, MatchVisibility.PLATFORM); // 他県 → 除外

        List<MatchRequestResponse> content = search(null, "13", null, null, null, null, null, 0, 20);

        assertThat(content).extracting(MatchRequestResponse::getId)
                .containsExactlyInAnyOrder(in1.getId(), in2.getId());
    }

    // ============================================================
    // AC-8: keyword 指定時も NG チーム（双方向ブロック）除外
    // ============================================================
    @Test
    @DisplayName("AC-8: keyword 指定時も NG チーム（双方向ブロック）の募集を除外")
    void keywordExcludesNgTeam() {
        MatchRequestEntity hit = persistOpen(TEAM_A, "Soccer practice session", "13", null,
                MatchCategory.ANY, MatchLevel.ANY, MatchVisibility.PLATFORM);
        persistOpen(TEAM_NG, "Blocked practice session", "13", null,
                MatchCategory.ANY, MatchLevel.ANY, MatchVisibility.PLATFORM);
        // SEARCHER が TEAM_NG をブロック
        ngTeamRepository.saveAndFlush(NgTeamEntity.builder()
                .teamId(SEARCHER).blockedTeamId(TEAM_NG).reason("test").build());

        List<MatchRequestResponse> content = search(KEYWORD, null, null, null, null, null, null, 0, 20);

        assertThat(content).extracting(MatchRequestResponse::getId).containsExactly(hit.getId());
    }

    // ============================================================
    // AC-9: keyword 指定時も期限切れ除外
    // ============================================================
    @Test
    @DisplayName("AC-9: keyword 指定時も期限切れ（expires_at<=now）の募集を除外")
    void keywordExcludesExpired() {
        MatchRequestEntity hit = persistOpen(TEAM_A, "Fresh practice session", "13", null,
                MatchCategory.ANY, MatchLevel.ANY, MatchVisibility.PLATFORM,
                LocalDateTime.now().plusDays(3), false);
        persistOpen(TEAM_B, "Expired practice session", "13", null,
                MatchCategory.ANY, MatchLevel.ANY, MatchVisibility.PLATFORM,
                LocalDateTime.now().minusDays(1), false); // 期限切れ → 除外

        List<MatchRequestResponse> content = search(KEYWORD, null, null, null, null, null, null, 0, 20);

        assertThat(content).extracting(MatchRequestResponse::getId).containsExactly(hit.getId());
    }

    // ============================================================
    // AC-10: keyword 指定時も status!=OPEN・soft-delete 済みを除外
    // ============================================================
    @Test
    @DisplayName("AC-10: keyword 指定時も OPEN 以外・削除済みの募集を除外")
    void keywordExcludesNonOpenAndDeleted() {
        MatchRequestEntity hit = persistOpen(TEAM_A, "Open practice session", "13", null,
                MatchCategory.ANY, MatchLevel.ANY, MatchVisibility.PLATFORM);
        // MATCHED（OPEN 以外）→ 除外
        persist(TEAM_B, "Matched practice session", "13", null,
                MatchCategory.ANY, MatchLevel.ANY, MatchVisibility.PLATFORM,
                MatchRequestStatus.MATCHED, null, false);
        // soft-delete 済み → 除外
        persist(TEAM_C, "Deleted practice session", "13", null,
                MatchCategory.ANY, MatchLevel.ANY, MatchVisibility.PLATFORM,
                MatchRequestStatus.OPEN, null, true);

        List<MatchRequestResponse> content = search(KEYWORD, null, null, null, null, null, null, 0, 20);

        assertThat(content).extracting(MatchRequestResponse::getId).containsExactly(hit.getId());
    }

    // ============================================================
    // AC-11: keyword 指定時のページング・size 上限・totalElements 整合
    // ============================================================
    @Test
    @DisplayName("AC-11: keyword＋prefecture のページングと totalElements が複合件数と整合する")
    void keywordPagingTotalElementsMatchesCombinedCount() {
        // pref13 で keyword 一致 5 件
        for (int i = 0; i < 5; i++) {
            persistOpen(TEAM_A, "Practice game number " + i, "13", null,
                    MatchCategory.ANY, MatchLevel.ANY, MatchVisibility.PLATFORM);
        }
        // pref27 で keyword 一致 3 件（複合条件で除外されるべき）
        for (int i = 0; i < 3; i++) {
            persistOpen(TEAM_B, "Practice game other " + i, "27", null,
                    MatchCategory.ANY, MatchLevel.ANY, MatchVisibility.PLATFORM);
        }

        // 引数順: prefectureCode, cityCode, activityType, category, keyword, level, visibility, page, size
        ResponseEntity<PagedResponse<MatchRequestResponse>> res =
                controller.searchRequests("13", null, null, null, KEYWORD, null, null, 0, 2);

        PagedResponse<MatchRequestResponse> body = res.getBody();
        assertThat(body).isNotNull();
        // 複合件数 = pref13 かつ keyword 一致 = 5 件（pref27 の 3 件は除外）
        assertThat(body.getMeta().getTotal()).isEqualTo(5);
        assertThat(body.getMeta().getTotalPages()).isEqualTo(3);
        assertThat(body.getData()).hasSize(2);

        // size 上限 50 の検証（size=100 → 実効 50）
        ResponseEntity<PagedResponse<MatchRequestResponse>> capped =
                controller.searchRequests("13", null, null, null, KEYWORD, null, null, 0, 100);
        assertThat(capped.getBody().getMeta().getSize()).isEqualTo(50);
    }

    // ============================================================
    // ヘルパ
    // ============================================================

    private List<MatchRequestResponse> search(String keyword, String prefectureCode, String cityCode,
                                              String activityType, String category, String level,
                                              String visibility, int page, int size) {
        ResponseEntity<PagedResponse<MatchRequestResponse>> res = controller.searchRequests(
                prefectureCode, cityCode, activityType, category, keyword, level, visibility, page, size);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        return res.getBody().getData();
    }

    private MatchRequestEntity persistOpen(Long teamId, String title, String prefectureCode, String cityCode,
                                           MatchCategory category, MatchLevel level, MatchVisibility visibility) {
        return persist(teamId, title, prefectureCode, cityCode, category, level, visibility,
                MatchRequestStatus.OPEN, null, false);
    }

    private MatchRequestEntity persistOpen(Long teamId, String title, String prefectureCode, String cityCode,
                                           MatchCategory category, MatchLevel level, MatchVisibility visibility,
                                           LocalDateTime expiresAt, boolean deleted) {
        return persist(teamId, title, prefectureCode, cityCode, category, level, visibility,
                MatchRequestStatus.OPEN, expiresAt, deleted);
    }

    private MatchRequestEntity persist(Long teamId, String title, String prefectureCode, String cityCode,
                                       MatchCategory category, MatchLevel level, MatchVisibility visibility,
                                       MatchRequestStatus status, LocalDateTime expiresAt, boolean deleted) {
        MatchRequestEntity e = MatchRequestEntity.builder()
                .teamId(teamId)
                .title(title)
                .activityType(ActivityType.PRACTICE)
                .activityDetail("detail")
                .category(category)
                .level(level)
                .visibility(visibility)
                .prefectureCode(prefectureCode)
                .cityCode(cityCode)
                .status(status)
                .expiresAt(expiresAt)
                .build();
        if (deleted) {
            e.softDelete();
        }
        return requestRepository.saveAndFlush(e);
    }

    private void authenticateAs(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    /**
     * FULLTEXT インデックス ft_mr_search を補完する（本番 V8.003 と同等）。
     * ddl-auto:create では Hibernate が FULLTEXT を作らないため、MATCH ... AGAINST が成立しない。
     * 既に存在する場合は何もしない（information_schema で判定）。
     */
    private void ensureFulltextIndex() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.STATISTICS "
                        + "WHERE table_schema = DATABASE() AND table_name = 'match_requests' "
                        + "AND index_name = 'ft_mr_search'",
                Integer.class);
        if (count == null || count == 0) {
            jdbcTemplate.execute(
                    "ALTER TABLE match_requests ADD FULLTEXT INDEX ft_mr_search (title, activity_detail)");
        }
    }
}
