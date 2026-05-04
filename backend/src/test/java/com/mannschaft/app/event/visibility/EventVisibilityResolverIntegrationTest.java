package com.mannschaft.app.event.visibility;

import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F00 Phase B — {@link EventVisibilityResolver} 結合テスト。
 *
 * <p>実 MySQL（Testcontainers）に対し最小限の seed を投入し、
 * Resolver と {@link ContentVisibilityChecker} を組み立てた上で
 * status × visibility × メンバーシップ の各 case を E2E に検証する。</p>
 *
 * <p>セットアップは {@code MembershipBatchQueryServiceIntegrationTest} の方式を踏襲。
 * すなわち {@code @Transactional} ロールバック方式 + {@code em.createNativeQuery}
 * で users / organizations / teams / team_org_memberships / roles / user_roles / events
 * を直接 INSERT する。これにより EventEntity / events DDL が
 * {@code ddl-auto=create-drop} で生成されたものに依存する形になる。</p>
 *
 * <p>A-3b 知見: events テーブルの NOT NULL 列をすべて明示的に INSERT する
 * （Builder.Default で設定される列も DB INSERT では含めるとよい）。</p>
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Transactional
@DisplayName("EventVisibilityResolver 結合テスト")
class EventVisibilityResolverIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    // OOM 対策（既存 Repository テストパターン踏襲）
    @MockitoBean
    private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    @Autowired
    private ContentVisibilityChecker checker;

    @PersistenceContext
    private EntityManager em;

    private Long memberRoleId;
    private Long systemAdminRoleId;
    private Long memberUserId;
    private Long nonMemberUserId;
    private Long sysAdminUserId;
    private Long teamId;
    private Long orgId;

    @BeforeEach
    void setUp() {
        // 1. ロールを直接投入
        em.createNativeQuery(
                "INSERT INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                        + "VALUES ('SYSTEM_ADMIN', 'システム管理者', 1, 1, NOW(), NOW())")
                .executeUpdate();
        em.createNativeQuery(
                "INSERT INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                        + "VALUES ('MEMBER', 'メンバー', 4, 0, NOW(), NOW())")
                .executeUpdate();
        em.flush();

        memberRoleId = ((Number) em.createNativeQuery(
                "SELECT id FROM roles WHERE name = 'MEMBER'").getSingleResult()).longValue();
        systemAdminRoleId = ((Number) em.createNativeQuery(
                "SELECT id FROM roles WHERE name = 'SYSTEM_ADMIN'").getSingleResult()).longValue();

        memberUserId    = insertUser("event.member@example.com", "山田", "太郎");
        nonMemberUserId = insertUser("event.nonmember@example.com", "鈴木", "花子");
        sysAdminUserId  = insertUser("event.sysadmin@example.com", "管理", "者");

        orgId  = insertOrganization("EV結合 組織");
        teamId = insertTeam("EV結合 チーム");
        insertTeamOrgMembership(teamId, orgId);

        insertUserRole(memberUserId, memberRoleId, teamId, null);
        insertUserRole(sysAdminUserId, systemAdminRoleId, null, null);

        em.flush();
        em.clear();
    }

    private Long insertUser(String email, String lastName, String firstName) {
        em.createNativeQuery(
                "INSERT INTO users ("
                        + "email, last_name, first_name, display_name, status, "
                        + "is_searchable, handle_searchable, contact_approval_required, "
                        + "online_visibility, dm_receive_from, encryption_key_version, "
                        + "locale, timezone, reporting_restricted, follow_list_visibility, "
                        + "care_notification_enabled, offline_only, "
                        + "created_at, updated_at) "
                        + "VALUES (:email, :ln, :fn, :dn, 'ACTIVE', "
                        + "1, 1, 1, "
                        + "'NOBODY', 'ANYONE', 1, "
                        + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                        + "1, 0, "
                        + "NOW(), NOW())")
                .setParameter("email", email)
                .setParameter("ln", lastName)
                .setParameter("fn", firstName)
                .setParameter("dn", lastName + " " + firstName)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM users WHERE email = :email")
                .setParameter("email", email)
                .getSingleResult()).longValue();
    }

    private Long insertOrganization(String name) {
        em.createNativeQuery(
                "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                        + "supporter_enabled, version, created_at, updated_at) "
                        + "VALUES (:name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM organizations WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    private Long insertTeam(String name) {
        em.createNativeQuery(
                "INSERT INTO teams (name, visibility, supporter_enabled, version, created_at, updated_at) "
                        + "VALUES (:name, 'PUBLIC', 1, 0, NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    private void insertTeamOrgMembership(Long teamId, Long orgId) {
        em.createNativeQuery(
                "INSERT INTO team_org_memberships (team_id, organization_id, status, invited_at, created_at) "
                        + "VALUES (:tid, :oid, 'ACTIVE', NOW(), NOW())")
                .setParameter("tid", teamId)
                .setParameter("oid", orgId)
                .executeUpdate();
    }

    private void insertUserRole(Long uid, Long roleId, Long teamIdParam, Long orgIdParam) {
        em.createNativeQuery(
                "INSERT INTO user_roles (user_id, role_id, team_id, organization_id, created_at, updated_at) "
                        + "VALUES (:uid, :rid, :tid, :oid, NOW(), NOW())")
                .setParameter("uid", uid)
                .setParameter("rid", roleId)
                .setParameter("tid", teamIdParam)
                .setParameter("oid", orgIdParam)
                .executeUpdate();
    }

    /**
     * events テーブルへ最小限の event 行を直接 INSERT する。
     *
     * <p>NOT NULL 全列を明示する（A-3b 知見）。Builder.Default 由来の値も
     * DB INSERT では明示的にセットしないと NOT NULL 違反になる。</p>
     *
     * @return 生成された event_id
     */
    private Long insertEvent(String slug, Long createdBy, String status, String visibility) {
        em.createNativeQuery(
                "INSERT INTO events ("
                        + "scope_type, scope_id, slug, status, visibility, "
                        + "is_approval_required, attendance_mode, "
                        + "registration_count, checkin_count, "
                        + "organizer_reminder_sent_count, "
                        + "version, created_by, created_at, updated_at) "
                        + "VALUES ('TEAM', :scopeId, :slug, :status, :visibility, "
                        + "0, 'REGISTRATION', "
                        + "0, 0, "
                        + "0, "
                        + "0, :createdBy, NOW(), NOW())")
                .setParameter("scopeId", teamId)
                .setParameter("slug", slug)
                .setParameter("status", status)
                .setParameter("visibility", visibility)
                .setParameter("createdBy", createdBy)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM events WHERE slug = :slug")
                .setParameter("slug", slug)
                .getSingleResult()).longValue();
    }

    // =========================================================================
    // シナリオ
    // =========================================================================

    @Test
    @DisplayName("PUBLIC × PUBLISHED は匿名・非メンバー・メンバーすべて閲覧可")
    void public_published_visible_to_all() {
        Long eventId = insertEvent("ev-public-pub", memberUserId, "PUBLISHED", "PUBLIC");
        em.flush();
        em.clear();

        assertThat(checker.canView(ReferenceType.EVENT, eventId, null)).isTrue();
        assertThat(checker.canView(ReferenceType.EVENT, eventId, nonMemberUserId)).isTrue();
        assertThat(checker.canView(ReferenceType.EVENT, eventId, memberUserId)).isTrue();
    }

    @Test
    @DisplayName("MEMBERS_ONLY × PUBLISHED は所属メンバーのみ閲覧可")
    void members_only_published_visible_to_member_only() {
        Long eventId = insertEvent("ev-members-pub", memberUserId, "PUBLISHED", "MEMBERS_ONLY");
        em.flush();
        em.clear();

        assertThat(checker.canView(ReferenceType.EVENT, eventId, null)).isFalse();
        assertThat(checker.canView(ReferenceType.EVENT, eventId, nonMemberUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.EVENT, eventId, memberUserId)).isTrue();
    }

    @Test
    @DisplayName("DRAFT は作成者本人および SystemAdmin のみ閲覧可")
    void draft_visible_to_author_or_sysadmin_only() {
        Long eventId = insertEvent("ev-draft", memberUserId, "DRAFT", "PUBLIC");
        em.flush();
        em.clear();

        assertThat(checker.canView(ReferenceType.EVENT, eventId, null)).isFalse();
        assertThat(checker.canView(ReferenceType.EVENT, eventId, nonMemberUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.EVENT, eventId, memberUserId)).isTrue();
        assertThat(checker.canView(ReferenceType.EVENT, eventId, sysAdminUserId)).isTrue();
    }

    @Test
    @DisplayName("CANCELLED は SystemAdmin のみ閲覧可（ARCHIVED 扱い）")
    void cancelled_visible_to_sysadmin_only() {
        Long eventId = insertEvent("ev-cancelled", memberUserId, "CANCELLED", "PUBLIC");
        em.flush();
        em.clear();

        assertThat(checker.canView(ReferenceType.EVENT, eventId, null)).isFalse();
        assertThat(checker.canView(ReferenceType.EVENT, eventId, memberUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.EVENT, eventId, nonMemberUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.EVENT, eventId, sysAdminUserId)).isTrue();
    }

    @Test
    @DisplayName("不存在 ID は誰に対しても false")
    void unknown_id_false() {
        assertThat(checker.canView(ReferenceType.EVENT, 999_999L, memberUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.EVENT, 999_999L, sysAdminUserId)).isFalse();
    }

    @Test
    @DisplayName("filterAccessible は MEMBERS_ONLY と PUBLIC を所属メンバー視点で正しくフィルタ")
    void filterAccessible_mixed_visibility_for_member() {
        Long ev1 = insertEvent("ev-flt-1", memberUserId, "PUBLISHED", "PUBLIC");
        Long ev2 = insertEvent("ev-flt-2", memberUserId, "PUBLISHED", "MEMBERS_ONLY");
        Long ev3 = insertEvent("ev-flt-3", memberUserId, "DRAFT", "PUBLIC");
        em.flush();
        em.clear();

        Set<Long> nonMember = checker.filterAccessible(
                ReferenceType.EVENT, List.of(ev1, ev2, ev3), nonMemberUserId);
        assertThat(nonMember).containsExactly(ev1);

        Set<Long> member = checker.filterAccessible(
                ReferenceType.EVENT, List.of(ev1, ev2, ev3), memberUserId);
        assertThat(member).containsExactlyInAnyOrder(ev1, ev2, ev3); // ev3 は author 自身

        Set<Long> sysAdmin = checker.filterAccessible(
                ReferenceType.EVENT, List.of(ev1, ev2, ev3), sysAdminUserId);
        assertThat(sysAdmin).containsExactlyInAnyOrder(ev1, ev2, ev3);
    }
}
