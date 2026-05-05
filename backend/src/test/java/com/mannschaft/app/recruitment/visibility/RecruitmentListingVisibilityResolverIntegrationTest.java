package com.mannschaft.app.recruitment.visibility;

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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F00 Phase C — {@link RecruitmentListingVisibilityResolver} 結合テスト。
 *
 * <p>実 MySQL（Testcontainers）に対し最小限の seed を投入し、Resolver と
 * {@link ContentVisibilityChecker} を組み立てた上で
 * status × visibility × メンバーシップ の各 case を E2E に検証する。
 *
 * <p>セットアップは {@code EventVisibilityResolverIntegrationTest} の方式を踏襲。
 * すなわち {@code @Transactional} ロールバック方式 + {@code em.createNativeQuery}
 * で users / organizations / teams / team_org_memberships / roles / user_roles /
 * recruitment_listings を直接 INSERT する。
 *
 * <p>recruitment_categories は Flyway 初期データで {@code id=1} (futsal_open) が
 * 既に投入されているのでそれを利用する。
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Transactional
@DisplayName("RecruitmentListingVisibilityResolver 結合テスト")
class RecruitmentListingVisibilityResolverIntegrationTest {

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

        memberUserId    = insertUser("rl.member@example.com", "山田", "太郎");
        nonMemberUserId = insertUser("rl.nonmember@example.com", "鈴木", "花子");
        sysAdminUserId  = insertUser("rl.sysadmin@example.com", "管理", "者");

        orgId  = insertOrganization("RL結合 組織");
        teamId = insertTeam("RL結合 チーム");
        insertTeamOrgMembership(teamId, orgId);

        insertUserRole(memberUserId, memberRoleId, teamId, null);
        insertUserRole(sysAdminUserId, systemAdminRoleId, null, null);

        // recruitment_categories は Flyway 初期データで投入済み。futsal_open を利用。
        categoryId = ((Number) em.createNativeQuery(
                "SELECT id FROM recruitment_categories WHERE code = 'futsal_open'")
                .getSingleResult()).longValue();

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
     * recruitment_listings テーブルへ最小限の募集枠行を直接 INSERT する。
     *
     * <p>NOT NULL 列・CHECK 制約を満たすように全列を明示する。
     * deadline / auto_cancel_at は start_at より前、auto_cancel_at は deadline 以下に設定。
     *
     * @return 生成された listing_id
     */
    private Long insertRecruitment(String title, Long createdBy, String status, String visibility) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime startAt = now.plusDays(7);
        LocalDateTime endAt = startAt.plusHours(2);
        LocalDateTime deadline = startAt.minusDays(1);
        LocalDateTime autoCancel = deadline.minusHours(1);

        em.createNativeQuery(
                "INSERT INTO recruitment_listings ("
                        + "scope_type, scope_id, category_id, title, "
                        + "participation_type, "
                        + "start_at, end_at, application_deadline, auto_cancel_at, "
                        + "capacity, min_capacity, "
                        + "confirmed_count, waitlist_count, waitlist_max, "
                        + "payment_enabled, "
                        + "visibility, status, "
                        + "created_by, "
                        + "participant_count_cache, next_waitlist_position, "
                        + "created_at, updated_at) "
                        + "VALUES ('TEAM', :scopeId, :categoryId, :title, "
                        + "'INDIVIDUAL', "
                        + ":startAt, :endAt, :deadline, :autoCancel, "
                        + "10, 1, "
                        + "0, 0, 100, "
                        + "0, "
                        + ":visibility, :status, "
                        + ":createdBy, "
                        + "0, 1, "
                        + "NOW(), NOW())")
                .setParameter("scopeId", teamId)
                .setParameter("categoryId", categoryId)
                .setParameter("title", title)
                .setParameter("startAt", startAt)
                .setParameter("endAt", endAt)
                .setParameter("deadline", deadline)
                .setParameter("autoCancel", autoCancel)
                .setParameter("visibility", visibility)
                .setParameter("status", status)
                .setParameter("createdBy", createdBy)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM recruitment_listings WHERE title = :title")
                .setParameter("title", title)
                .getSingleResult()).longValue();
    }

    // =========================================================================
    // シナリオ
    // =========================================================================

    @Test
    @DisplayName("PUBLIC × OPEN は匿名・非メンバー・メンバーすべて閲覧可")
    void public_open_visible_to_all() {
        Long id = insertRecruitment("rl-public-open", memberUserId, "OPEN", "PUBLIC");
        em.flush();
        em.clear();

        assertThat(checker.canView(ReferenceType.RECRUITMENT_LISTING, id, null)).isTrue();
        assertThat(checker.canView(ReferenceType.RECRUITMENT_LISTING, id, nonMemberUserId)).isTrue();
        assertThat(checker.canView(ReferenceType.RECRUITMENT_LISTING, id, memberUserId)).isTrue();
    }

    @Test
    @DisplayName("SCOPE_ONLY × OPEN は所属メンバーのみ閲覧可")
    void scope_only_open_visible_to_member_only() {
        Long id = insertRecruitment("rl-scope-open", memberUserId, "OPEN", "SCOPE_ONLY");
        em.flush();
        em.clear();

        assertThat(checker.canView(ReferenceType.RECRUITMENT_LISTING, id, null)).isFalse();
        assertThat(checker.canView(ReferenceType.RECRUITMENT_LISTING, id, nonMemberUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.RECRUITMENT_LISTING, id, memberUserId)).isTrue();
    }

    @Test
    @DisplayName("DRAFT は作成者本人および SystemAdmin のみ閲覧可")
    void draft_visible_to_author_or_sysadmin_only() {
        Long id = insertRecruitment("rl-draft", memberUserId, "DRAFT", "PUBLIC");
        em.flush();
        em.clear();

        assertThat(checker.canView(ReferenceType.RECRUITMENT_LISTING, id, null)).isFalse();
        assertThat(checker.canView(ReferenceType.RECRUITMENT_LISTING, id, nonMemberUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.RECRUITMENT_LISTING, id, memberUserId)).isTrue();
        assertThat(checker.canView(ReferenceType.RECRUITMENT_LISTING, id, sysAdminUserId)).isTrue();
    }

    @Test
    @DisplayName("CANCELLED は SystemAdmin のみ閲覧可（ARCHIVED 扱い）")
    void cancelled_visible_to_sysadmin_only() {
        Long id = insertRecruitment("rl-cancelled", memberUserId, "CANCELLED", "PUBLIC");
        em.flush();
        em.clear();

        assertThat(checker.canView(ReferenceType.RECRUITMENT_LISTING, id, null)).isFalse();
        assertThat(checker.canView(ReferenceType.RECRUITMENT_LISTING, id, memberUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.RECRUITMENT_LISTING, id, nonMemberUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.RECRUITMENT_LISTING, id, sysAdminUserId)).isTrue();
    }

    @Test
    @DisplayName("AUTO_CANCELLED は SystemAdmin のみ閲覧可")
    void auto_cancelled_visible_to_sysadmin_only() {
        Long id = insertRecruitment("rl-auto-cancelled", memberUserId, "AUTO_CANCELLED", "PUBLIC");
        em.flush();
        em.clear();

        assertThat(checker.canView(ReferenceType.RECRUITMENT_LISTING, id, memberUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.RECRUITMENT_LISTING, id, sysAdminUserId)).isTrue();
    }

    @Test
    @DisplayName("不存在 ID は誰に対しても false")
    void unknown_id_false() {
        assertThat(checker.canView(ReferenceType.RECRUITMENT_LISTING, 999_999L, memberUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.RECRUITMENT_LISTING, 999_999L, sysAdminUserId)).isFalse();
    }

    @Test
    @DisplayName("filterAccessible は SCOPE_ONLY と PUBLIC を所属メンバー視点で正しくフィルタ")
    void filterAccessible_mixed_visibility_for_member() {
        Long id1 = insertRecruitment("rl-flt-1", memberUserId, "OPEN", "PUBLIC");
        Long id2 = insertRecruitment("rl-flt-2", memberUserId, "OPEN", "SCOPE_ONLY");
        Long id3 = insertRecruitment("rl-flt-3", memberUserId, "DRAFT", "PUBLIC");
        em.flush();
        em.clear();

        Set<Long> nonMember = checker.filterAccessible(
                ReferenceType.RECRUITMENT_LISTING, List.of(id1, id2, id3), nonMemberUserId);
        assertThat(nonMember).containsExactly(id1);

        Set<Long> member = checker.filterAccessible(
                ReferenceType.RECRUITMENT_LISTING, List.of(id1, id2, id3), memberUserId);
        // id3 (DRAFT) は author 自身なので可視
        assertThat(member).containsExactlyInAnyOrder(id1, id2, id3);

        Set<Long> sysAdmin = checker.filterAccessible(
                ReferenceType.RECRUITMENT_LISTING, List.of(id1, id2, id3), sysAdminUserId);
        assertThat(sysAdmin).containsExactlyInAnyOrder(id1, id2, id3);
    }
}
