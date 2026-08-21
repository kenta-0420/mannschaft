package com.mannschaft.app.filesharing.visibility;

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
 * F00 Phase D-β — {@link FileAttachmentVisibilityResolver} 結合テスト。
 *
 * <p>実 MySQL（Testcontainers）に対し最小限の seed を投入し、Resolver と
 * {@link ContentVisibilityChecker} を組み立てた上で以下を検証する:</p>
 * <ol>
 *   <li>TEAM スコープのファイルはチームメンバーが閲覧可、非メンバーは不可</li>
 *   <li>PERSONAL スコープのファイルはフォルダ所有者のみ閲覧可</li>
 *   <li>不存在 ID は fail-closed（false）</li>
 * </ol>
 *
 * <p>{@link AbstractMySqlIntegrationTest} を継承し Spring TestContext Cache を共有する。
 * native query で users / roles / teams / user_roles を INSERT し、
 * shared_folders / shared_files を同様に native query で投入する。</p>
 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("FileAttachmentVisibilityResolver — 結合テスト")
class FileAttachmentVisibilityResolverIntegrationTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private FileAttachmentVisibilityResolver resolver;

    @Autowired
    private ContentVisibilityChecker contentVisibilityChecker;

    @PersistenceContext
    private EntityManager em;

    private Long memberRoleId;
    private Long systemAdminRoleId;
    private Long teamMemberUserId;
    private Long nonMemberUserId;
    private Long folderOwnerUserId;
    private Long sysAdminUserId;
    private Long teamId;

    @BeforeEach
    void setUp() {
        // ロール
        // 冪等化: roles はグローバル参照テーブルのため INSERT IGNORE で二重INSERTを無害化する
        // （同一 name の重複INSERTは UNIQUE 制約違反になる。CI shard 再編成で同一 JVM 内の
        // 同居テストが変わり得るため、盲目的 INSERT は禁止。既存行があれば黙って再利用する）。
        em.createNativeQuery(
                "INSERT IGNORE INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                        + "VALUES ('SYSTEM_ADMIN', 'システム管理者', 1, 1, NOW(), NOW())")
                .executeUpdate();
        em.createNativeQuery(
                "INSERT IGNORE INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                        + "VALUES ('MEMBER', 'メンバー', 4, 0, NOW(), NOW())")
                .executeUpdate();
        em.flush();

        memberRoleId = ((Number) em.createNativeQuery(
                "SELECT id FROM roles WHERE name = 'MEMBER'").getSingleResult()).longValue();
        systemAdminRoleId = ((Number) em.createNativeQuery(
                "SELECT id FROM roles WHERE name = 'SYSTEM_ADMIN'").getSingleResult()).longValue();

        // ユーザー
        teamMemberUserId = insertUser("fa-vr-member@test.com", "チーム", "メンバー");
        nonMemberUserId = insertUser("fa-vr-nonmember@test.com", "非", "メンバー");
        folderOwnerUserId = insertUser("fa-vr-owner@test.com", "フォルダ", "オーナー");
        sysAdminUserId = insertUser("fa-vr-sysadmin@test.com", "システム", "管理者");

        // チーム
        teamId = insertTeam("FA-VR-テストチーム");

        // user_roles（チームメンバー・SystemAdmin）
        insertUserRole(teamMemberUserId, memberRoleId, teamId, null);
        insertUserRole(sysAdminUserId, systemAdminRoleId, null, null);

        em.flush();
        em.clear();
    }

    // =========================================================================
    // シナリオ
    // =========================================================================

    @Test
    @DisplayName("team_scoped_file_member_can_view — TEAMスコープのファイル: チームメンバーは閲覧可、非メンバーは不可")
    void team_scoped_file_member_can_view() {
        // TEAM スコープのフォルダを作成
        Long folderId = insertTeamFolder(teamId, teamMemberUserId);
        // フォルダ内にファイルを作成
        Long fileId = insertSharedFile(folderId, teamMemberUserId);
        em.flush();
        em.clear();

        // チームメンバーは閲覧可
        assertThat(resolver.canView(fileId, teamMemberUserId)).isTrue();
        assertThat(contentVisibilityChecker.canView(
                ReferenceType.FILE_ATTACHMENT, fileId, teamMemberUserId)).isTrue();

        // 非メンバーは閲覧不可
        assertThat(resolver.canView(fileId, nonMemberUserId)).isFalse();
        assertThat(contentVisibilityChecker.canView(
                ReferenceType.FILE_ATTACHMENT, fileId, nonMemberUserId)).isFalse();
    }

    @Test
    @DisplayName("personal_scoped_file_only_owner_can_view — PERSONALスコープのファイル: フォルダ所有者のみ閲覧可")
    void personal_scoped_file_only_owner_can_view() {
        // PERSONAL スコープのフォルダ（所有者: folderOwnerUserId）を作成
        Long folderId = insertPersonalFolder(folderOwnerUserId);
        Long fileId = insertSharedFile(folderId, folderOwnerUserId);
        em.flush();
        em.clear();

        // フォルダ所有者は閲覧可
        assertThat(resolver.canView(fileId, folderOwnerUserId)).isTrue();

        // チームメンバーであっても他人は閲覧不可
        assertThat(resolver.canView(fileId, teamMemberUserId)).isFalse();

        // 非メンバーも閲覧不可
        assertThat(resolver.canView(fileId, nonMemberUserId)).isFalse();

        // SystemAdmin は閲覧可（SystemAdmin 高速パス）
        assertThat(resolver.canView(fileId, sysAdminUserId)).isTrue();
    }

    @Test
    @DisplayName("unknown_id_false — 不存在IDは誰に対しても false（IDOR 防止）")
    void unknown_id_false() {
        assertThat(resolver.canView(999_999L, teamMemberUserId)).isFalse();
        assertThat(contentVisibilityChecker.canView(
                ReferenceType.FILE_ATTACHMENT, 999_999L, teamMemberUserId)).isFalse();
        assertThat(contentVisibilityChecker.canView(
                ReferenceType.FILE_ATTACHMENT, 999_999L, null)).isFalse();
    }

    // =========================================================================
    // セットアップヘルパ
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
     * TEAM スコープのフォルダを作成する。
     */
    private Long insertTeamFolder(Long teamIdParam, Long createdBy) {
        em.createNativeQuery(
                "INSERT INTO shared_folders ("
                        + "scope_type, team_id, organization_id, user_id, "
                        + "name, version, created_by, created_at, updated_at) "
                        + "VALUES ('TEAM', :teamId, NULL, NULL, "
                        + "'テストフォルダ（TEAM）', 0, :createdBy, NOW(), NOW())")
                .setParameter("teamId", teamIdParam)
                .setParameter("createdBy", createdBy)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM shared_folders WHERE name = 'テストフォルダ（TEAM）'")
                .getSingleResult()).longValue();
    }

    /**
     * PERSONAL スコープのフォルダを作成する（user_id = 所有者）。
     */
    private Long insertPersonalFolder(Long ownerUserId) {
        em.createNativeQuery(
                "INSERT INTO shared_folders ("
                        + "scope_type, team_id, organization_id, user_id, "
                        + "name, version, created_by, created_at, updated_at) "
                        + "VALUES ('PERSONAL', NULL, NULL, :userId, "
                        + "'テストフォルダ（PERSONAL）', 0, :userId, NOW(), NOW())")
                .setParameter("userId", ownerUserId)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM shared_folders WHERE name = 'テストフォルダ（PERSONAL）'")
                .getSingleResult()).longValue();
    }

    /**
     * フォルダ内にファイルを作成する。
     */
    private Long insertSharedFile(Long folderId, Long createdBy) {
        em.createNativeQuery(
                "INSERT INTO shared_files ("
                        + "folder_id, name, file_key, file_size, content_type, "
                        + "current_version, version, created_by, created_at, updated_at) "
                        + "VALUES (:folderId, 'テスト.txt', 'test/key/file.txt', 100, 'text/plain', "
                        + "1, 0, :createdBy, NOW(), NOW())")
                .setParameter("folderId", folderId)
                .setParameter("createdBy", createdBy)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM shared_files WHERE folder_id = :folderId ORDER BY id DESC LIMIT 1")
                .setParameter("folderId", folderId)
                .getSingleResult()).longValue();
    }
}
