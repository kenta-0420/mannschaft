package com.mannschaft.app.publicview.service;

import com.mannschaft.app.cms.PostStatus;
import com.mannschaft.app.cms.Visibility;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.team.entity.TeamEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * F19.1 sitemap 投稿 URL の<b>親スコープ公開判定</b>の結合テスト。
 *
 * <h2>このテストが守っているもの</h2>
 * <p>sitemap は URL をこちらから検索エンジンへ差し出す。単票 API なら「非公開なら 404」で
 * 守れるが、sitemap に載せた時点で<b>URL の存在自体が漏れる</b>（後から 404 にしても手遅れ）。
 * 投稿 URL は {@code /public/teams/{teamId}/posts/{postId}} という形をしているため、
 * 親チーム / 組織が非公開のまま載せると「非公開チームが実在すること」と
 * 「その配下の投稿 ID」を同時に開示してしまう。</p>
 *
 * <p>載ってよいのは<b>次の 4 条件すべて</b>を満たす投稿だけ:</p>
 * <ol>
 *   <li>{@code visibility = PUBLIC}</li>
 *   <li>{@code status = PUBLISHED}</li>
 *   <li>論理削除されていない</li>
 *   <li><b>親スコープ（チーム / 組織）も公開である</b>（PUBLIC・未アーカイブ・未論理削除）</li>
 * </ol>
 *
 * <p>構成は活動記録側の金型 {@code ActivityResultSitemapQueryTest} を踏襲
 * （実 MySQL / Testcontainers・{@code @Transactional} ロールバック隔離）。</p>
 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F19.1 sitemap 投稿収録 — 親スコープが非公開なら載らないことの結合テスト")
class SitemapPostParentScopeQueryTest extends AbstractMySqlIntegrationTest {

    /** slug 衝突回避用の連番（テストクラス内で一意なら十分）。 */
    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());

    @Autowired
    private SitemapQueryService sitemapQueryService;

    @PersistenceContext
    private EntityManager em;

    // ── 親スコープ
    private Long publicTeamId;
    private Long privateTeamId;
    private Long archivedTeamId;
    private Long deletedTeamId;
    private Long publicOrgId;
    private Long privateOrgId;
    private Long archivedOrgId;
    private Long deletedOrgId;

    // ── 唯一「載ってよい」投稿
    private Long visibleTeamPostId;
    private Long visibleOrgPostId;

    @BeforeEach
    void setUp() {
        // 既存データ（他テストのコミット残骸など）に結果を左右されないよう、
        // 本テストの観測対象である「公開スコープ」を自分のフィクスチャだけに限定する。
        hideAllExistingScopes();

        publicTeamId = persistTeam(TeamEntity.Visibility.PUBLIC, false, false);
        privateTeamId = persistTeam(TeamEntity.Visibility.PRIVATE, false, false);
        archivedTeamId = persistTeam(TeamEntity.Visibility.PUBLIC, true, false);
        deletedTeamId = persistTeam(TeamEntity.Visibility.PUBLIC, false, true);

        publicOrgId = persistOrg(OrganizationEntity.Visibility.PUBLIC, false, false);
        privateOrgId = persistOrg(OrganizationEntity.Visibility.PRIVATE, false, false);
        archivedOrgId = persistOrg(OrganizationEntity.Visibility.PUBLIC, true, false);
        deletedOrgId = persistOrg(OrganizationEntity.Visibility.PUBLIC, false, true);

        // ── 載ってよいもの（公開スコープ配下・PUBLIC・PUBLISHED）
        visibleTeamPostId = persistTeamPost(publicTeamId, Visibility.PUBLIC, PostStatus.PUBLISHED);
        visibleOrgPostId = persistOrgPost(publicOrgId, Visibility.PUBLIC, PostStatus.PUBLISHED);

        // ── 載ってはいけないもの（1 条件ずつ崩す。投稿自身は文句なしの PUBLIC + PUBLISHED）
        persistTeamPost(privateTeamId, Visibility.PUBLIC, PostStatus.PUBLISHED);
        persistTeamPost(archivedTeamId, Visibility.PUBLIC, PostStatus.PUBLISHED);
        persistTeamPost(deletedTeamId, Visibility.PUBLIC, PostStatus.PUBLISHED);
        persistOrgPost(privateOrgId, Visibility.PUBLIC, PostStatus.PUBLISHED);
        persistOrgPost(archivedOrgId, Visibility.PUBLIC, PostStatus.PUBLISHED);
        persistOrgPost(deletedOrgId, Visibility.PUBLIC, PostStatus.PUBLISHED);

        // ── 公開スコープ配下でも投稿自身の条件を満たさないもの（既存挙動の保持確認用）
        persistTeamPost(publicTeamId, Visibility.PUBLIC, PostStatus.DRAFT);
        persistTeamPost(publicTeamId, Visibility.MEMBERS_ONLY, PostStatus.PUBLISHED);
        persistOrgPost(publicOrgId, Visibility.PUBLIC, PostStatus.DRAFT);
        persistOrgPost(publicOrgId, Visibility.MEMBERS_ONLY, PostStatus.PUBLISHED);

        em.flush();
        em.clear();
    }

    @Test
    @DisplayName("AC1: 非公開(PRIVATE)チーム配下の PUBLIC+PUBLISHED 投稿は sitemap に載らない")
    void ac1_privateTeamPost_notIncluded() {
        assertThat(sitemapQueryService.findPublicTeamPostEntries())
                .extracting(SitemapPostEntry::scopeId)
                .doesNotContain(privateTeamId);
    }

    @Test
    @DisplayName("AC2: アーカイブ済みチーム配下の PUBLIC+PUBLISHED 投稿も載らない")
    void ac2_archivedTeamPost_notIncluded() {
        assertThat(sitemapQueryService.findPublicTeamPostEntries())
                .extracting(SitemapPostEntry::scopeId)
                .doesNotContain(archivedTeamId);
    }

    @Test
    @DisplayName("AC3: 論理削除済みチーム配下の PUBLIC+PUBLISHED 投稿も載らない")
    void ac3_softDeletedTeamPost_notIncluded() {
        assertThat(sitemapQueryService.findPublicTeamPostEntries())
                .extracting(SitemapPostEntry::scopeId)
                .doesNotContain(deletedTeamId);
    }

    @Test
    @DisplayName("AC4: 組織側も同じ — 非公開・アーカイブ・論理削除の組織配下は載らない")
    void ac4_nonPublicOrganizationPosts_notIncluded() {
        assertThat(sitemapQueryService.findPublicOrganizationPostEntries())
                .extracting(SitemapPostEntry::scopeId)
                .doesNotContain(privateOrgId, archivedOrgId, deletedOrgId);
    }

    @Test
    @DisplayName("AC5: 公開チーム・公開組織配下の PUBLIC+PUBLISHED 投稿は従来どおり載る（過剰除去の防止）")
    void ac5_publicScopePosts_stillIncluded() {
        assertThat(sitemapQueryService.findPublicTeamPostEntries())
                .extracting(SitemapPostEntry::postId)
                .contains(visibleTeamPostId);
        assertThat(sitemapQueryService.findPublicOrganizationPostEntries())
                .extracting(SitemapPostEntry::postId)
                .contains(visibleOrgPostId);
    }

    @Test
    @DisplayName("AC6: 公開スコープ配下でも DRAFT / MEMBERS_ONLY 投稿は載らない（既存挙動の保持）")
    void ac6_draftAndMembersOnlyPosts_notIncluded() {
        // 公開チーム配下に置いた投稿は 3 件（PUBLIC+PUBLISHED / DRAFT / MEMBERS_ONLY）だが、
        // 載ってよいのは 1 件だけ。件数まで固定して「DRAFT も一緒に載っていた」を検出する。
        assertThat(sitemapQueryService.findPublicTeamPostEntries())
                .extracting(SitemapPostEntry::postId)
                .containsExactly(visibleTeamPostId);
        assertThat(sitemapQueryService.findPublicOrganizationPostEntries())
                .extracting(SitemapPostEntry::postId)
                .containsExactly(visibleOrgPostId);
    }

    @Test
    @DisplayName("AC7: 公開チームが 0 件でも SQL 構文エラーにならず空を返す（IN () 回避）")
    void ac7_noPublicTeams_returnsEmptyWithoutSqlError() {
        hideAllExistingScopes();
        em.flush();
        em.clear();

        assertThatCode(() -> assertThat(sitemapQueryService.findPublicTeamPostEntries()).isEmpty())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("AC8: 公開組織が 0 件でも SQL 構文エラーにならず空を返す（IN () 回避）")
    void ac8_noPublicOrganizations_returnsEmptyWithoutSqlError() {
        hideAllExistingScopes();
        em.flush();
        em.clear();

        assertThatCode(
                () -> assertThat(sitemapQueryService.findPublicOrganizationPostEntries()).isEmpty())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("AC9: 公開チーム・組織が両方 0 件でも sitemap 用クエリ 5 種すべてが例外なく空を返す")
    void ac9_noPublicScopesAtAll_allSitemapQueriesSucceed() {
        hideAllExistingScopes();
        em.flush();
        em.clear();

        assertThatCode(() -> {
            assertThat(sitemapQueryService.findPublicTeamEntries()).isEmpty();
            assertThat(sitemapQueryService.findPublicOrganizationEntries()).isEmpty();
            assertThat(sitemapQueryService.findPublicTeamPostEntries()).isEmpty();
            assertThat(sitemapQueryService.findPublicOrganizationPostEntries()).isEmpty();
            assertThat(sitemapQueryService.findPublicActivityEntries()).isEmpty();
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("AC10: 公開スコープ数に比例したクエリ発行(N+1)が起きない")
    void ac10_noNPlusOneQueries() {
        // 公開チームを 10 件に増やしても、発行 SQL 数は「公開チーム取得 + 投稿取得」の
        // 2 本前後のまま変わらないこと（スコープごとに 1 本撃つ実装なら 10 本以上になる）。
        for (int i = 0; i < 10; i++) {
            Long teamId = persistTeam(TeamEntity.Visibility.PUBLIC, false, false);
            persistTeamPost(teamId, Visibility.PUBLIC, PostStatus.PUBLISHED);
        }
        em.flush();
        em.clear();

        Statistics stats = statisticsCleared();
        List<SitemapPostEntry> entries = sitemapQueryService.findPublicTeamPostEntries();

        assertThat(entries).hasSize(11);
        assertThat(stats.getPrepareStatementCount()).isLessThanOrEqualTo(3);
    }

    // ========================================================================
    // ヘルパ
    // ========================================================================

    private Statistics statisticsCleared() {
        SessionFactory sf = em.getEntityManagerFactory().unwrap(SessionFactory.class);
        Statistics stats = sf.getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();
        return stats;
    }

    /**
     * 既存の全チーム・全組織を「公開でない」状態に落とす（本テストのトランザクション内のみ）。
     *
     * <p>共有 Testcontainer には他テストが残したデータが居る可能性があるため、
     * 「公開スコープが 0 件」を厳密に作れるようにしておく。</p>
     */
    private void hideAllExistingScopes() {
        em.createQuery("UPDATE TeamEntity t SET t.visibility = "
                + "com.mannschaft.app.team.entity.TeamEntity.Visibility.PRIVATE").executeUpdate();
        em.createQuery("UPDATE OrganizationEntity o SET o.visibility = "
                + "com.mannschaft.app.organization.entity.OrganizationEntity.Visibility.PRIVATE")
                .executeUpdate();
        em.clear();
    }

    private Long persistTeam(TeamEntity.Visibility visibility, boolean archived, boolean softDeleted) {
        TeamEntity team = TeamEntity.builder()
                .slug("sitemap-parent-team-" + SEQ.incrementAndGet())
                .name("sitemap 親スコープテスト チーム")
                .visibility(visibility)
                .supporterEnabled(false)
                .build();
        em.persist(team);
        em.flush();
        if (archived) {
            team.archive();
        }
        if (softDeleted) {
            team.softDelete();
        }
        em.flush();
        return team.getId();
    }

    private Long persistOrg(OrganizationEntity.Visibility visibility, boolean archived, boolean softDeleted) {
        OrganizationEntity org = OrganizationEntity.builder()
                .slug("sitemap-parent-org-" + SEQ.incrementAndGet())
                .name("sitemap 親スコープテスト 組織")
                .orgType(OrganizationEntity.OrgType.OTHER)
                .visibility(visibility)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE)
                .supporterEnabled(false)
                .build();
        em.persist(org);
        em.flush();
        if (archived) {
            org.archive();
        }
        if (softDeleted) {
            org.softDelete();
        }
        em.flush();
        return org.getId();
    }

    private Long persistTeamPost(Long teamId, Visibility visibility, PostStatus status) {
        return persistPost(teamId, null, visibility, status);
    }

    private Long persistOrgPost(Long organizationId, Visibility visibility, PostStatus status) {
        return persistPost(null, organizationId, visibility, status);
    }

    private Long persistPost(Long teamId, Long organizationId, Visibility visibility, PostStatus status) {
        long seq = SEQ.incrementAndGet();
        BlogPostEntity post = BlogPostEntity.builder()
                .teamId(teamId)
                .organizationId(organizationId)
                .title("sitemap 親スコープテスト 投稿")
                .slug("sitemap-parent-post-" + seq)
                .body("本文")
                .visibility(visibility)
                .status(status)
                .build();
        em.persist(post);
        em.flush();
        return post.getId();
    }
}
