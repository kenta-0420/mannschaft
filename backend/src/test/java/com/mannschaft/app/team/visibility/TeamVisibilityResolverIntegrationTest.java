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
 * F00 Phase D-3 — {@link TeamVisibilityResolver} 結合テスト（ロールベース設計）。
 *
 * <p>実 MySQL（Testcontainers）に対し最小限の seed を投入し、
 * {@link ContentVisibilityChecker} 経由で TEAM の可視性評価を包括的に検証する。</p>
 *
 * <p>{@link SpringBootTest#properties()} で {@code feature.visibility-resolver.team=true}
 * を設定し、{@link TeamVisibilityResolver} Bean を有効化している。</p>
 *
 * <p>{@code SurveyVisibilityResolverIntegrationTest} の方式を踏襲し、
 * {@code @Transactional} ロールバック方式 + {@code em.createNativeQuery}
 * で users / roles / user_roles / teams を直接 INSERT する。</p>
 *
 * <p>D-3 ロールベース設計マッピング:
 * <ul>
 *   <li>GUESTS_AND_ABOVE → SCOPE_AFFILIATED（GUEST以上の全所属メンバー閲覧可）のシナリオを検証</li>
 *   <li>MEMBERS_AND_ABOVE → MEMBERS_AND_ABOVE（正規メンバー以上のみ可視）のシナリオを検証</li>
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
    private Long publicTeamId;
    private Long guestsAndAboveTeamId;
    private Long membersAndAboveTeamId;

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

        // teams 挿入（PUBLIC / GUESTS_AND_ABOVE / MEMBERS_AND_ABOVE の 3 種）
        publicTeamId = insertTeam("TV結合テストチーム_PUBLIC", "PUBLIC");
        guestsAndAboveTeamId = insertTeam("TV結合テストチーム_GUESTS_AND_ABOVE", "GUESTS_AND_ABOVE");
        membersAndAboveTeamId = insertTeam("TV結合テストチーム_MEMBERS_AND_ABOVE", "MEMBERS_AND_ABOVE");

        // memberships: memberUserId を各チームの MEMBER として登録
        insertUserRole(memberUserId, memberRoleId, publicTeamId, null);
        insertUserRole(memberUserId, memberRoleId, guestsAndAboveTeamId, null);
        insertUserRole(memberUserId, memberRoleId, membersAndAboveTeamId, null);

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
    // シナリオ 2: GUESTS_AND_ABOVE チームはメンバーに可視、非メンバーには不可視
    // =========================================================================

    @Test
    @DisplayName("guests_and_above_team_visible_to_members_only: GUESTS_AND_ABOVE チームはメンバーに true、非メンバーに false")
    void guests_and_above_team_visible_to_members_only() {
        // チームメンバー（setUp で guestsAndAboveTeamId に MEMBER として登録済み）→ 可視
        assertThat(checker.canView(ReferenceType.TEAM, guestsAndAboveTeamId, memberUserId)).isTrue();
        // 非メンバー → 不可視
        assertThat(checker.canView(ReferenceType.TEAM, guestsAndAboveTeamId, nonMemberUserId)).isFalse();
        // 匿名ユーザー → 不可視
        assertThat(checker.canView(ReferenceType.TEAM, guestsAndAboveTeamId, null)).isFalse();
        // SystemAdmin は高速パスで可視
        assertThat(checker.canView(ReferenceType.TEAM, guestsAndAboveTeamId, sysAdminUserId)).isTrue();
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
    // シナリオ 4: MEMBERS_AND_ABOVE チームはメンバーに可視、非メンバーには不可視
    // =========================================================================

    @Test
    @DisplayName("members_and_above_team_visible_to_members_only: MEMBERS_AND_ABOVE チームはメンバーに true、非メンバーに false")
    void members_and_above_team_visible_to_members_only() {
        // チームメンバー → 可視
        assertThat(checker.canView(ReferenceType.TEAM, membersAndAboveTeamId, memberUserId)).isTrue();
        // 非メンバー → 不可視
        assertThat(checker.canView(ReferenceType.TEAM, membersAndAboveTeamId, nonMemberUserId)).isFalse();
        // 匿名ユーザー → 不可視
        assertThat(checker.canView(ReferenceType.TEAM, membersAndAboveTeamId, null)).isFalse();
        // SystemAdmin は高速パスで可視
        assertThat(checker.canView(ReferenceType.TEAM, membersAndAboveTeamId, sysAdminUserId)).isTrue();
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
                "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, created_at, updated_at, slug) "
                        + "VALUES (:name, :visibility, 1, 0, 0, NOW(), NOW(), LEFT(REPLACE(UUID(), '-', ''), 22))")
                .setParameter("name", name)
                .setParameter("visibility", visibility)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
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
