package com.mannschaft.app.social.announcement;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.dashboard.ViewerRole;
import com.mannschaft.app.dashboard.service.RoleResolver;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.support.test.TeamOrgFixtureHelper;
import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-261011-0035 ALIC-1 §2.6 役割の鮮度（AC-ALIC1-E01〜E06・試練 red）。
 *
 * <p>告知の可視性に使う役割はキャッシュしない。テスト profile は {@code spring.cache.type=none} で
 * 実キャッシュが無いため、「{@code dashboard:viewer-role} に古い役割が事前投入され、無効化が失敗した状態」を
 * {@link RoleResolver#resolveViewerRole}（{@code @Cacheable} の入口）だけが古い値を返すスタブで表す。
 * キャッシュなしの入口（{@code resolveViewerRoleForPreview}・{@code resolveViewerRolesFresh}）は本物のまま。
 * 告知部分がキャッシュなしの役割を使うよう直れば、古い値は告知の可否に効かなくなる。</p>
 *
 * <p>現状（red の理由）: チーム／組織の告知一覧 API・ダッシュボードの告知とも {@code resolveViewerRole}
 * （キャッシュあり）の結果で visibility を絞るため、降格後も MEMBERS 項目が見え、昇格後もすぐには出ない。</p>
 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("ALIC-1 E 告知の可視性に使う役割の鮮度")
class AnnouncementViewerRoleFreshnessIT extends AbstractMySqlIntegrationTest {

    private static final String PUBLIC_TITLE = "鮮度試練の公開タイトル";
    private static final String SUPPORTERS_TITLE = "鮮度試練の応援者タイトル";
    private static final String MEMBERS_TITLE = "鮮度試練のメンバー秘密タイトル";
    private static final String MEMBERS_EXCERPT = "鮮度試練のメンバー秘密抜粋";

    @Autowired private MockMvc mockMvc;
    @PersistenceContext private EntityManager em;
    @MockitoSpyBean private RoleResolver roleResolver;

    private Long teamId;
    private String teamSlug;
    private Long orgId;
    private String orgSlug;
    private Long viewerId;
    private Long authorId;

    @BeforeEach
    void setUp() {
        String key = UUID.randomUUID().toString().substring(0, 8);
        teamSlug = "fresh-team-" + key;
        orgSlug = "fresh-org-" + key;
        teamId = TeamOrgFixtureHelper.insertTeam(em, "鮮度試練チーム", teamSlug);
        orgId = TeamOrgFixtureHelper.insertOrganization(em, "鮮度試練組織", orgSlug);
        viewerId = saveUser("fresh-viewer-" + key);
        authorId = saveUser("fresh-author-" + key);
        em.flush();
    }

    // ───────── チーム側 ─────────

    @Test
    @DisplayName("AC-ALIC1-E01 チーム MEMBER→SUPPORTER 降格直後（キャッシュに MEMBER が残る）は MEMBERS 項目が一覧・未読件数・ダッシュボードから消える")
    void e01_チーム降格は即時に効く() throws Exception {
        MembershipTestHelper.insertMembership(em, viewerId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        List<Long> feeds = seedFeeds(AnnouncementScopeType.TEAM, teamId);
        staleCachedRole("TEAM", teamId, ViewerRole.MEMBER);
        setRoleKind(ScopeType.TEAM, teamId, RoleKind.SUPPORTER);

        assertList("/api/v1/teams/" + teamId + "/announcements", List.of(feeds.get(0), feeds.get(1)), 2);
        String dashboard = body("/api/v1/dashboard/team/" + teamSlug, 200);
        assertThat(dashboard).as("前提: ダッシュボードのお知らせに公開項目が出ている").contains(PUBLIC_TITLE);
        assertThat(dashboard).as("降格後の MEMBERS 項目の title_cache・excerpt_cache が残っていない")
                .doesNotContain(MEMBERS_TITLE).doesNotContain(MEMBERS_EXCERPT);
    }

    @Test
    @DisplayName("AC-ALIC1-E02 同条件でチーム離脱直後はダッシュボード 403・一覧に非公開の告知が0件")
    void e02_チーム離脱は即時に効く() throws Exception {
        MembershipTestHelper.insertMembership(em, viewerId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        Long supporters = saveFeed(AnnouncementScopeType.TEAM, teamId,
                AnnouncementVisibility.SUPPORTERS_AND_ABOVE, SUPPORTERS_TITLE, "応援者抜粋");
        Long members = saveFeed(AnnouncementScopeType.TEAM, teamId,
                AnnouncementVisibility.MEMBERS_AND_ABOVE, MEMBERS_TITLE, MEMBERS_EXCERPT);
        staleCachedRole("TEAM", teamId, ViewerRole.MEMBER);
        leave(ScopeType.TEAM, teamId);

        body("/api/v1/dashboard/team/" + teamSlug, 403);
        String list = body("/api/v1/teams/" + teamId + "/announcements", 200);
        assertThat(ids(list)).as("離脱者に会員限定の告知（%d, %d）を返さない", supporters, members).isEmpty();
        assertThat(unread(list)).isZero();
        assertThat(list).doesNotContain(MEMBERS_TITLE).doesNotContain(SUPPORTERS_TITLE);
    }

    @Test
    @DisplayName("AC-ALIC1-E03 チーム SUPPORTER→MEMBER 昇格直後（キャッシュに SUPPORTER が残る）は MEMBERS 項目がすぐ出る")
    void e03_チーム昇格は即時に効く() throws Exception {
        MembershipTestHelper.insertMembership(em, viewerId, ScopeType.TEAM, teamId, RoleKind.SUPPORTER);
        List<Long> feeds = seedFeeds(AnnouncementScopeType.TEAM, teamId);
        staleCachedRole("TEAM", teamId, ViewerRole.SUPPORTER);
        setRoleKind(ScopeType.TEAM, teamId, RoleKind.MEMBER);

        assertList("/api/v1/teams/" + teamId + "/announcements", feeds, 3);
        assertThat(body("/api/v1/dashboard/team/" + teamSlug, 200)).contains(MEMBERS_TITLE);
    }

    // ───────── 組織側（直属） ─────────

    @Test
    @DisplayName("AC-ALIC1-E04 組織 MEMBER→SUPPORTER 降格直後（キャッシュに MEMBER が残る）は組織一覧・未読件数・組織ダッシュボードから MEMBERS 項目が消える")
    void e04_組織降格は即時に効く() throws Exception {
        MembershipTestHelper.insertMembership(em, viewerId, ScopeType.ORGANIZATION, orgId, RoleKind.MEMBER);
        List<Long> feeds = seedFeeds(AnnouncementScopeType.ORGANIZATION, orgId);
        staleCachedRole("ORGANIZATION", orgId, ViewerRole.MEMBER);
        setRoleKind(ScopeType.ORGANIZATION, orgId, RoleKind.SUPPORTER);

        assertList("/api/v1/organizations/" + orgId + "/announcements", List.of(feeds.get(0), feeds.get(1)), 2);
        String dashboard = body("/api/v1/dashboard/organization/" + orgSlug, 200);
        assertThat(dashboard).as("前提: 組織ダッシュボードに公開項目が出ている").contains(PUBLIC_TITLE);
        assertThat(dashboard).doesNotContain(MEMBERS_TITLE).doesNotContain(MEMBERS_EXCERPT);
    }

    @Test
    @DisplayName("AC-ALIC1-E05 同条件で組織離脱直後は組織ダッシュボード 403、組織一覧と未読件数は PUBLIC のみ（非メンバーと同じ）")
    void e05_組織離脱は即時に効く() throws Exception {
        MembershipTestHelper.insertMembership(em, viewerId, ScopeType.ORGANIZATION, orgId, RoleKind.MEMBER);
        List<Long> feeds = seedFeeds(AnnouncementScopeType.ORGANIZATION, orgId);
        staleCachedRole("ORGANIZATION", orgId, ViewerRole.MEMBER);
        leave(ScopeType.ORGANIZATION, orgId);

        body("/api/v1/dashboard/organization/" + orgSlug, 403);
        assertList("/api/v1/organizations/" + orgId + "/announcements", List.of(feeds.get(0)), 1);
    }

    @Test
    @DisplayName("AC-ALIC1-E06 直属の SUPPORTER→MEMBER 昇格が組織一覧・組織ダッシュボードにすぐ反映される")
    void e06_組織昇格は即時に効く() throws Exception {
        MembershipTestHelper.insertMembership(em, viewerId, ScopeType.ORGANIZATION, orgId, RoleKind.SUPPORTER);
        List<Long> feeds = seedFeeds(AnnouncementScopeType.ORGANIZATION, orgId);
        staleCachedRole("ORGANIZATION", orgId, ViewerRole.SUPPORTER);
        setRoleKind(ScopeType.ORGANIZATION, orgId, RoleKind.MEMBER);

        assertList("/api/v1/organizations/" + orgId + "/announcements", feeds, 3);
        assertThat(body("/api/v1/dashboard/organization/" + orgSlug, 200)).contains(MEMBERS_TITLE);
    }

    // ───────── helpers ─────────

    /** 「キャッシュに古い役割が残り、無効化に失敗した」状態。@Cacheable の入口だけが古い値を返す。 */
    private void staleCachedRole(String scopeType, Long scopeId, ViewerRole stale) {
        doReturn(stale).when(roleResolver).resolveViewerRole(viewerId, scopeType, scopeId);
    }

    /** PUBLIC・SUPPORTERS・MEMBERS の3件を作り、その順の ID を返す。 */
    private List<Long> seedFeeds(AnnouncementScopeType scope, Long scopeId) {
        Long pub = saveFeed(scope, scopeId, AnnouncementVisibility.PUBLIC, PUBLIC_TITLE, "公開抜粋");
        Long sup = saveFeed(scope, scopeId, AnnouncementVisibility.SUPPORTERS_AND_ABOVE, SUPPORTERS_TITLE, "応援者抜粋");
        Long mem = saveFeed(scope, scopeId, AnnouncementVisibility.MEMBERS_AND_ABOVE, MEMBERS_TITLE, MEMBERS_EXCERPT);
        em.flush();
        return List.of(pub, sup, mem);
    }

    private Long saveFeed(AnnouncementScopeType scope, Long scopeId, String visibility, String title, String excerpt) {
        AnnouncementFeedEntity feed = AnnouncementFeedEntity.builder().scopeType(scope).scopeId(scopeId)
                .sourceType(AnnouncementSourceType.BULLETIN_THREAD).sourceId(System.nanoTime() % 1_000_000_000L)
                .authorId(authorId).titleCache(title).excerptCache(excerpt).visibility(visibility).build();
        em.persist(feed);
        return feed.getId();
    }

    private void setRoleKind(ScopeType scopeType, Long scopeId, RoleKind kind) {
        em.flush();
        em.createNativeQuery("UPDATE memberships SET role_kind = :rk, updated_at = NOW() "
                        + "WHERE user_id = :uid AND scope_type = :st AND scope_id = :sid AND left_at IS NULL")
                .setParameter("rk", kind.name()).setParameter("uid", viewerId)
                .setParameter("st", scopeType.name()).setParameter("sid", scopeId).executeUpdate();
        em.clear();
    }

    private void leave(ScopeType scopeType, Long scopeId) {
        em.flush();
        em.createNativeQuery("UPDATE memberships SET left_at = NOW(), updated_at = NOW() "
                        + "WHERE user_id = :uid AND scope_type = :st AND scope_id = :sid AND left_at IS NULL")
                .setParameter("uid", viewerId).setParameter("st", scopeType.name())
                .setParameter("sid", scopeId).executeUpdate();
        em.clear();
    }

    private void assertList(String path, List<Long> expectedIds, long expectedUnread) throws Exception {
        String list = body(path, 200);
        assertThat(ids(list)).as("一覧 %s の告知", path).containsExactlyInAnyOrderElementsOf(expectedIds);
        assertThat(unread(list)).as("一覧 %s の未読件数", path).isEqualTo(expectedUnread);
        if (expectedIds.size() < 3) {
            assertThat(list).as("見えない MEMBERS 項目の要約が応答に残らない").doesNotContain(MEMBERS_EXCERPT);
        }
    }

    private String body(String path, int expectedStatus) throws Exception {
        em.flush();
        em.clear();
        MvcResult result = mockMvc.perform(get(path).servletPath(path).with(user(viewerId.toString())))
                .andExpect(status().is(expectedStatus)).andReturn();
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static List<Long> ids(String json) {
        List<Number> raw = JsonPath.read(json, "$.data[*].id");
        return raw.stream().map(Number::longValue).toList();
    }

    private static long unread(String json) {
        return ((Number) JsonPath.read(json, "$.meta.unreadCount")).longValue();
    }

    private Long saveUser(String name) {
        UserEntity entity = UserEntity.builder().email(name + "@fresh.example.com")
                .lastName("試練").firstName("鮮度").displayName(name).isSearchable(true)
                .locale("ja").timezone("Asia/Tokyo").status(UserEntity.UserStatus.ACTIVE).build();
        em.persist(entity);
        return entity.getId();
    }
}
