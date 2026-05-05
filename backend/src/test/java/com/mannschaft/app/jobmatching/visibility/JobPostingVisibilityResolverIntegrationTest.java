package com.mannschaft.app.jobmatching.visibility;

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
 * F00 Phase C — {@link JobPostingVisibilityResolver} 結合テスト。
 *
 * <p>実 MySQL（Testcontainers）に対し最小限の seed を投入し、
 * Resolver と {@link ContentVisibilityChecker} を組み立てた上で
 * status × visibility × メンバーシップ の各 case を E2E に検証する。</p>
 *
 * <p>セットアップは {@code EventVisibilityResolverIntegrationTest} の方式を踏襲。
 * すなわち {@code @Transactional} ロールバック方式 + {@code em.createNativeQuery}
 * で users / organizations / teams / team_org_memberships / roles / user_roles / job_postings
 * を直接 INSERT する。</p>
 *
 * <p>本テストでは特に {@link com.mannschaft.app.jobmatching.enums.VisibilityScope#JOBBER_INTERNAL}
 * の CUSTOM 分岐が JOBBER ロールでのみ可視になることを実 DB 経由で確認する。
 * 同シナリオで MEMBER ロールが拒否されることも検証する。</p>
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Transactional
@DisplayName("JobPostingVisibilityResolver 結合テスト")
class JobPostingVisibilityResolverIntegrationTest {

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
    private Long supporterRoleId;
    private Long jobberRoleId;
    private Long systemAdminRoleId;
    private Long memberUserId;
    private Long supporterUserId;
    private Long jobberUserId;
    private Long nonMemberUserId;
    private Long sysAdminUserId;
    private Long teamId;
    private Long orgId;

    @BeforeEach
    void setUp() {
        // 1. ロールを直接投入。JOBBER は F13.1 §2.9 の並行ロール（priority マップ非搭載）。
        em.createNativeQuery(
                "INSERT INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                        + "VALUES ('SYSTEM_ADMIN', 'システム管理者', 1, 1, NOW(), NOW())")
                .executeUpdate();
        em.createNativeQuery(
                "INSERT INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                        + "VALUES ('MEMBER', 'メンバー', 4, 0, NOW(), NOW())")
                .executeUpdate();
        em.createNativeQuery(
                "INSERT INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                        + "VALUES ('SUPPORTER', 'サポーター', 5, 0, NOW(), NOW())")
                .executeUpdate();
        em.createNativeQuery(
                "INSERT INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                        + "VALUES ('JOBBER', '助っ人 (有償)', 7, 0, NOW(), NOW())")
                .executeUpdate();
        em.flush();

        memberRoleId = ((Number) em.createNativeQuery(
                "SELECT id FROM roles WHERE name = 'MEMBER'").getSingleResult()).longValue();
        supporterRoleId = ((Number) em.createNativeQuery(
                "SELECT id FROM roles WHERE name = 'SUPPORTER'").getSingleResult()).longValue();
        jobberRoleId = ((Number) em.createNativeQuery(
                "SELECT id FROM roles WHERE name = 'JOBBER'").getSingleResult()).longValue();
        systemAdminRoleId = ((Number) em.createNativeQuery(
                "SELECT id FROM roles WHERE name = 'SYSTEM_ADMIN'").getSingleResult()).longValue();

        memberUserId    = insertUser("jp.member@example.com", "山田", "太郎");
        supporterUserId = insertUser("jp.supporter@example.com", "佐藤", "次郎");
        jobberUserId    = insertUser("jp.jobber@example.com", "田中", "三郎");
        nonMemberUserId = insertUser("jp.nonmember@example.com", "鈴木", "花子");
        sysAdminUserId  = insertUser("jp.sysadmin@example.com", "管理", "者");

        orgId  = insertOrganization("JP結合 組織");
        teamId = insertTeam("JP結合 チーム");
        insertTeamOrgMembership(teamId, orgId);

        insertUserRole(memberUserId, memberRoleId, teamId, null);
        insertUserRole(supporterUserId, supporterRoleId, teamId, null);
        insertUserRole(jobberUserId, jobberRoleId, teamId, null);
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
     * job_postings テーブルへ最小限の求人行を直接 INSERT する。
     *
     * <p>NOT NULL 全列を明示する。CHECK 制約（reward 範囲 / work_end_at > work_start_at /
     * application_deadline_at <= work_start_at）に合う値を組み立てる。</p>
     *
     * @return 生成された posting_id
     */
    private Long insertPosting(String title, Long createdBy, String status, String visibilityScope) {
        em.createNativeQuery(
                "INSERT INTO job_postings ("
                        + "team_id, created_by_user_id, title, description, "
                        + "work_location_type, work_start_at, work_end_at, "
                        + "reward_type, base_reward_jpy, capacity, application_deadline_at, "
                        + "visibility_scope, status, version, created_at, updated_at) "
                        + "VALUES (:teamId, :createdBy, :title, '結合テスト用ダミー説明', "
                        + "'ONSITE', "
                        + "DATE_ADD(NOW(), INTERVAL 7 DAY), "
                        + "DATE_ADD(NOW(), INTERVAL 7 DAY) + INTERVAL 4 HOUR, "
                        + "'LUMP_SUM', 5000, 1, "
                        + "DATE_ADD(NOW(), INTERVAL 6 DAY), "
                        + ":visibility, :status, 0, NOW(), NOW())")
                .setParameter("teamId", teamId)
                .setParameter("createdBy", createdBy)
                .setParameter("title", title)
                .setParameter("visibility", visibilityScope)
                .setParameter("status", status)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM job_postings WHERE title = :title")
                .setParameter("title", title)
                .getSingleResult()).longValue();
    }

    // =========================================================================
    // シナリオ — 標準 visibility
    // =========================================================================

    @Test
    @DisplayName("JOBBER_PUBLIC_BOARD × OPEN は匿名・非メンバー・メンバーすべて閲覧可（PUBLIC 相当）")
    void jobber_public_board_visible_to_all() {
        Long postingId = insertPosting("jp-public-open", memberUserId, "OPEN", "JOBBER_PUBLIC_BOARD");
        em.flush();
        em.clear();

        assertThat(checker.canView(ReferenceType.JOB_POSTING, postingId, null)).isTrue();
        assertThat(checker.canView(ReferenceType.JOB_POSTING, postingId, nonMemberUserId)).isTrue();
        assertThat(checker.canView(ReferenceType.JOB_POSTING, postingId, memberUserId)).isTrue();
    }

    @Test
    @DisplayName("TEAM_MEMBERS × OPEN は所属メンバーのみ閲覧可")
    void team_members_visible_to_member_only() {
        Long postingId = insertPosting("jp-team-members-open", memberUserId, "OPEN", "TEAM_MEMBERS");
        em.flush();
        em.clear();

        assertThat(checker.canView(ReferenceType.JOB_POSTING, postingId, null)).isFalse();
        assertThat(checker.canView(ReferenceType.JOB_POSTING, postingId, nonMemberUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.JOB_POSTING, postingId, memberUserId)).isTrue();
        // SUPPORTER は MEMBER 包含外（MEMBERS_ONLY は scope の所属を見る → SUPPORTER も ok）
        // C-2 マスター裁可: TEAM_MEMBERS_SUPPORTERS とは別。MEMBERS_ONLY はスコープ所属者すべて
        assertThat(checker.canView(ReferenceType.JOB_POSTING, postingId, supporterUserId)).isTrue();
    }

    @Test
    @DisplayName("TEAM_MEMBERS_SUPPORTERS × OPEN は MEMBER と SUPPORTER に閲覧可")
    void team_members_supporters_visible_to_supporters() {
        Long postingId = insertPosting("jp-supporters-open", memberUserId, "OPEN", "TEAM_MEMBERS_SUPPORTERS");
        em.flush();
        em.clear();

        assertThat(checker.canView(ReferenceType.JOB_POSTING, postingId, memberUserId)).isTrue();
        assertThat(checker.canView(ReferenceType.JOB_POSTING, postingId, supporterUserId)).isTrue();
        assertThat(checker.canView(ReferenceType.JOB_POSTING, postingId, nonMemberUserId)).isFalse();
    }

    // =========================================================================
    // シナリオ — §5.1.4 CUSTOM (JOBBER_INTERNAL)
    // =========================================================================

    @Test
    @DisplayName("JOBBER_INTERNAL × OPEN は当該チームの JOBBER ロール保有者のみ閲覧可")
    void jobber_internal_visible_to_jobber_only() {
        Long postingId = insertPosting("jp-jobber-internal-open", memberUserId, "OPEN", "JOBBER_INTERNAL");
        em.flush();
        em.clear();

        // JOBBER 本人 → 可視
        assertThat(checker.canView(ReferenceType.JOB_POSTING, postingId, jobberUserId)).isTrue();
        // 通常 MEMBER → 不可視（JOBBER は並行ロール）
        assertThat(checker.canView(ReferenceType.JOB_POSTING, postingId, memberUserId)).isFalse();
        // SUPPORTER → 不可視
        assertThat(checker.canView(ReferenceType.JOB_POSTING, postingId, supporterUserId)).isFalse();
        // 非所属ユーザー → 不可視
        assertThat(checker.canView(ReferenceType.JOB_POSTING, postingId, nonMemberUserId)).isFalse();
        // 匿名 → 不可視
        assertThat(checker.canView(ReferenceType.JOB_POSTING, postingId, null)).isFalse();
        // SystemAdmin → 可視（基底高速パス）
        assertThat(checker.canView(ReferenceType.JOB_POSTING, postingId, sysAdminUserId)).isTrue();
    }

    // =========================================================================
    // シナリオ — status 軸ガード
    // =========================================================================

    @Test
    @DisplayName("DRAFT は作成者本人および SystemAdmin のみ閲覧可（visibility 無関係）")
    void draft_visible_to_author_or_sysadmin_only() {
        Long postingId = insertPosting("jp-draft", memberUserId, "DRAFT", "JOBBER_PUBLIC_BOARD");
        em.flush();
        em.clear();

        assertThat(checker.canView(ReferenceType.JOB_POSTING, postingId, null)).isFalse();
        assertThat(checker.canView(ReferenceType.JOB_POSTING, postingId, nonMemberUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.JOB_POSTING, postingId, memberUserId)).isTrue();
        assertThat(checker.canView(ReferenceType.JOB_POSTING, postingId, sysAdminUserId)).isTrue();
    }

    @Test
    @DisplayName("CANCELLED は SystemAdmin のみ閲覧可（ARCHIVED 扱い）")
    void cancelled_visible_to_sysadmin_only() {
        Long postingId = insertPosting("jp-cancelled", memberUserId, "CANCELLED", "JOBBER_PUBLIC_BOARD");
        em.flush();
        em.clear();

        assertThat(checker.canView(ReferenceType.JOB_POSTING, postingId, null)).isFalse();
        assertThat(checker.canView(ReferenceType.JOB_POSTING, postingId, memberUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.JOB_POSTING, postingId, nonMemberUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.JOB_POSTING, postingId, sysAdminUserId)).isTrue();
    }

    @Test
    @DisplayName("CLOSED は PUBLISHED 相当として visibility 評価へ進む（応募終了後も閲覧可）")
    void closed_visible_per_visibility() {
        Long postingId = insertPosting("jp-closed", memberUserId, "CLOSED", "TEAM_MEMBERS");
        em.flush();
        em.clear();

        assertThat(checker.canView(ReferenceType.JOB_POSTING, postingId, memberUserId)).isTrue();
        assertThat(checker.canView(ReferenceType.JOB_POSTING, postingId, nonMemberUserId)).isFalse();
    }

    // =========================================================================
    // シナリオ — その他ガード
    // =========================================================================

    @Test
    @DisplayName("不存在 ID は誰に対しても false")
    void unknown_id_false() {
        assertThat(checker.canView(ReferenceType.JOB_POSTING, 999_999L, memberUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.JOB_POSTING, 999_999L, sysAdminUserId)).isFalse();
    }

    @Test
    @DisplayName("filterAccessible は status × visibility × ロールを横断して正しくフィルタ")
    void filterAccessible_mixed_visibility() {
        Long openPublic   = insertPosting("jp-flt-1", memberUserId, "OPEN", "JOBBER_PUBLIC_BOARD");
        Long openMembers  = insertPosting("jp-flt-2", memberUserId, "OPEN", "TEAM_MEMBERS");
        Long openJobber   = insertPosting("jp-flt-3", memberUserId, "OPEN", "JOBBER_INTERNAL");
        Long draftAuthor  = insertPosting("jp-flt-4", memberUserId, "DRAFT", "JOBBER_PUBLIC_BOARD");
        Long cancelled    = insertPosting("jp-flt-5", memberUserId, "CANCELLED", "JOBBER_PUBLIC_BOARD");
        em.flush();
        em.clear();

        List<Long> all = List.of(openPublic, openMembers, openJobber, draftAuthor, cancelled);

        // 非メンバー: PUBLIC 系の OPEN のみ閲覧可
        Set<Long> nonMember = checker.filterAccessible(
                ReferenceType.JOB_POSTING, all, nonMemberUserId);
        assertThat(nonMember).containsExactly(openPublic);

        // MEMBER: PUBLIC + MEMBERS（JOBBER_INTERNAL は不可視）
        Set<Long> member = checker.filterAccessible(
                ReferenceType.JOB_POSTING, all, memberUserId);
        assertThat(member).containsExactlyInAnyOrder(openPublic, openMembers, draftAuthor);
        // ↑ draftAuthor は memberUserId が author 自身なので可視

        // JOBBER: PUBLIC + JOBBER_INTERNAL（MEMBERS は所属外なので不可視）
        Set<Long> jobber = checker.filterAccessible(
                ReferenceType.JOB_POSTING, all, jobberUserId);
        assertThat(jobber).containsExactlyInAnyOrder(openPublic, openJobber);

        // SystemAdmin: status=DELETED 以外すべて可視（CANCELLED 含む）
        Set<Long> sysAdmin = checker.filterAccessible(
                ReferenceType.JOB_POSTING, all, sysAdminUserId);
        assertThat(sysAdmin).containsExactlyInAnyOrder(
                openPublic, openMembers, openJobber, draftAuthor, cancelled);
    }
}
