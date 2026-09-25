package com.mannschaft.app.member.service;

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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PR #3387 検分（4巡目・P1）: V225 seed（{@code MEMBER_SUBTAB_VISIBILITY_MANAGE}）の
 * DEPUTY_ADMIN 天井行（{@code is_default=0}）が、個別付与なしの DEPUTY_ADMIN に
 * 実際に権限を漏らしていないかを実 DB（Testcontainers）で検証する。
 *
 * <p><b>検分指摘</b>: 「判定経路は {@code is_default} を無視して {@code role_permissions} の全行を
 * 有効な権限として扱うため、個別付与されていない DEPUTY_ADMIN でも
 * {@code checkPermission} を通ってしまう」との指摘（P1）。</p>
 *
 * <p><b>実コード確認結果</b>: {@code MemberSubtabVisibilityService#checkUpdatePermission} は
 * {@link AccessControlService#checkPermission} → {@code RoleService#hasPermission} →
 * {@code RoleService#resolveEffectivePermissions} を経由する。この経路の DEPUTY_ADMIN 分岐
 * （{@code RoleService.java} 647-649 行）は {@code role_permissions} 由来の {@code rolePermissions}
 * を一切参照せず、権限グループ由来の {@code groupPermissions} のみを返す（{@code is_default} の値に
 * 関わらず、DEPUTY_ADMIN は role_permissions 経由での自動付与を一切受けない設計）。
 * よって V225 の天井行（is_default=0）の存在自体は、この判定経路に対して無害である。
 * 本テストはこれを実 DB で裏取りする（推測ではなく実測。CLAUDE.md「経験的検出が一次」）。</p>
 */
@Transactional
@DisplayName("PR #3387 検分4巡目 P1: MEMBER_SUBTAB_VISIBILITY_MANAGE 天井行(is_default=0)の実害有無")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class MemberSubtabVisibilityPermissionSeedIT extends AbstractMySqlIntegrationTest {

    private static final String MIGRATION_RESOURCE =
            "db/migration/V225.20260926162744__seed_member_subtab_visibility_permission.sql";
    private static final String PERMISSION = "MEMBER_SUBTAB_VISIBILITY_MANAGE";
    private static final String ORGANIZATION = "ORGANIZATION";

    private static final AtomicInteger SEQ = new AtomicInteger(0);

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private AccessControlService accessControlService;

    private Long orgId;

    @BeforeEach
    void setUp() {
        seedRoles();
        seedPermissionFromMigration();
        orgId = insertOrganization();
        em.flush();
        em.clear();
    }

    @Test
    @DisplayName("AC-1(red相当の裏取り): 個別付与のない DEPUTY_ADMIN は天井行だけでは checkPermission を通らない")
    void 個別付与のないDEPUTY_ADMINは拒否される() {
        Long deputy = insertUser();
        grantRole(deputy, "DEPUTY_ADMIN", orgId);
        em.flush();
        em.clear();

        // 自己検証: V225 由来の DEPUTY_ADMIN 天井行(is_default=0)が実在すること
        Number ceilingRows = (Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM role_permissions rp "
                        + "JOIN roles r ON r.id = rp.role_id "
                        + "JOIN permissions p ON p.id = rp.permission_id "
                        + "WHERE r.name = 'DEPUTY_ADMIN' AND p.name = :perm AND rp.is_default = 0")
                .setParameter("perm", PERMISSION)
                .getSingleResult();
        assertThat(ceilingRows.intValue())
                .as("フィクスチャの自己検証: migration 由来の DEPUTY_ADMIN 天井行が入っていること")
                .isEqualTo(1);

        assertThat(accessControlService.hasPermission(deputy, orgId, ORGANIZATION, PERMISSION))
                .as("個別付与（permission_groups）のない DEPUTY_ADMIN は天井行だけで許可されてはならない")
                .isFalse();
        assertThatThrownBy(() -> accessControlService.checkPermission(deputy, orgId, ORGANIZATION, PERMISSION))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.COMMON_002);
    }

    @Test
    @DisplayName("AC-2: permission_groups 経由で個別付与された DEPUTY_ADMIN は許可される")
    void 権限グループ経由で個別付与されたDEPUTY_ADMINは許可される() {
        Long deputy = insertUser();
        grantRole(deputy, "DEPUTY_ADMIN", orgId);
        Long groupId = insertPermissionGroup(orgId);
        addPermissionToGroup(groupId, PERMISSION);
        assignGroupToUser(deputy, groupId);
        em.flush();
        em.clear();

        assertThat(accessControlService.hasPermission(deputy, orgId, ORGANIZATION, PERMISSION))
                .as("permission_groups 経由で個別付与された DEPUTY_ADMIN は許可されるべきである")
                .isTrue();
    }

    @Test
    @DisplayName("AC-3: ADMIN は is_default=1 のロール直付けにより無条件で許可される")
    void ADMINはロール直付けにより許可される() {
        Long admin = insertUser();
        grantRole(admin, "ADMIN", orgId);
        em.flush();
        em.clear();

        assertThat(accessControlService.hasPermission(admin, orgId, ORGANIZATION, PERMISSION)).isTrue();
    }

    // ── フィクスチャ ─────────────────────────────

    private int nextSeq() {
        return SEQ.incrementAndGet();
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

    private void seedPermissionFromMigration() {
        for (String sql : readMigrationStatements()) {
            em.createNativeQuery(sql).executeUpdate();
        }
        em.flush();
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
        String email = "cmp3387-seed-" + n + "@example.com";
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

    private Long insertOrganization() {
        String name = "CMP3387組織" + nextSeq();
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

    private void grantRole(Long userId, String roleName, Long orgIdParam) {
        em.createNativeQuery(
                "INSERT INTO user_roles (user_id, role_id, team_id, organization_id, created_at, updated_at) "
                        + "SELECT :uid, r.id, NULL, :oid, NOW(), NOW() FROM roles r WHERE r.name = :role")
                .setParameter("uid", userId)
                .setParameter("oid", orgIdParam)
                .setParameter("role", roleName)
                .executeUpdate();
        em.createNativeQuery(
                "INSERT INTO memberships (user_id, scope_type, scope_id, role_kind, joined_at, created_at, updated_at) "
                        + "SELECT :uid, 'ORGANIZATION', :oid, 'MEMBER', NOW(), NOW(), NOW() "
                        + "WHERE NOT EXISTS (SELECT 1 FROM memberships m WHERE m.user_id = :uid "
                        + "AND m.scope_type = 'ORGANIZATION' AND m.scope_id = :oid AND m.left_at IS NULL)")
                .setParameter("uid", userId)
                .setParameter("oid", orgIdParam)
                .executeUpdate();
    }

    private Long insertPermissionGroup(Long orgIdParam) {
        String name = "CMP3387権限束" + nextSeq();
        em.createNativeQuery(
                "INSERT INTO permission_groups (team_id, organization_id, target_role, name, "
                        + "created_at, updated_at) "
                        + "VALUES (NULL, :oid, 'DEPUTY_ADMIN', :name, NOW(), NOW())")
                .setParameter("oid", orgIdParam)
                .setParameter("name", name)
                .executeUpdate();
        em.flush();
        return ((Number) em.createNativeQuery("SELECT id FROM permission_groups WHERE name = :name")
                .setParameter("name", name).getSingleResult()).longValue();
    }

    private void addPermissionToGroup(Long groupId, String permissionName) {
        em.createNativeQuery(
                "INSERT INTO permission_group_permissions (group_id, permission_id, created_at) "
                        + "SELECT :gid, p.id, NOW() FROM permissions p WHERE p.name = :perm")
                .setParameter("gid", groupId)
                .setParameter("perm", permissionName)
                .executeUpdate();
        em.flush();
    }

    private void assignGroupToUser(Long userId, Long groupId) {
        em.createNativeQuery(
                "INSERT INTO user_permission_groups (user_id, group_id, created_at) "
                        + "VALUES (:uid, :gid, NOW())")
                .setParameter("uid", userId)
                .setParameter("gid", groupId)
                .executeUpdate();
        em.flush();
    }
}
