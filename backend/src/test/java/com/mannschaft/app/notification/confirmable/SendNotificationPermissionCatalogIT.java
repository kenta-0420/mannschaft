package com.mannschaft.app.notification.confirmable;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CMP-260909-1141 試練: 確認通知（F04.9）の {@code SEND_NOTIFICATION} 権限を、
 * <b>実 DB（Testcontainers）</b>で裏取りする受け入れテスト。
 *
 * <h2>なぜ実 DB か</h2>
 * <p>判定の実体は {@code UserRoleRepository} の native クエリであり、モックした UT は
 * 実スキーマとの契約を何も保証しない。また権限名の正本は Flyway の
 * {@code INSERT INTO permissions} だけで、カタログに無い名前を渡しても例外にはならず
 * 静かに「不成立」になる（{@code docs/security/README.md} §4.3）。</p>
 *
 * <h2>カタログ行の出所</h2>
 * <p>test profile は Flyway 無効・{@code ddl-auto: create} のため {@code permissions} は空表である。
 * テスト内で独自の権限行を捏造すると「本番で成立しえない行」を土台にした偽の緑になるため、
 * 本戦役の migration ファイル本文をそのまま実行して行を作る
 * （{@link #seedSendNotificationFromMigration()}）。したがって migration の内容が変われば
 * 本テストも同時に壊れる。</p>
 *
 * <h2>受け入れ条件</h2>
 * <ul>
 *   <li>AC-8 : migration は {@code SEND_NOTIFICATION} をカタログへ登録する</li>
 *   <li>AC-9 : migration は DEPUTY_ADMIN へ {@code is_default=1} で既定付与する（マスター裁可）</li>
 *   <li>AC-10: ADMIN は TEAM / ORGANIZATION の両方で許可される</li>
 *   <li>AC-11: 既定付与のまま（権限を明示付与していない）DEPUTY_ADMIN は TEAM / ORGANIZATION で許可される
 *              — 「画面は見えるが押すと 403」という退行を作らないことの証明</li>
 *   <li>AC-12: 既定付与を外した DEPUTY_ADMIN は COMMON_002（403）で拒否される
 *              — 権限判定が実際に効いていることの証明（これが無いと AC-11 は「常に true」と区別できない）</li>
 *   <li>AC-13: MEMBER は権限行があっても拒否される</li>
 *   <li>AC-14: 在籍していないスコープでは拒否される（IDOR）</li>
 * </ul>
 */
@Transactional
@DisplayName("CMP-260909-1141: SEND_NOTIFICATION のカタログ登録と既定付与（実DB）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class SendNotificationPermissionCatalogIT extends AbstractMySqlIntegrationTest {

    /** 本戦役の migration。ここから SEND_NOTIFICATION のカタログ行と既定付与行を作る。 */
    private static final String MIGRATION_RESOURCE =
            "db/migration/V216.20260918083734__add_send_notification_permission.sql";

    private static final String PERMISSION = "SEND_NOTIFICATION";
    private static final String TEAM = "TEAM";
    private static final String ORGANIZATION = "ORGANIZATION";

    private static final AtomicInteger SEQ = new AtomicInteger(0);

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private AccessControlService accessControlService;

    private Long teamId;
    private Long otherTeamId;
    private Long orgId;
    private Long otherOrgId;

    @BeforeEach
    void setUp() {
        seedRoles();
        seedSendNotificationFromMigration();
        teamId = insertTeam();
        otherTeamId = insertTeam();
        orgId = insertOrganization();
        otherOrgId = insertOrganization();
        em.flush();
        em.clear();
    }

    // =====================================================================
    // AC-8 / AC-9: migration の内容そのもの
    // =====================================================================

    @Test
    @DisplayName("AC-8: migration は SEND_NOTIFICATION を permissions カタログへ 1 件登録する")
    void ac8_カタログに登録される() {
        Number rows = (Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM permissions WHERE name = :name")
                .setParameter("name", PERMISSION)
                .getSingleResult();
        assertThat(rows.intValue())
                .as("権限名の正本は Flyway の INSERT INTO permissions のみである")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("AC-9: migration は DEPUTY_ADMIN へ is_default=1 で既定付与する")
    void ac9_deputyAdminへ既定付与される() {
        assertThat(countRolePermission("DEPUTY_ADMIN", 1))
                .as("認可判定は is_default=1 の行だけを実付与とみなすため、天井行（0）では意味を成さない")
                .isEqualTo(1);
        assertThat(countRolePermission("ADMIN", 1))
                .as("ADMIN にも設計事実として既定付与されること")
                .isEqualTo(1);
        assertThat(countRolePermission("MEMBER", 1) + countRolePermission("MEMBER", 0))
                .as("MEMBER には行を作らない（安全側設計）")
                .isZero();
    }

    // =====================================================================
    // AC-10 / AC-11 / AC-12
    // =====================================================================

    @Test
    @DisplayName("AC-10: ADMIN は TEAM / ORGANIZATION の両方で許可される")
    void ac10_adminは両スコープで許可される() {
        Long teamAdmin = insertUser();
        grantRole(teamAdmin, "ADMIN", teamId, null);
        Long orgAdmin = insertUser();
        grantRole(orgAdmin, "ADMIN", null, orgId);
        em.flush();
        em.clear();

        assertThatCode(() -> accessControlService
                .checkAdminOrHasPermissionInScope(teamAdmin, teamId, TEAM, PERMISSION))
                .doesNotThrowAnyException();
        assertThatCode(() -> accessControlService
                .checkAdminOrHasPermissionInScope(orgAdmin, orgId, ORGANIZATION, PERMISSION))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("AC-11: 既定付与のままの DEPUTY_ADMIN は TEAM / ORGANIZATION の両方で許可される")
    void ac11_既定付与のdeputyAdminは許可される() {
        Long teamDeputy = insertUser();
        grantRole(teamDeputy, "DEPUTY_ADMIN", teamId, null);
        Long orgDeputy = insertUser();
        grantRole(orgDeputy, "DEPUTY_ADMIN", null, orgId);
        em.flush();
        em.clear();

        // 権限グループ等による個別付与は一切していない。migration の is_default=1 行だけが根拠。
        assertThat(accessControlService.hasAdminOrPermissionInScope(teamDeputy, teamId, TEAM, PERMISSION))
                .as("既定付与により、いま送信できている副管理者を止めてはならない（TEAM）")
                .isTrue();
        assertThat(accessControlService
                .hasAdminOrPermissionInScope(orgDeputy, orgId, ORGANIZATION, PERMISSION))
                .as("既定付与により、いま送信できている副管理者を止めてはならない（ORGANIZATION）")
                .isTrue();
    }

    @Test
    @DisplayName("AC-12: 既定付与を外した DEPUTY_ADMIN は COMMON_002 で拒否される")
    void ac12_既定付与を外したdeputyAdminは拒否される() {
        Long teamDeputy = insertUser();
        grantRole(teamDeputy, "DEPUTY_ADMIN", teamId, null);
        Long orgDeputy = insertUser();
        grantRole(orgDeputy, "DEPUTY_ADMIN", null, orgId);
        // 運用で「副管理者から送信権限を外した」状態を表現する。
        revokeRolePermission("DEPUTY_ADMIN");
        em.flush();
        em.clear();

        assertThat(accessControlService.hasAdminOrPermissionInScope(teamDeputy, teamId, TEAM, PERMISSION))
                .as("権限を外された DEPUTY_ADMIN が通るなら、判定は効いていない")
                .isFalse();
        assertThatThrownBy(() -> accessControlService
                .checkAdminOrHasPermissionInScope(teamDeputy, teamId, TEAM, PERMISSION))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.COMMON_002);
        assertThat(accessControlService
                .hasAdminOrPermissionInScope(orgDeputy, orgId, ORGANIZATION, PERMISSION))
                .isFalse();
    }

    // =====================================================================
    // AC-13 / AC-14
    // =====================================================================

    @Test
    @DisplayName("AC-13: MEMBER は権限行を持っていても拒否される")
    void ac13_memberは拒否される() {
        grantRolePermission("MEMBER", true);
        Long member = insertUser();
        grantRole(member, "MEMBER", teamId, null);
        em.flush();
        em.clear();

        assertThat(accessControlService.hasAdminOrPermissionInScope(member, teamId, TEAM, PERMISSION))
                .as("判定は「DEPUTY_ADMIN であること」を条件に含むべきである")
                .isFalse();
    }

    @Test
    @DisplayName("AC-14: 在籍していない他チーム・他組織では拒否される（IDOR）")
    void ac14_他スコープでは拒否される() {
        Long teamDeputy = insertUser();
        grantRole(teamDeputy, "DEPUTY_ADMIN", teamId, null);
        Long orgDeputy = insertUser();
        grantRole(orgDeputy, "DEPUTY_ADMIN", null, orgId);
        em.flush();
        em.clear();

        assertThat(accessControlService
                .hasAdminOrPermissionInScope(teamDeputy, otherTeamId, TEAM, PERMISSION))
                .isFalse();
        assertThat(accessControlService
                .hasAdminOrPermissionInScope(orgDeputy, otherOrgId, ORGANIZATION, PERMISSION))
                .isFalse();
    }

    // =====================================================================
    // フィクスチャ
    // =====================================================================

    private int nextSeq() {
        return SEQ.incrementAndGet();
    }

    private long countRolePermission(String roleName, int isDefault) {
        Number rows = (Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM role_permissions rp "
                                + "JOIN roles r ON r.id = rp.role_id "
                                + "JOIN permissions p ON p.id = rp.permission_id "
                                + "WHERE r.name = :role AND p.name = :perm AND rp.is_default = :isDefault")
                .setParameter("role", roleName)
                .setParameter("perm", PERMISSION)
                .setParameter("isDefault", isDefault)
                .getSingleResult();
        return rows.longValue();
    }

    private void seedRoles() {
        insertRole("SYSTEM_ADMIN", 1);
        insertRole("ADMIN", 2);
        insertRole("DEPUTY_ADMIN", 3);
        insertRole("MEMBER", 4);
        insertRole("SUPPORTER", 5);
        insertRole("GUEST", 6);
        em.flush();
    }

    private void insertRole(String name, int priority) {
        em.createNativeQuery(
                        "INSERT IGNORE INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                                + "VALUES (:name, :name, :priority, 1, NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("priority", priority)
                .executeUpdate();
    }

    /**
     * 本戦役の migration 本文をそのまま実行して {@code SEND_NOTIFICATION} のカタログ行・既定付与行を作る。
     */
    private void seedSendNotificationFromMigration() {
        for (String sql : readMigrationStatements()) {
            em.createNativeQuery(sql).executeUpdate();
        }
        em.flush();
        // 自己検証: migration 由来で DEPUTY_ADMIN×SEND_NOTIFICATION の is_default=1 行が実在すること。
        // ここが 0 なら以降の全テストは「土台の無い緑/赤」であり、結果を信用してはならない。
        assertThat(countRolePermission("DEPUTY_ADMIN", 1))
                .as("フィクスチャの自己検証: migration 由来の DEPUTY_ADMIN×SEND_NOTIFICATION 行が入っていること")
                .isEqualTo(1);
    }

    private List<String> readMigrationStatements() {
        String raw;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(MIGRATION_RESOURCE)) {
            assertThat(in).as("migration ファイルが classpath 上に存在すること: " + MIGRATION_RESOURCE).isNotNull();
            raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("migration ファイルの読み出しに失敗した: " + MIGRATION_RESOURCE, e);
        }
        StringBuilder stripped = new StringBuilder();
        for (String line : raw.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                continue;
            }
            stripped.append(line).append('\n');
        }
        List<String> statements = new ArrayList<>();
        for (String part : stripped.toString().split(";")) {
            String sql = part.trim();
            if (!sql.isEmpty()) {
                statements.add(sql);
            }
        }
        return statements;
    }

    private Long insertUser() {
        int n = nextSeq();
        String email = "cmp2609091141-" + n + "@example.com";
        em.createNativeQuery(
                        "INSERT INTO users (email, last_name, first_name, display_name, status, "
                                + "is_searchable, handle_searchable, contact_approval_required, "
                                + "online_visibility, dm_receive_from, encryption_key_version, "
                                + "locale, timezone, reporting_restricted, follow_list_visibility, "
                                + "care_notification_enabled, offline_only, created_at, updated_at) "
                                + "VALUES (:email, '試験', :fn, :dn, 'ACTIVE', 1, 1, 1, "
                                + "'NOBODY', 'ANYONE', 1, 'ja', 'Asia/Tokyo', 0, 'PUBLIC', 1, 0, NOW(), NOW())")
                .setParameter("email", email)
                .setParameter("fn", "利用者" + n)
                .setParameter("dn", "試験 利用者" + n)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email).getSingleResult()).longValue();
    }

    private Long insertTeam() {
        String name = "F049チーム" + nextSeq();
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, 'PUBLIC', 1, 0, 0, CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), "
                                + "NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name).getSingleResult()).longValue();
    }

    private Long insertOrganization() {
        String name = "F049組織" + nextSeq();
        em.createNativeQuery(
                        "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                                + "supporter_enabled, version, slug, created_at, updated_at) "
                                + "VALUES (:name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, "
                                + "CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE name = :name")
                .setParameter("name", name).getSingleResult()).longValue();
    }

    private void grantRole(Long userId, String roleName, Long teamIdParam, Long orgIdParam) {
        em.createNativeQuery(
                        "INSERT INTO user_roles (user_id, role_id, team_id, organization_id, created_at, updated_at) "
                                + "SELECT :uid, r.id, :tid, :oid, NOW(), NOW() FROM roles r WHERE r.name = :role")
                .setParameter("uid", userId)
                .setParameter("tid", teamIdParam)
                .setParameter("oid", orgIdParam)
                .setParameter("role", roleName)
                .executeUpdate();
        if (teamIdParam != null) {
            insertActiveMembership(userId, TEAM, teamIdParam);
        } else if (orgIdParam != null) {
            insertActiveMembership(userId, ORGANIZATION, orgIdParam);
        }
    }

    private void insertActiveMembership(Long userId, String scopeType, Long scopeId) {
        em.createNativeQuery(
                        "INSERT INTO memberships (user_id, scope_type, scope_id, role_kind, joined_at, created_at, updated_at) "
                                + "SELECT :uid, :scopeType, :scopeId, 'MEMBER', NOW(), NOW(), NOW() "
                                + "WHERE NOT EXISTS (SELECT 1 FROM memberships m WHERE m.user_id = :uid "
                                + "AND m.scope_type = :scopeType AND m.scope_id = :scopeId AND m.left_at IS NULL)")
                .setParameter("uid", userId)
                .setParameter("scopeType", scopeType)
                .setParameter("scopeId", scopeId)
                .executeUpdate();
    }

    private void grantRolePermission(String roleName, boolean isDefault) {
        em.createNativeQuery(
                        "INSERT INTO role_permissions (role_id, permission_id, is_default, created_at) "
                                + "SELECT r.id, p.id, :isDefault, NOW() FROM roles r CROSS JOIN permissions p "
                                + "WHERE r.name = :role AND p.name = :perm "
                                + "AND NOT EXISTS (SELECT 1 FROM role_permissions rp "
                                + "                WHERE rp.role_id = r.id AND rp.permission_id = p.id)")
                .setParameter("role", roleName)
                .setParameter("perm", PERMISSION)
                .setParameter("isDefault", isDefault ? 1 : 0)
                .executeUpdate();
        em.flush();
    }

    /** 運用で「その役職から権限を外した」状態を作る。 */
    private void revokeRolePermission(String roleName) {
        em.createNativeQuery(
                        "DELETE rp FROM role_permissions rp "
                                + "JOIN roles r ON r.id = rp.role_id "
                                + "JOIN permissions p ON p.id = rp.permission_id "
                                + "WHERE r.name = :role AND p.name = :perm")
                .setParameter("role", roleName)
                .setParameter("perm", PERMISSION)
                .executeUpdate();
        em.flush();
    }
}
