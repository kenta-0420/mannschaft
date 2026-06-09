package com.mannschaft.app.team.visibility;

import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F00 Phase D-3 — {@link TeamVisibilityResolver} 結合テスト。
 *
 * <p>実 MySQL（Testcontainers）に対し最小限の seed を投入し、
 * {@link ContentVisibilityChecker} 経由で TEAM の可視性評価を包括的に検証する。</p>
 *
 * <p>{@link SpringBootTest#properties()} で {@code feature.visibility-resolver.team=true}
 * を設定し、{@link TeamVisibilityResolver} Bean を有効化している。</p>
 *
 * <p>{@code SurveyVisibilityResolverIntegrationTest} の方式を踏襲し、
 * {@code @Transactional} ロールバック方式 + {@code em.createNativeQuery}
 * で users / roles / user_roles / teams / organizations / team_org_memberships を直接 INSERT する。</p>
 *
 * <p>D-3 で追加されたマッピング:
 * <ul>
 *   <li>ORGANIZATION_ONLY → ORGANIZATION_WIDE（親 ORG メンバーまで公開）のシナリオを検証</li>
 *   <li>PRIVATE → MEMBERS_ONLY（チームメンバーのみ可視。{@code TeamEntity} に {@code created_by}
 *       がないため {@code PRIVATE}（作成者本人のみ）は実質 fail-closed となる。
 *       チームの「非公開」の意図は「メンバーだけ見える」であるため MEMBERS_ONLY を使用）のシナリオを検証</li>
 * </ul>
 */
@SpringBootTest(properties = {"feature.visibility-resolver.team=true"})
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("TeamVisibilityResolver 結合テスト")
class TeamVisibilityResolverIntegrationTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private ContentVisibilityChecker checker;

    @PersistenceContext
    private EntityManager em;

    private Long memberRoleId;
    private Long systemAdminRoleId;
    private Long memberUserId;
    private Long nonMemberUserId;
    private Long sysAdminUserId;
    private Long orgMemberUserId;
    private Long publicTeamId;
    private Long privateTeamId;
    private Long orgOnlyTeamId;
    private Long orgId;

    @BeforeEach
    void setUp() {
        // roles 挿入
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

        // users 挿入
        memberUserId = insertUser("tv.member@example.com", "田中", "一郎");
        nonMemberUserId = insertUser("tv.nonmember@example.com", "山田", "花子");
        sysAdminUserId = insertUser("tv.sysadmin@example.com", "管理", "者");
        orgMemberUserId = insertUser("tv.orgmember@example.com", "組織", "メンバー");

        // 組織挿入（ORGANIZATION_ONLY チームの親 ORG）
        orgId = insertOrganization("TV結合テスト組織");

        // teams 挿入（PUBLIC / PRIVATE / ORGANIZATION_ONLY の 3 種）
        publicTeamId = insertTeam("TV結合テストチーム_PUBLIC", "PUBLIC");
        privateTeamId = insertTeam("TV結合テストチーム_PRIVATE", "PRIVATE");
        orgOnlyTeamId = insertTeam("TV結合テストチーム_ORGANIZATION_ONLY", "ORGANIZATION_ONLY");

        // team_org_memberships: orgOnlyTeamId を orgId に所属させる（ORGANIZATION_WIDE 解決用）
        insertTeamOrgMembership(orgOnlyTeamId, orgId);

        // memberships: memberUserId を publicTeamId / privateTeamId の MEMBER として登録
        insertUserRole(memberUserId, memberRoleId, publicTeamId, null);
        insertUserRole(memberUserId, memberRoleId, privateTeamId, null);

        // orgMemberUserId を org の MEMBER として登録（ORGANIZATION_WIDE 評価対象）
        insertUserRole(orgMemberUserId, memberRoleId, null, orgId);

        // sysAdmin を SYSTEM_ADMIN として登録
        insertUserRole(sysAdminUserId, systemAdminRoleId, null, null);

        em.flush();
        em.clear();
    }

    // =========================================================================
    // シナリオ 1: PUBLIC チームは非メンバーでも閲覧可
    // =========================================================================

    @Test
    @DisplayName("public_team_visible_to_non_member: PUBLIC チームは非メンバーでも true")
    void public_team_visible_to_non_member() {
        // 非メンバー
        assertThat(checker.canView(ReferenceType.TEAM, publicTeamId, nonMemberUserId)).isTrue();
        // 匿名ユーザー
        assertThat(checker.canView(ReferenceType.TEAM, publicTeamId, null)).isTrue();
        // メンバー
        assertThat(checker.canView(ReferenceType.TEAM, publicTeamId, memberUserId)).isTrue();
        // SystemAdmin
        assertThat(checker.canView(ReferenceType.TEAM, publicTeamId, sysAdminUserId)).isTrue();
    }

    // =========================================================================
    // シナリオ 2: PRIVATE チームはメンバーに可視、非メンバーには不可視
    // =========================================================================

    @Test
    @DisplayName("private_team_visible_to_members_only: PRIVATE チームはメンバーに true、非メンバーに false")
    void private_team_visible_to_members_only() {
        // チームメンバー（setUp で privateTeamId に MEMBER として登録済み）→ 可視
        assertThat(checker.canView(ReferenceType.TEAM, privateTeamId, memberUserId)).isTrue();
        // 非メンバー → 不可視
        assertThat(checker.canView(ReferenceType.TEAM, privateTeamId, nonMemberUserId)).isFalse();
        // 匿名ユーザー → 不可視
        assertThat(checker.canView(ReferenceType.TEAM, privateTeamId, null)).isFalse();
        // SystemAdmin は高速パスで可視
        assertThat(checker.canView(ReferenceType.TEAM, privateTeamId, sysAdminUserId)).isTrue();
    }

    // =========================================================================
    // シナリオ 3: 不存在 ID は誰に対しても false
    // =========================================================================

    @Test
    @DisplayName("unknown_id_false: 不存在 ID は誰でも false")
    void unknown_id_false() {
        assertThat(checker.canView(ReferenceType.TEAM, 999_999L, memberUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.TEAM, 999_999L, sysAdminUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.TEAM, 999_999L, null)).isFalse();
    }

    // =========================================================================
    // シナリオ 4: ORGANIZATION_ONLY チームは親 ORG メンバーのみ閲覧可
    // =========================================================================

    @Test
    @DisplayName("org_only_team_visible_to_org_member: ORGANIZATION_ONLY チームは親 ORG メンバーに true")
    void org_only_team_visible_to_org_member() {
        // 親 ORG メンバー (orgMemberUserId) は ORGANIZATION_WIDE 経由で可視
        assertThat(checker.canView(ReferenceType.TEAM, orgOnlyTeamId, orgMemberUserId)).isTrue();
        // SystemAdmin は常に可視
        assertThat(checker.canView(ReferenceType.TEAM, orgOnlyTeamId, sysAdminUserId)).isTrue();
        // チームメンバーでも ORG メンバーでもないユーザーは不可視
        assertThat(checker.canView(ReferenceType.TEAM, orgOnlyTeamId, nonMemberUserId)).isFalse();
        // 匿名ユーザーは不可視
        assertThat(checker.canView(ReferenceType.TEAM, orgOnlyTeamId, null)).isFalse();
    }

    // =========================================================================
    // シナリオ 5: PRIVATE チームは MEMBERS_ONLY として評価される
    // =========================================================================

    @Test
    @DisplayName("private_team_members_only: PRIVATE は MEMBERS_ONLY にマップ — メンバーは可視、非メンバーは不可視")
    void private_team_members_only() {
        // PRIVATE → MEMBERS_ONLY: チームメンバーは可視
        assertThat(checker.canView(ReferenceType.TEAM, privateTeamId, memberUserId)).isTrue();
        // チームメンバーではないユーザーは不可視
        assertThat(checker.canView(ReferenceType.TEAM, privateTeamId, nonMemberUserId)).isFalse();
        // 匿名ユーザーは不可視
        assertThat(checker.canView(ReferenceType.TEAM, privateTeamId, null)).isFalse();
        // SystemAdmin は高速パスで可視
        assertThat(checker.canView(ReferenceType.TEAM, privateTeamId, sysAdminUserId)).isTrue();
    }

    // =========================================================================
    // ヘルパ
    // =========================================================================

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

    private Long insertTeam(String name, String visibility) {
        em.createNativeQuery(
                "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, created_at, updated_at, public_id) "
                        + "VALUES (:name, :visibility, 1, 0, 0, NOW(), NOW(), UUID_TO_BIN(UUID(), 1))")
                .setParameter("name", name)
                .setParameter("visibility", visibility)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    private Long insertOrganization(String name) {
        em.createNativeQuery(
                "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                        + "supporter_enabled, version, created_at, updated_at, public_id) "
                        + "VALUES (:name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, NOW(), NOW(), UUID_TO_BIN(UUID(), 1))")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM organizations WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    private void insertTeamOrgMembership(Long teamId, Long organizationId) {
        em.createNativeQuery(
                "INSERT INTO team_org_memberships "
                        + "(team_id, organization_id, status, invited_at, created_at) "
                        + "VALUES (:teamId, :orgId, 'ACTIVE', NOW(), NOW())")
                .setParameter("teamId", teamId)
                .setParameter("orgId", organizationId)
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
}
