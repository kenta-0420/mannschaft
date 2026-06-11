package com.mannschaft.app.gallery.visibility;

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
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F00 Phase D-β — {@link PhotoAlbumVisibilityResolver} 結合テスト。
 *
 * <p>実 MySQL（Testcontainers）に対し最小限の seed を投入し、Resolver と
 * {@link ContentVisibilityChecker} を組み立てた上で AlbumVisibility 3 値
 * (ALL_MEMBERS / ADMIN_ONLY) の可視性判定を検証する。</p>
 *
 * <p>{@code SurveyVisibilityResolverIntegrationTest} の方式を踏襲する。
 * すなわち {@code @Transactional} ロールバック方式 + {@code em.createNativeQuery}
 * で users / organizations / teams / team_org_memberships / roles / user_roles
 * および photo_albums を直接 INSERT する。</p>
 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("PhotoAlbumVisibilityResolver 結合テスト")
class PhotoAlbumVisibilityResolverIntegrationTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private ContentVisibilityChecker checker;

    @PersistenceContext
    private EntityManager em;

    private Long memberRoleId;
    private Long adminRoleId;
    private Long systemAdminRoleId;
    private Long memberUserId;
    private Long nonMemberUserId;
    private Long adminUserId;
    private Long sysAdminUserId;
    private Long teamId;
    private Long orgId;

    @BeforeEach
    void setUp() {
        em.createNativeQuery(
                "INSERT INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                        + "VALUES ('SYSTEM_ADMIN', 'システム管理者', 1, 1, NOW(), NOW())")
                .executeUpdate();
        em.createNativeQuery(
                "INSERT INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                        + "VALUES ('ADMIN', '管理者', 2, 0, NOW(), NOW())")
                .executeUpdate();
        em.createNativeQuery(
                "INSERT INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                        + "VALUES ('MEMBER', 'メンバー', 4, 0, NOW(), NOW())")
                .executeUpdate();
        em.flush();

        memberRoleId = ((Number) em.createNativeQuery(
                "SELECT id FROM roles WHERE name = 'MEMBER'").getSingleResult()).longValue();
        adminRoleId = ((Number) em.createNativeQuery(
                "SELECT id FROM roles WHERE name = 'ADMIN'").getSingleResult()).longValue();
        systemAdminRoleId = ((Number) em.createNativeQuery(
                "SELECT id FROM roles WHERE name = 'SYSTEM_ADMIN'").getSingleResult()).longValue();

        memberUserId  = insertUser("pa.member@example.com",    "山田", "太郎");
        nonMemberUserId = insertUser("pa.nonmember@example.com", "鈴木", "花子");
        adminUserId   = insertUser("pa.admin@example.com",     "佐藤", "次郎");
        sysAdminUserId = insertUser("pa.sysadmin@example.com", "管理", "者");

        orgId  = insertOrganization("PA結合 組織");
        teamId = insertTeam("PA結合 チーム");
        insertTeamOrgMembership(teamId, orgId);

        insertUserRole(memberUserId,   memberRoleId,      teamId, null);
        insertUserRole(adminUserId,    adminRoleId,       teamId, null);
        insertUserRole(sysAdminUserId, systemAdminRoleId, null,   null);

        // F00.5 以降: memberships テーブルにも挿入（MembershipBatchQueryService が参照する）
        insertMembership(memberUserId, "TEAM", teamId, "MEMBER");
        insertMembership(adminUserId,  "TEAM", teamId, "MEMBER");

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
                        + "supporter_enabled, version, slug, created_at, updated_at) "
                        + "VALUES (:name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM organizations WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    private Long insertTeam(String name) {
        em.createNativeQuery(
                "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, created_at, updated_at) "
                        + "VALUES (:name, 'PUBLIC', 1, 0, 0, CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    private void insertTeamOrgMembership(Long tid, Long oid) {
        em.createNativeQuery(
                "INSERT INTO team_org_memberships (team_id, organization_id, status, invited_at, created_at) "
                        + "VALUES (:tid, :oid, 'ACTIVE', NOW(), NOW())")
                .setParameter("tid", tid)
                .setParameter("oid", oid)
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
     * memberships テーブルに入会レコードを挿入する（F00.5 以降のデータモデル）。
     *
     * @param uid       user_id
     * @param scopeType "TEAM" または "ORGANIZATION"
     * @param scopeId   team_id または organization_id
     * @param roleKind  "MEMBER" または "SUPPORTER"
     */
    private void insertMembership(Long uid, String scopeType, Long scopeId, String roleKind) {
        em.createNativeQuery(
                "INSERT INTO memberships (user_id, scope_type, scope_id, role_kind, joined_at, created_at, updated_at) "
                        + "VALUES (:uid, :scopeType, :scopeId, :roleKind, NOW(), NOW(), NOW())")
                .setParameter("uid", uid)
                .setParameter("scopeType", scopeType)
                .setParameter("scopeId", scopeId)
                .setParameter("roleKind", roleKind)
                .executeUpdate();
    }

    /**
     * photo_albums テーブルに最小 NOT NULL 全列を直接 INSERT する。
     *
     * @param title      アルバムタイトル（一意キーとして利用）
     * @param createdBy  作成者 user_id
     * @param visibility visibility 文字列（'ALL_MEMBERS' / 'SUPPORTERS_AND_ABOVE' / 'ADMIN_ONLY'）
     * @param scopeTeamId  team_id ({@code null} 可)
     * @param scopeOrgId   organization_id ({@code null} 可)
     * @return 生成された photo_album_id
     */
    private Long insertPhotoAlbum(String title, Long createdBy, String visibility,
                                   Long scopeTeamId, Long scopeOrgId) {
        em.createNativeQuery(
                "INSERT INTO photo_albums ("
                        + "team_id, organization_id, title, visibility, "
                        + "allow_member_upload, allow_download, photo_count, "
                        + "created_by, created_at, updated_at) "
                        + "VALUES (:tid, :oid, :title, :visibility, "
                        + "0, 1, 0, "
                        + ":createdBy, NOW(), NOW())")
                .setParameter("tid", scopeTeamId)
                .setParameter("oid", scopeOrgId)
                .setParameter("title", title)
                .setParameter("visibility", visibility)
                .setParameter("createdBy", createdBy)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM photo_albums WHERE title = :title")
                .setParameter("title", title)
                .getSingleResult()).longValue();
    }

    // =========================================================================
    // ALL_MEMBERS シナリオ（MEMBERS_ONLY に正規化）
    // =========================================================================

    @Test
    @DisplayName("ALL_MEMBERS + TEAM スコープ — チームメンバーは閲覧可、非メンバーは不可")
    void all_members_album_team_member_can_view() {
        Long albumId = insertPhotoAlbum(
                "pa-all-members", memberUserId, "ALL_MEMBERS", teamId, null);
        em.flush();
        em.clear();

        // チームメンバーは可視
        assertThat(checker.canView(ReferenceType.PHOTO_ALBUM, albumId, memberUserId)).isTrue();
        // 管理者もメンバーなので可視
        assertThat(checker.canView(ReferenceType.PHOTO_ALBUM, albumId, adminUserId)).isTrue();
        // 非メンバーは不可視
        assertThat(checker.canView(ReferenceType.PHOTO_ALBUM, albumId, nonMemberUserId)).isFalse();
        // 匿名不可視
        assertThat(checker.canView(ReferenceType.PHOTO_ALBUM, albumId, null)).isFalse();
        // SystemAdmin は高速パスで可視
        assertThat(checker.canView(ReferenceType.PHOTO_ALBUM, albumId, sysAdminUserId)).isTrue();
    }

    // =========================================================================
    // ADMIN_ONLY シナリオ（ADMINS_ONLY に正規化）
    // =========================================================================

    @Test
    @DisplayName("ADMIN_ONLY + TEAM スコープ — 非管理者は閲覧不可、管理者は可視")
    void admins_only_album_non_admin_cannot_view() {
        Long albumId = insertPhotoAlbum(
                "pa-admin-only", adminUserId, "ADMIN_ONLY", teamId, null);
        em.flush();
        em.clear();

        // 一般メンバーは不可視
        assertThat(checker.canView(ReferenceType.PHOTO_ALBUM, albumId, memberUserId)).isFalse();
        // 非メンバーも不可視
        assertThat(checker.canView(ReferenceType.PHOTO_ALBUM, albumId, nonMemberUserId)).isFalse();
        // 匿名不可視
        assertThat(checker.canView(ReferenceType.PHOTO_ALBUM, albumId, null)).isFalse();
        // ADMIN ロール所持者は可視
        assertThat(checker.canView(ReferenceType.PHOTO_ALBUM, albumId, adminUserId)).isTrue();
        // SystemAdmin は高速パスで可視
        assertThat(checker.canView(ReferenceType.PHOTO_ALBUM, albumId, sysAdminUserId)).isTrue();
    }

    // =========================================================================
    // 不存在 ID シナリオ
    // =========================================================================

    @Test
    @DisplayName("不存在 ID は false（IDOR 防止 §11.3）")
    void unknown_id_false() {
        assertThat(checker.canView(ReferenceType.PHOTO_ALBUM, 999_999L, memberUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.PHOTO_ALBUM, 999_999L, sysAdminUserId)).isFalse();
        assertThat(checker.canView(ReferenceType.PHOTO_ALBUM, 999_999L, null)).isFalse();
    }
}
