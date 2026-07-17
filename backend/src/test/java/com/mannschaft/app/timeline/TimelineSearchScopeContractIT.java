package com.mannschaft.app.timeline;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.timeline.controller.TimelineFeedController;
import com.mannschaft.app.timeline.dto.PostResponse;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.enums.VillageBulletinVisibility;
import com.mannschaft.app.village.entity.enums.VillageJoinPolicy;
import com.mannschaft.app.village.entity.enums.VillageType;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
import com.mannschaft.app.village.repository.VillageRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 認可根治戦役 Wave3-B7-timeline（本丸）: {@code GET /api/v1/timeline/search} の全文検索が
 * 呼び出し元の可視 scope 外の投稿を漏らさないことを検証する契約テスト（試練）。
 *
 * <p>正本: 依頼文（Wave3-B7-timeline節・本丸）・{@code TimelinePostRepository#SEARCH_QUERY}
 * （旧: MATCH...AGAINST のみで scope 絞り込み皆無 → TEAM/ORGANIZATION/PERSONAL 全投稿が
 * キーワード一致で横断ヒットする本文漏洩）。金型: {@code TimelineReadScopeContractIT}
 * （Controller 直接 Autowire + SecurityContext 差し替え）・
 * {@code MatchRequestControllerKeywordSearchIntegrationTest}（FULLTEXT ngram 検索 IT の作法）。</p>
 *
 * <h3>クラスレベル {@code @Transactional} を使わない理由</h3>
 * <p>InnoDB の FULLTEXT INDEX は<strong>コミット後</strong>のデータのみ検索対象になる
 * （{@code MATCH...AGAINST} はテストTX内の未コミット INSERT を拾えない）。よって本 IT は
 * {@code @Transactional} で包まず、{@code TimelinePostRepository#saveAndFlush} で都度コミットし、
 * {@code @BeforeEach}/{@code @AfterEach} で対象ユーザーIDに絞った native DELETE により後始末する
 * （{@code MatchRequestControllerKeywordSearchIntegrationTest} と同一方針）。</p>
 *
 * <h3>FULLTEXT インデックスについて</h3>
 * <p>テストプロファイルは {@code ddl-auto: create}（Flyway 無効）でスキーマを生成するため、
 * 本番 DDL（{@code V4.001}）で定義される FULLTEXT INDEX {@code ft_timeline_posts_content}
 * （{@code WITH PARSER ngram}）が Hibernate では作成されない。{@code MATCH...AGAINST} を
 * 成立させるため {@link #ensureFulltextIndex()} で本番同等のインデックスを補完する
 * （症状隠しではなく本番スキーマの再現）。</p>
 *
 * <p><b>検証方針</b>: 別 team/org/他人 PERSONAL の投稿を一意なキーワードで seed し、
 * 非メンバー・第三者の検索結果に<strong>出てこない</strong>こと（漏洩防止）と、
 * 正当な所有者・メンバーには出ること（機能維持）の両面を検証する。</p>
 *
 * <p><b>VILLAGE は検索対象外</b>（fail-safe な意図的除外。{@code SEARCH_QUERY} の Javadoc 参照）。
 * 本 IT では「VILLAGE 投稿が検索結果に一切出ない」ことを正の挙動として検証する
 * （村タイムラインは {@code VillageSearchService} が別途カバー済み）。</p>
 */
@DisplayName("timeline 全文検索 scope 漏洩防止契約テスト（認可根治 Wave3-B7-timeline・本丸）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class TimelineSearchScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private TimelineFeedController feedController;

    @Autowired
    private TimelinePostRepository postRepository;

    @Autowired
    private MembershipRepository membershipRepository;

    @Autowired
    private VillageRepository villageRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // --- テスト用ユーザー（高位ID・seed と衝突しない） ---
    private static final Long USER_TEAM_A_MEMBER = 92_301L;
    private static final Long USER_ORG_A_MEMBER = 92_302L;
    private static final Long USER_OUTSIDER = 92_303L;

    // --- 所属スコープ ---
    private static final Long TEAM_A = 70_301L;
    private static final Long ORG_A = 80_301L;

    /** FULLTEXT BOOLEAN MODE で一意にヒットさせるためのキーワード（他テストの既存データと非衝突）。 */
    private static final String UNIQUE_KEYWORD = "zwaveb7tsearchkw";

    private static final String VILLAGE_SLUG_PREFIX = "b7t-search-village-";

    @BeforeEach
    void setUp() {
        ensureFulltextIndex();
        cleanUpTestData();

        membershipRepository.saveAndFlush(MembershipEntity.builder()
                .userId(USER_TEAM_A_MEMBER)
                .scopeType(ScopeType.TEAM)
                .scopeId(TEAM_A)
                .roleKind(RoleKind.MEMBER)
                .joinedAt(LocalDateTime.now())
                .build());
        membershipRepository.saveAndFlush(MembershipEntity.builder()
                .userId(USER_ORG_A_MEMBER)
                .scopeType(ScopeType.ORGANIZATION)
                .scopeId(ORG_A)
                .roleKind(RoleKind.MEMBER)
                .joinedAt(LocalDateTime.now())
                .build());
    }

    @AfterEach
    void tearDown() {
        cleanUpTestData();
        SecurityContextHolder.clearContext();
    }

    /** 本 IT が作成したデータのみを対象ユーザーID・村slugプレフィクスで絞って掃除する（他テストのデータは触らない）。 */
    private void cleanUpTestData() {
        jdbcTemplate.update("DELETE FROM timeline_posts WHERE user_id IN (?, ?, ?)",
                USER_TEAM_A_MEMBER, USER_ORG_A_MEMBER, USER_OUTSIDER);
        jdbcTemplate.update("DELETE FROM memberships WHERE user_id IN (?, ?)",
                USER_TEAM_A_MEMBER, USER_ORG_A_MEMBER);
        jdbcTemplate.update("DELETE FROM village_memberships WHERE village_id IN "
                + "(SELECT id FROM villages WHERE slug LIKE CONCAT(?, '%'))", VILLAGE_SLUG_PREFIX);
        jdbcTemplate.update("DELETE FROM villages WHERE slug LIKE CONCAT(?, '%')", VILLAGE_SLUG_PREFIX);
    }

    /**
     * FULLTEXT インデックス {@code ft_timeline_posts_content} を補完する
     * （本番 V4.001 と同等・ngram パーサ付き）。ddl-auto:create では Hibernate が FULLTEXT を作らないため、
     * {@code MATCH...AGAINST} が成立しない。既に存在する場合は何もしない（information_schema で判定）。
     */
    private void ensureFulltextIndex() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.STATISTICS "
                        + "WHERE table_schema = DATABASE() AND table_name = 'timeline_posts' "
                        + "AND index_name = 'ft_timeline_posts_content'",
                Integer.class);
        if (count == null || count == 0) {
            jdbcTemplate.execute(
                    "ALTER TABLE timeline_posts ADD FULLTEXT INDEX ft_timeline_posts_content (content) "
                            + "WITH PARSER ngram");
        }
    }

    private TimelinePostEntity savePost(PostScopeType scopeType, Long scopeId, UUID scopeVillageId, Long userId) {
        return postRepository.saveAndFlush(TimelinePostEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .scopeVillageId(scopeVillageId)
                .userId(userId)
                .content(UNIQUE_KEYWORD + " honbun " + scopeType + " " + userId)
                .status(PostStatus.PUBLISHED)
                .build());
    }

    private void setAuthentication(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private List<Long> searchIds(String q) {
        ResponseEntity<ApiResponse<List<PostResponse>>> response = feedController.searchPosts(q, 50);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody().getData().stream().map(PostResponse::getId).toList();
    }

    @Nested
    @DisplayName("TEAM/ORGANIZATION 投稿の scope 漏洩防止")
    class TeamOrgLeakPrevention {

        @Test
        @DisplayName("[本丸] 別TEAMの投稿は非メンバーの検索結果に出てこない")
        void 別TEAM投稿は非メンバーには出ない() {
            Long otherTeamPost = savePost(PostScopeType.TEAM, TEAM_A, null, USER_TEAM_A_MEMBER).getId();

            setAuthentication(USER_OUTSIDER);
            List<Long> ids = searchIds(UNIQUE_KEYWORD);

            assertThat(ids).doesNotContain(otherTeamPost);
        }

        @Test
        @DisplayName("正当メンバーには自分の所属TEAM投稿がヒットする")
        void 正当メンバーには自TEAM投稿がヒットする() {
            Long teamPost = savePost(PostScopeType.TEAM, TEAM_A, null, USER_TEAM_A_MEMBER).getId();

            setAuthentication(USER_TEAM_A_MEMBER);
            List<Long> ids = searchIds(UNIQUE_KEYWORD);

            assertThat(ids).contains(teamPost);
        }

        @Test
        @DisplayName("[本丸] 別ORGANIZATIONの投稿は非メンバーの検索結果に出てこない")
        void 別ORG投稿は非メンバーには出ない() {
            Long otherOrgPost = savePost(PostScopeType.ORGANIZATION, ORG_A, null, USER_ORG_A_MEMBER).getId();

            setAuthentication(USER_OUTSIDER);
            List<Long> ids = searchIds(UNIQUE_KEYWORD);

            assertThat(ids).doesNotContain(otherOrgPost);
        }

        @Test
        @DisplayName("正当メンバーには自分の所属ORGANIZATION投稿がヒットする")
        void 正当メンバーには自ORG投稿がヒットする() {
            Long orgPost = savePost(PostScopeType.ORGANIZATION, ORG_A, null, USER_ORG_A_MEMBER).getId();

            setAuthentication(USER_ORG_A_MEMBER);
            List<Long> ids = searchIds(UNIQUE_KEYWORD);

            assertThat(ids).contains(orgPost);
        }

        @Test
        @DisplayName("[本丸] TEAM所属者は自分のTEAM投稿は見えるが他人のORGANIZATION投稿は見えない（横断混在なし）")
        void 複数scope混在でも自scopeのみヒットする() {
            Long teamAPost = savePost(PostScopeType.TEAM, TEAM_A, null, USER_TEAM_A_MEMBER).getId();
            // ORG_A は USER_TEAM_A_MEMBER にとって非所属スコープ
            Long orgAPost = savePost(PostScopeType.ORGANIZATION, ORG_A, null, USER_ORG_A_MEMBER).getId();

            setAuthentication(USER_TEAM_A_MEMBER);
            List<Long> ids = searchIds(UNIQUE_KEYWORD);

            assertThat(ids).contains(teamAPost);
            assertThat(ids).doesNotContain(orgAPost);
        }
    }

    @Nested
    @DisplayName("PERSONAL 投稿の scope 漏洩防止")
    class PersonalLeakPrevention {

        @Test
        @DisplayName("[本丸] 他人のPERSONAL投稿は検索結果に出てこない")
        void 他人のPERSONAL投稿は出ない() {
            Long otherPersonalPost =
                    savePost(PostScopeType.PERSONAL, USER_TEAM_A_MEMBER, null, USER_TEAM_A_MEMBER).getId();

            setAuthentication(USER_OUTSIDER);
            List<Long> ids = searchIds(UNIQUE_KEYWORD);

            assertThat(ids).doesNotContain(otherPersonalPost);
        }

        @Test
        @DisplayName("自分のPERSONAL投稿は検索結果に出る")
        void 自分のPERSONAL投稿は出る() {
            Long myPersonalPost = savePost(PostScopeType.PERSONAL, USER_OUTSIDER, null, USER_OUTSIDER).getId();

            setAuthentication(USER_OUTSIDER);
            List<Long> ids = searchIds(UNIQUE_KEYWORD);

            assertThat(ids).contains(myPersonalPost);
        }
    }

    @Nested
    @DisplayName("PUBLIC 投稿は誰でもヒットする（回帰防止）")
    class PublicAlwaysVisible {

        @Test
        @DisplayName("PUBLIC投稿は所属を問わず検索結果に出る")
        void PUBLIC投稿は所属不問でヒットする() {
            Long publicPost = savePost(PostScopeType.PUBLIC, 0L, null, USER_TEAM_A_MEMBER).getId();

            setAuthentication(USER_OUTSIDER);
            List<Long> ids = searchIds(UNIQUE_KEYWORD);

            assertThat(ids).contains(publicPost);
        }
    }

    @Nested
    @DisplayName("VILLAGE 投稿は意図的に検索対象外（fail-safe）")
    class VillageExcluded {

        @Test
        @DisplayName("VILLAGE投稿は村メンバー本人であっても timeline 全文検索には出ない")
        void VILLAGE投稿は村メンバーでも出ない() {
            VillageEntity village = villageRepository.saveAndFlush(VillageEntity.builder()
                    .slug(VILLAGE_SLUG_PREFIX + System.nanoTime())
                    .name("検索契約テスト村")
                    .type(VillageType.COMMUNITY)
                    .joinPolicy(VillageJoinPolicy.FREE)
                    .visibility(VillageVisibility.PUBLIC)
                    .bulletinVisibility(VillageBulletinVisibility.MEMBERS_ONLY)
                    .build());
            Long villagePost =
                    savePost(PostScopeType.VILLAGE, 0L, village.getId(), USER_TEAM_A_MEMBER).getId();

            setAuthentication(USER_TEAM_A_MEMBER);
            List<Long> ids = searchIds(UNIQUE_KEYWORD);

            assertThat(ids).doesNotContain(villagePost);
        }
    }
}
