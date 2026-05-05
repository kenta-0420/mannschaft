package com.mannschaft.app.bulletin.visibility;

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
 * F00 Phase C — {@link BulletinThreadVisibilityResolver} 結合テスト。
 *
 * <p>掲示板スレッドは visibility 概念無しの最小実装（§12.3.1）のため、
 * MEMBERS_ONLY 固定挙動と SystemAdmin 高速パス・PERSONAL fail-closed を
 * 実 MySQL で検証する。</p>
 *
 * <p>必ず {@link ContentVisibilityChecker} 経由で呼び出す（設計書 §15 D-16 / 殿の指示）。</p>
 *
 * <p>セットアップは {@code EventVisibilityResolverIntegrationTest} を踏襲。
 * users / organizations / teams / team_org_memberships / roles / user_roles /
 * bulletin_categories / bulletin_threads を直接 INSERT する。</p>
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Transactional
@DisplayName("BulletinThreadVisibilityResolver 結合テスト")
class BulletinThreadVisibilityResolverIntegrationTest {

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
    private Long categoryId;

    @BeforeEach
    void setUp() {
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

        memberUserId    = insertUser("bulletin.member@example.com", "山田", "太郎");
        nonMemberUserId = insertUser("bulletin.nonmember@example.com", "鈴木", "花子");
        sysAdminUserId  = insertUser("bulletin.sysadmin@example.com", "管理", "者");

        orgId  = insertOrganization("BUL結合 組織");
        teamId = insertTeam("BUL結合 チーム");
        insertTeamOrgMembership(teamId, orgId);

        insertUserRole(memberUserId, memberRoleId, teamId, null);
        insertUserRole(sysAdminUserId, systemAdminRoleId, null, null);

        categoryId = insertCategory("一般", "TEAM", teamId);

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

    private Long insertCategory(String name, String scopeType, Long scopeId) {
        em.createNativeQuery(
                "INSERT INTO bulletin_categories ("
                        + "scope_type, scope_id, name, display_order, post_min_role, "
                        + "created_at, updated_at) "
                        + "VALUES (:scopeType, :scopeId, :name, 0, 'MEMBER_PLUS', NOW(), NOW())")
                .setParameter("scopeType", scopeType)
                .setParameter("scopeId", scopeId)
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM bulletin_categories WHERE name = :name AND scope_type = :scopeType "
                        + "AND scope_id = :scopeId")
                .setParameter("name", name)
                .setParameter("scopeType", scopeType)
                .setParameter("scopeId", scopeId)
                .getSingleResult()).longValue();
    }

    /**
     * bulletin_threads テーブルへ最小限の thread 行を直接 INSERT する。
     *
     * <p>NOT NULL 全列を明示する（A-3b 知見）。Builder.Default 由来の値も
     * DB INSERT では明示的にセットしないと NOT NULL 違反になる。</p>
     */
    private Long insertThread(String title, Long authorId, String scopeType, Long scopeId) {
        em.createNativeQuery(
                "INSERT INTO bulletin_threads ("
                        + "category_id, scope_type, scope_id, author_id, title, body, "
                        + "priority, read_tracking_mode, "
                        + "is_pinned, is_locked, is_archived, "
                        + "reply_count, read_count, "
                        + "created_at, updated_at) "
                        + "VALUES (:cid, :scopeType, :scopeId, :authorId, :title, '本文', "
                        + "'INFO', 'COUNT_ONLY', "
                        + "0, 0, 0, "
                        + "0, 0, "
                        + "NOW(), NOW())")
                .setParameter("cid", categoryId)
                .setParameter("scopeType", scopeType)
                .setParameter("scopeId", scopeId)
                .setParameter("authorId", authorId)
                .setParameter("title", title)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM bulletin_threads WHERE title = :title")
                .setParameter("title", title)
                .getSingleResult()).longValue();
    }

    // =========================================================================
    // シナリオ
    // =========================================================================

    @Test
    @DisplayName("TEAM スコープのスレッドは所属メンバーのみ閲覧可（MEMBERS_ONLY 固定）")
    void team_thread_visible_to_member_only() {
        Long threadId = insertThread("bul-team-thread", memberUserId, "TEAM", teamId);
        em.flush();
        em.clear();

        assertThat(checker.canView(ReferenceType.BULLETIN_THREAD, threadId, null)).isFalse();
        assertThat(checker.canView(ReferenceType.BULLETIN_THREAD, threadId, nonMemberUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.BULLETIN_THREAD, threadId, memberUserId)).isTrue();
    }

    @Test
    @DisplayName("TEAM スコープのスレッドは SystemAdmin にも閲覧可（§15 D-13）")
    void team_thread_visible_to_system_admin() {
        Long threadId = insertThread("bul-team-thread-sysadmin", memberUserId, "TEAM", teamId);
        em.flush();
        em.clear();

        assertThat(checker.canView(ReferenceType.BULLETIN_THREAD, threadId, sysAdminUserId)).isTrue();
    }

    @Test
    @DisplayName("ORGANIZATION スコープのスレッドも MEMBERS_ONLY 固定で評価される")
    void organization_thread_visible_to_member_only() {
        // org 直属のメンバーシップを追加付与
        insertUserRole(memberUserId, memberRoleId, null, orgId);
        Long threadId = insertThread("bul-org-thread", memberUserId, "ORGANIZATION", orgId);
        em.flush();
        em.clear();

        assertThat(checker.canView(ReferenceType.BULLETIN_THREAD, threadId, null)).isFalse();
        assertThat(checker.canView(ReferenceType.BULLETIN_THREAD, threadId, nonMemberUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.BULLETIN_THREAD, threadId, memberUserId)).isTrue();
    }

    @Test
    @DisplayName("PERSONAL スコープは作成者本人にも fail-closed で不可視（最小実装）")
    void personal_scope_invisible_even_to_author() {
        Long threadId = insertThread("bul-personal-thread", memberUserId, "PERSONAL", memberUserId);
        em.flush();
        em.clear();

        // PERSONAL は MembershipBatchQueryService が処理対象としないため
        // MEMBERS_ONLY 評価で fail-closed。
        assertThat(checker.canView(ReferenceType.BULLETIN_THREAD, threadId, memberUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.BULLETIN_THREAD, threadId, nonMemberUserId)).isFalse();
    }

    @Test
    @DisplayName("PERSONAL スコープでも SystemAdmin には可視（§15 D-13）")
    void personal_scope_visible_to_system_admin() {
        Long threadId = insertThread("bul-personal-thread-sysadmin", memberUserId, "PERSONAL", memberUserId);
        em.flush();
        em.clear();

        assertThat(checker.canView(ReferenceType.BULLETIN_THREAD, threadId, sysAdminUserId)).isTrue();
    }

    @Test
    @DisplayName("不存在 ID は誰に対しても false")
    void unknown_id_false() {
        assertThat(checker.canView(ReferenceType.BULLETIN_THREAD, 999_999L, memberUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.BULLETIN_THREAD, 999_999L, sysAdminUserId)).isFalse();
    }

    @Test
    @DisplayName("filterAccessible は所属メンバー視点で正しくフィルタ")
    void filterAccessible_mixed_for_member() {
        Long t1 = insertThread("bul-flt-1", memberUserId, "TEAM", teamId);
        Long t2 = insertThread("bul-flt-2", memberUserId, "TEAM", teamId);
        Long t3 = insertThread("bul-flt-3", memberUserId, "PERSONAL", memberUserId);
        em.flush();
        em.clear();

        Set<Long> nonMember = checker.filterAccessible(
                ReferenceType.BULLETIN_THREAD, List.of(t1, t2, t3), nonMemberUserId);
        assertThat(nonMember).isEmpty();

        Set<Long> member = checker.filterAccessible(
                ReferenceType.BULLETIN_THREAD, List.of(t1, t2, t3), memberUserId);
        assertThat(member).containsExactlyInAnyOrder(t1, t2); // t3 は PERSONAL → fail-closed

        Set<Long> sysAdmin = checker.filterAccessible(
                ReferenceType.BULLETIN_THREAD, List.of(t1, t2, t3), sysAdminUserId);
        assertThat(sysAdmin).containsExactlyInAnyOrder(t1, t2, t3);
    }

    @Test
    @DisplayName("論理削除済 (deleted_at != NULL) は誰にも不可視")
    void soft_deleted_invisible_to_all() {
        Long threadId = insertThread("bul-deleted", memberUserId, "TEAM", teamId);
        em.createNativeQuery("UPDATE bulletin_threads SET deleted_at = NOW() WHERE id = :id")
                .setParameter("id", threadId)
                .executeUpdate();
        em.flush();
        em.clear();

        assertThat(checker.canView(ReferenceType.BULLETIN_THREAD, threadId, memberUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.BULLETIN_THREAD, threadId, sysAdminUserId)).isFalse();
    }
}
