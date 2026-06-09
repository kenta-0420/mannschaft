package com.mannschaft.app.organization.visibility;

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
 * F00 Phase D-δ — {@link OrganizationVisibilityResolver} 結合テスト。
 *
 * <p>実 MySQL（Testcontainers）に対し最小限の seed を投入し、
 * {@link ContentVisibilityChecker} 経由で ORGANIZATION の可視性評価を包括的に検証する。</p>
 *
 * <p>{@link SpringBootTest#properties()} で {@code feature.visibility-resolver.organization=true}
 * を設定し、{@link OrganizationVisibilityResolver} Bean を有効化している。</p>
 *
 * <p>{@code TeamVisibilityResolverIntegrationTest} の方式を踏襲し、
 * {@code @Transactional} ロールバック方式 + {@code em.createNativeQuery}
 * で users / roles / user_roles / organizations を直接 INSERT する。</p>
 *
 * <p>organizations テーブルの必須カラム（NOT NULL）:
 * name, org_type, visibility, hierarchy_visibility, supporter_enabled, version,
 * created_at, updated_at。</p>
 */
@SpringBootTest(properties = {"feature.visibility-resolver.organization=true"})
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("OrganizationVisibilityResolver 結合テスト")
class OrganizationVisibilityResolverIntegrationTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private ContentVisibilityChecker checker;

    @PersistenceContext
    private EntityManager em;

    private Long memberRoleId;
    private Long systemAdminRoleId;
    private Long memberUserId;
    private Long nonMemberUserId;
    private Long sysAdminUserId;
    private Long publicOrgId;
    private Long privateOrgId;

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
        memberUserId = insertUser("ov.member@example.com", "田中", "一郎");
        nonMemberUserId = insertUser("ov.nonmember@example.com", "山田", "花子");
        sysAdminUserId = insertUser("ov.sysadmin@example.com", "管理", "者");

        // organizations 挿入（PUBLIC / PRIVATE の 2 種）
        publicOrgId = insertOrganization("OV結合テスト組織_PUBLIC", "PUBLIC");
        privateOrgId = insertOrganization("OV結合テスト組織_PRIVATE", "PRIVATE");

        // memberships: memberUserId を publicOrgId / privateOrgId の MEMBER として登録
        insertUserRole(memberUserId, memberRoleId, null, publicOrgId);
        insertUserRole(memberUserId, memberRoleId, null, privateOrgId);

        // sysAdmin を SYSTEM_ADMIN として登録
        insertUserRole(sysAdminUserId, systemAdminRoleId, null, null);

        em.flush();
        em.clear();
    }

    // =========================================================================
    // シナリオ 1: PUBLIC 組織は非メンバーでも閲覧可
    // =========================================================================

    @Test
    @DisplayName("public_org_visible_to_non_member: PUBLIC 組織は非メンバーでも true")
    void public_org_visible_to_non_member() {
        // 非メンバー
        assertThat(checker.canView(ReferenceType.ORGANIZATION, publicOrgId, nonMemberUserId)).isTrue();
        // 匿名ユーザー
        assertThat(checker.canView(ReferenceType.ORGANIZATION, publicOrgId, null)).isTrue();
        // メンバー
        assertThat(checker.canView(ReferenceType.ORGANIZATION, publicOrgId, memberUserId)).isTrue();
        // SystemAdmin
        assertThat(checker.canView(ReferenceType.ORGANIZATION, publicOrgId, sysAdminUserId)).isTrue();
    }

    // =========================================================================
    // シナリオ 2: PRIVATE 組織は非メンバーには非公開・メンバーは閲覧可
    // =========================================================================

    @Test
    @DisplayName("private_org_invisible_to_non_admin: PRIVATE 組織は非メンバーは false・メンバーは true")
    void private_org_invisible_to_non_admin() {
        // 非メンバー（組織に所属していない）
        assertThat(checker.canView(ReferenceType.ORGANIZATION, privateOrgId, nonMemberUserId)).isFalse();
        // 匿名ユーザー
        assertThat(checker.canView(ReferenceType.ORGANIZATION, privateOrgId, null)).isFalse();
        // MEMBER ロールで MEMBERS_ONLY に届く（自組織は閲覧可）
        assertThat(checker.canView(ReferenceType.ORGANIZATION, privateOrgId, memberUserId)).isTrue();
        // SystemAdmin は高速パスで可視
        assertThat(checker.canView(ReferenceType.ORGANIZATION, privateOrgId, sysAdminUserId)).isTrue();
    }

    // =========================================================================
    // シナリオ 3: 不存在 ID は誰に対しても false
    // =========================================================================

    @Test
    @DisplayName("unknown_id_false: 不存在 ID は誰でも false")
    void unknown_id_false() {
        assertThat(checker.canView(ReferenceType.ORGANIZATION, 999_999L, memberUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.ORGANIZATION, 999_999L, sysAdminUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.ORGANIZATION, 999_999L, null)).isFalse();
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

    private Long insertOrganization(String name, String visibility) {
        em.createNativeQuery(
                "INSERT INTO organizations ("
                        + "name, org_type, visibility, hierarchy_visibility, "
                        + "supporter_enabled, version, created_at, updated_at, slug) "
                        + "VALUES (:name, 'OTHER', :visibility, 'NONE', 1, 0, NOW(), NOW(), LEFT(REPLACE(UUID(), '-', ''), 22))")
                .setParameter("name", name)
                .setParameter("visibility", visibility)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM organizations WHERE name = :name")
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
