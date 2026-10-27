package com.mannschaft.app.inbox;

import com.mannschaft.app.common.visibility.perf.SqlIntentCounter;
import com.mannschaft.app.inbox.service.adapter.AnnouncementInboxAdapter;
import com.mannschaft.app.common.visibility.ScopeKey;
import com.mannschaft.app.dashboard.ViewerRole;
import com.mannschaft.app.dashboard.service.RoleResolver;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.social.announcement.AnnouncementFeedEntity;
import com.mannschaft.app.social.announcement.AnnouncementScopeType;
import com.mannschaft.app.social.announcement.AnnouncementSourceType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 実際のInbox adapterを通し、所属数に依存するrole照会の再発を検知する。
 * feedのスコープ毎取得は別budgetであり、role SQLへ混ぜない。
 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class AnnouncementInboxRoleBatchIT extends AbstractMySqlIntegrationTest {
    @Autowired private AnnouncementInboxAdapter adapter;
    @Autowired private RoleResolver roleResolver;
    @PersistenceContext private EntityManager em;

    @Test
    @DisplayName("所属1件から21件へ増えてもrole照会は一定、feedは各scopeで従来どおり取得する")
    void roleQueriesStayConstantAcrossScopes() {
        long oneUser = insertUser();
        long manyUser = insertUser();
        addTeams(oneUser, 1);
        addTeams(manyUser, 21);
        List<String> one = fetchSql(oneUser);
        List<String> many = fetchSql(manyUser);

        assertThat(feedQueries(one)).isEqualTo(1);
        assertThat(feedQueries(many)).isEqualTo(21);
        assertThat(roleQueries(one)).isPositive();
        assertThat(roleQueries(many))
                .as("role SQL budget: 1scope=%s / 21scope=%s (feedを除外)",
                        roleQueries(one), roleQueries(many))
                .isEqualTo(roleQueries(one));
    }

    @ParameterizedTest
    @CsvSource({
            "TEAM,-,MEMBER,MEMBER", "TEAM,-,SUPPORTER,SUPPORTER",
            "TEAM,ADMIN,MEMBER,ADMIN", "TEAM,DEPUTY_ADMIN,SUPPORTER,DEPUTY_ADMIN",
            "TEAM,GUEST,-,PUBLIC", "TEAM,-,-,PUBLIC",
            "ORGANIZATION,-,MEMBER,MEMBER", "ORGANIZATION,-,SUPPORTER,SUPPORTER",
            "ORGANIZATION,ADMIN,MEMBER,ADMIN", "ORGANIZATION,GUEST,-,PUBLIC"})
    void batchMatchesSingleResolverForLegalRoles(String type, String permission,
                                                String membership, ViewerRole expected) {
        long user = insertUser();
        long scope = insertScope(type);
        if (!"-".equals(permission)) {
            MembershipTestHelper.insertUserRole(em, user, permission,
                    "TEAM".equals(type) ? scope : null, "ORGANIZATION".equals(type) ? scope : null);
        }
        if (!"-".equals(membership)) {
            MembershipTestHelper.insertMembership(em, user, ScopeType.valueOf(type), scope,
                    RoleKind.valueOf(membership));
        }
        em.flush();
        em.clear();
        assertThat(roleResolver.resolveViewerRole(user, type, scope)).isEqualTo(expected);
        assertThat(roleResolver.resolveViewerRolesFresh(user, Set.of(new ScopeKey(type, scope))))
                .containsEntry(new ScopeKey(type, scope), expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"MEMBERSHIP", "PERMISSION"})
    void roleDowngradeHidesTitleAndExcerptDespiteDashboardCache(String source) {
        long user = insertUser();
        long team = insertScope("TEAM");
        ViewerRole before;
        if ("MEMBERSHIP".equals(source)) {
            MembershipTestHelper.insertMembership(em, user, ScopeType.TEAM, team, RoleKind.MEMBER);
            before = ViewerRole.MEMBER;
        } else {
            MembershipTestHelper.insertUserRole(em, user, "ADMIN", team, null);
            before = ViewerRole.ADMIN;
        }
        var feed = AnnouncementFeedEntity.builder().scopeType(AnnouncementScopeType.TEAM).scopeId(team)
                .sourceType(AnnouncementSourceType.BLOG_POST).sourceId(1L).authorId(user)
                .titleCache("内輪タイトル").excerptCache("内輪本文").visibility("MEMBERS_AND_ABOVE").build();
        em.persist(feed);
        em.flush();
        em.clear();
        assertThat(roleResolver.resolveViewerRole(user, "TEAM", team)).isEqualTo(before);
        assertThat(adapter.fetch(user, 500)).singleElement().satisfies(item -> {
            assertThat(item.title()).isEqualTo("内輪タイトル");
            assertThat(item.excerpt()).isEqualTo("内輪本文");
        });

        if ("MEMBERSHIP".equals(source)) {
            // SUPPORTERの正規masterも本番と同じpriorityで用意し、既存MEMBER所属を降格する。
            long seed = insertScope("TEAM");
            MembershipTestHelper.insertMembership(em, user, ScopeType.TEAM, seed, RoleKind.SUPPORTER);
            em.createNativeQuery("UPDATE memberships SET role_kind='SUPPORTER' WHERE user_id=:user AND scope_id=:scope")
                    .setParameter("user", user).setParameter("scope", team).executeUpdate();
        } else {
            em.createNativeQuery("DELETE FROM user_roles WHERE user_id=:user AND team_id=:team")
                    .setParameter("user", user).setParameter("team", team).executeUpdate();
            MembershipTestHelper.insertUserRole(em, user, "GUEST", team, null);
        }
        em.flush();
        em.clear();
        assertThat(roleResolver.resolveViewerRole(user, "TEAM", team)).isEqualTo(before);
        assertThat(adapter.fetch(user, 500)).isEmpty();
        assertThat(adapter.isVisibleTo(user, feed.getId())).isFalse();
    }

    @Test
    void inactiveUserIsPublicWithoutInboxScopes() {
        long user = insertUser();
        long team = addTeams(user, 1).getFirst();
        em.createNativeQuery("UPDATE users SET deleted_at=NOW() WHERE id=:user")
                .setParameter("user", user).executeUpdate();
        em.flush();
        em.clear();
        assertThat(roleResolver.resolveViewerRolesFresh(user, Set.of(new ScopeKey("TEAM", team))))
                .containsEntry(new ScopeKey("TEAM", team), ViewerRole.PUBLIC);
        assertThat(adapter.fetch(user, 500)).isEmpty();
    }

    @Test
    void nonMemberCannotReadEvenPublicFeedInAnotherScope() {
        long owner = insertUser();
        long outsider = insertUser();
        long team = addTeams(owner, 1).getFirst();
        var feed = AnnouncementFeedEntity.builder().scopeType(AnnouncementScopeType.TEAM).scopeId(team)
                .sourceType(AnnouncementSourceType.BLOG_POST).sourceId(1L).authorId(owner)
                .titleCache("別スコープタイトル").excerptCache("別スコープ本文").visibility("PUBLIC").build();
        em.persist(feed);
        em.flush();
        em.clear();
        assertThat(adapter.fetch(outsider, 500)).isEmpty();
        assertThat(adapter.isVisibleTo(outsider, feed.getId())).isFalse();
    }

    @Test
    void parentOrganizationAdminDoesNotOverrideDirectTeamSupporter() {
        long user = insertUser();
        long team = insertScope("TEAM");
        long org = insertScope("ORGANIZATION");
        MembershipTestHelper.insertMembership(em, user, ScopeType.TEAM, team, RoleKind.SUPPORTER);
        MembershipTestHelper.insertUserRole(em, user, "ADMIN", null, org);
        em.createNativeQuery("""
                INSERT INTO team_org_memberships (team_id, organization_id, status, invited_at, created_at)
                VALUES (:team, :org, 'ACTIVE', NOW(), NOW())
                """).setParameter("team", team).setParameter("org", org).executeUpdate();
        em.flush();
        em.clear();
        assertThat(roleResolver.resolveViewerRole(user, "TEAM", team)).isEqualTo(ViewerRole.SUPPORTER);
        assertThat(roleResolver.resolveViewerRolesFresh(user, Set.of(new ScopeKey("TEAM", team))))
                .containsEntry(new ScopeKey("TEAM", team), ViewerRole.SUPPORTER);
    }

    @Test
    void systemAdminOverridesMissingScopeRole() {
        long user = insertUser();
        long team = insertScope("TEAM");
        MembershipTestHelper.insertUserRole(em, user, "SYSTEM_ADMIN", null, null);
        em.flush();
        em.clear();
        assertThat(roleResolver.resolveViewerRolesFresh(user, Set.of(new ScopeKey("TEAM", team))))
                .containsEntry(new ScopeKey("TEAM", team), ViewerRole.SYSTEM_ADMIN);
    }

    @Test
    void emptyBatchAndZeroWindowIssueNoSql() {
        long user = insertUser();
        em.flush();
        em.clear();
        try (var capture = SqlIntentCounter.captureCurrentThread()) {
            assertThat(roleResolver.resolveViewerRolesFresh(user, Set.of())).isEmpty();
            assertThat(adapter.fetch(user, 0)).isEmpty();
            assertThat(capture.capturedSqls()).isEmpty();
        }
    }

    private List<String> fetchSql(long userId) {
        em.flush();
        em.clear();
        try (var capture = SqlIntentCounter.captureCurrentThread()) {
            assertThat(adapter.fetch(userId, 500)).isEmpty();
            return capture.capturedSqls();
        }
    }

    private long feedQueries(List<String> statements) {
        return statements.stream().map(sql -> sql.toLowerCase(Locale.ROOT))
                .filter(sql -> sql.contains("from announcement_feeds ")).count();
    }

    private long roleQueries(List<String> statements) {
        return statements.stream().map(sql -> sql.toLowerCase(Locale.ROOT))
                .filter(sql -> sql.matches("(?s).*\\b(from|join) (users|user_roles|memberships|roles)\\b.*"))
                .count();
    }

    private long insertUser() {
        String email = "inbox-batch-" + UUID.randomUUID() + "@example.com";
        em.createNativeQuery("""
                INSERT INTO users (email, last_name, first_name, display_name, status,
                    is_searchable, handle_searchable, contact_approval_required,
                    online_visibility, dm_receive_from, encryption_key_version,
                    locale, timezone, reporting_restricted, follow_list_visibility,
                    care_notification_enabled, offline_only, created_at, updated_at)
                VALUES (:email, 'BATCH', '試験', 'BATCH試験', 'ACTIVE', 1, 1, 1,
                    'NOBODY', 'ANYONE', 1, 'ja', 'Asia/Tokyo', 0, 'PUBLIC', 1, 0, NOW(), NOW())
                """).setParameter("email", email).executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email=:email")
                .setParameter("email", email).getSingleResult()).longValue();
    }

    private List<Long> addTeams(long userId, int count) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long teamId = insertScope("TEAM");
            MembershipTestHelper.insertMembership(em, userId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            ids.add(teamId);
        }
        return ids;
    }

    private long insertScope(String type) {
        String slug = "ib-" + UUID.randomUUID().toString().substring(0, 16);
        if ("TEAM".equals(type)) {
            em.createNativeQuery("""
                    INSERT INTO teams (name, visibility, supporter_enabled, version, member_count,
                        slug, created_at, updated_at)
                    VALUES ('BATCH試験', 'PUBLIC', 1, 0, 0, :slug, NOW(), NOW())
                    """).setParameter("slug", slug).executeUpdate();
        } else {
            em.createNativeQuery("""
                    INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility,
                        supporter_enabled, version, slug, created_at, updated_at)
                    VALUES ('BATCH試験', 'OTHER', 'PUBLIC', 'NONE', 1, 0, :slug, NOW(), NOW())
                    """).setParameter("slug", slug).executeUpdate();
        }
        String table = "TEAM".equals(type) ? "teams" : "organizations";
        return ((Number) em.createNativeQuery("SELECT id FROM " + table + " WHERE slug=:slug")
                .setParameter("slug", slug).getSingleResult()).longValue();
    }
}
