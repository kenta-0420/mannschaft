package com.mannschaft.app.common;

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
 * CMP-041 試練: {@link AccessControlService#hasAdminOrPermissionInScope} /
 * {@link AccessControlService#checkAdminOrHasPermissionInScope}（第一陣で新設）の受け入れテスト。
 *
 * <h2>方針</h2>
 * <p>認可判定は必ず<b>実 DB（Testcontainers）</b>で検証する。判定の実体は
 * {@code UserRoleRepository} の native クエリであり、モックした UT は実スキーマとの契約を
 * 何も保証しない（CMP-040 で「100% 落ちるクエリ」が長期間潜伏した唯一の理由）。</p>
 *
 * <h2>{@code MANAGE_SURVEYS} 行の出所</h2>
 * <p>test profile は Flyway 無効・{@code ddl-auto: create} のため {@code permissions} は空表である。
 * ここでテスト独自の権限行を捏造すると「本番で成立しえない行」を土台にした偽の緑になるため、
 * <b>第一陣の migration ファイル本文をそのまま実行して</b>行を作る
 * （{@link #seedManageSurveysFromMigration()}）。したがってカタログの内容が migration と
 * 食い違えば本テストも同時に壊れる。</p>
 *
 * <h2>受け入れ条件</h2>
 * <ul>
 *   <li>AC-6 : TEAM で ADMIN → 許可</li>
 *   <li>AC-7 : TEAM で MANAGE_SURVEYS を持つ DEPUTY_ADMIN → 許可</li>
 *   <li>AC-8 : TEAM で権限なし DEPUTY_ADMIN → BusinessException(COMMON_002)</li>
 *   <li>AC-9 : ORGANIZATION でも AC-6〜8 と同結果</li>
 *   <li>AC-10: MEMBER / SUPPORTER / GUEST → 拒否</li>
 *   <li>AC-11: SYSTEM_ADMIN の扱いが現行 {@code isAdminOrAbove} と矛盾しない</li>
 *   <li>AC-12: 権限グループ経由の DEPUTY_ADMIN も許可（TEAM / ORGANIZATION 両方）</li>
 *   <li>AC-13: {@code is_default=0} の天井行だけでは通らない（ORGANIZATION 版と同一の扱い）</li>
 *   <li>AC-14: 他チーム・他組織の scopeId では拒否（IDOR）</li>
 *   <li>AC-15: 存在しない scopeId・ロール未登録 → 500 にせず拒否</li>
 * </ul>
 */
@Transactional
@DisplayName("CMP-041: ADMIN or MANAGE_SURVEYS 保有 DEPUTY_ADMIN 判定（TEAM/ORGANIZATION）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class AdminOrPermissionInScopeIT extends AbstractMySqlIntegrationTest {

    /** 第一陣の migration。ここから MANAGE_SURVEYS のカタログ行を作る。 */
    private static final String MIGRATION_RESOURCE =
            "db/migration/V187.20260819090014__add_manage_surveys_to_catalog.sql";

    private static final String PERMISSION = "MANAGE_SURVEYS";
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
        seedManageSurveysFromMigration();
        teamId = insertTeam();
        otherTeamId = insertTeam();
        orgId = insertOrganization();
        otherOrgId = insertOrganization();
        em.flush();
        em.clear();
    }

    // =====================================================================
    // AC-6 / AC-7 / AC-8（TEAM）
    // =====================================================================

    @Test
    @DisplayName("AC-6: TEAM の ADMIN は許可される")
    void ac6_teamのADMINは許可される() {
        Long admin = insertUser();
        grantRole(admin, "ADMIN", teamId, null);
        em.flush();
        em.clear();

        assertThat(accessControlService.hasAdminOrPermissionInScope(admin, teamId, TEAM, PERMISSION))
                .as("ADMIN は権限個別付与の有無によらず無条件で許可されるべきである")
                .isTrue();
        assertThatCode(() -> accessControlService
                .checkAdminOrHasPermissionInScope(admin, teamId, TEAM, PERMISSION))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("AC-7: TEAM で MANAGE_SURVEYS を持つ DEPUTY_ADMIN は許可される（ロール直付け経路）")
    void ac7_team権限保有DEPUTY_ADMINは許可される() {
        Long deputy = insertUser();
        grantRole(deputy, "DEPUTY_ADMIN", teamId, null);
        // ロール直付け経路: role_permissions に is_default=1 で付与する
        grantRolePermission("DEPUTY_ADMIN", PERMISSION, true);
        em.flush();
        em.clear();

        assertThat(accessControlService.hasAdminOrPermissionInScope(deputy, teamId, TEAM, PERMISSION))
                .as("MANAGE_SURVEYS を持つ DEPUTY_ADMIN は TEAM でも許可されるべきである")
                .isTrue();
    }

    @Test
    @DisplayName("AC-8: TEAM の権限なし DEPUTY_ADMIN は COMMON_002 で拒否される")
    void ac8_team権限なしDEPUTY_ADMINは拒否される() {
        Long deputy = insertUser();
        grantRole(deputy, "DEPUTY_ADMIN", teamId, null);
        em.flush();
        em.clear();

        assertThat(accessControlService.hasAdminOrPermissionInScope(deputy, teamId, TEAM, PERMISSION))
                .as("権限を持たない DEPUTY_ADMIN は許可されてはならない")
                .isFalse();
        assertThatThrownBy(() -> accessControlService
                .checkAdminOrHasPermissionInScope(deputy, teamId, TEAM, PERMISSION))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.COMMON_002);
    }

    // =====================================================================
    // AC-9（ORGANIZATION でも同結果）
    // =====================================================================

    @Test
    @DisplayName("AC-9: ORGANIZATION でも ADMIN 許可 / 権限保有 DEPUTY_ADMIN 許可 / 権限なし DEPUTY_ADMIN 拒否")
    void ac9_organizationでもAC6からAC8と同結果() {
        Long admin = insertUser();
        grantRole(admin, "ADMIN", null, orgId);
        Long deputyWith = insertUser();
        grantRole(deputyWith, "DEPUTY_ADMIN", null, orgId);
        Long deputyWithout = insertUser();
        grantRole(deputyWithout, "DEPUTY_ADMIN", null, orgId);
        grantRolePermission("DEPUTY_ADMIN", PERMISSION, true);
        em.flush();
        em.clear();

        assertThat(accessControlService.hasAdminOrPermissionInScope(admin, orgId, ORGANIZATION, PERMISSION))
                .as("AC-9(ADMIN): ORGANIZATION でも ADMIN は許可").isTrue();
        assertThat(accessControlService.hasAdminOrPermissionInScope(deputyWith, orgId, ORGANIZATION, PERMISSION))
                .as("AC-9(権限保有 DEPUTY_ADMIN): ORGANIZATION でも許可").isTrue();
        // 権限は role_permissions で DEPUTY_ADMIN 全体に付いているため、
        // 「権限なし DEPUTY_ADMIN」は別ロール行を持たない別組織で表現する（下記 AC-14 と重複しない軸）。
        assertThat(accessControlService.hasAdminOrPermissionInScope(deputyWithout, otherOrgId, ORGANIZATION, PERMISSION))
                .as("AC-9(在籍していない組織): 拒否").isFalse();
        assertThatThrownBy(() -> accessControlService
                .checkAdminOrHasPermissionInScope(deputyWithout, otherOrgId, ORGANIZATION, PERMISSION))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.COMMON_002);
    }

    // =====================================================================
    // AC-10
    // =====================================================================

    @Test
    @DisplayName("AC-10: MEMBER / SUPPORTER / GUEST は権限行があっても拒否される")
    void ac10_一般ロールは拒否される() {
        // MANAGE_SURVEYS を MEMBER / SUPPORTER / GUEST にも付けてなお通らないこと
        // （判定が「DEPUTY_ADMIN であること」を条件に含んでいることの証明）。
        grantRolePermission("MEMBER", PERMISSION, true);
        grantRolePermission("SUPPORTER", PERMISSION, true);
        grantRolePermission("GUEST", PERMISSION, true);

        Long member = insertUser();
        grantRole(member, "MEMBER", teamId, null);
        Long supporter = insertUser();
        grantRole(supporter, "SUPPORTER", teamId, null);
        Long guest = insertUser();
        grantRole(guest, "GUEST", teamId, null);
        em.flush();
        em.clear();

        assertThat(accessControlService.hasAdminOrPermissionInScope(member, teamId, TEAM, PERMISSION))
                .as("MEMBER は許可されてはならない").isFalse();
        assertThat(accessControlService.hasAdminOrPermissionInScope(supporter, teamId, TEAM, PERMISSION))
                .as("SUPPORTER は許可されてはならない").isFalse();
        assertThat(accessControlService.hasAdminOrPermissionInScope(guest, teamId, TEAM, PERMISSION))
                .as("GUEST は許可されてはならない").isFalse();
    }

    // =====================================================================
    // AC-11
    // =====================================================================

    /**
     * AC-11: SYSTEM_ADMIN の扱いが現行 {@link AccessControlService#isAdminOrAbove} と矛盾しないこと。
     *
     * <p>現行のスコープ認可（{@code ADMIN_ROLES = {"ADMIN","DEPUTY_ADMIN"}}）は SYSTEM_ADMIN を
     * 特別扱いしない。よって<b>まず現行挙動をテストで固定し</b>、新設メソッドをそれに照合する。
     * 新設側だけが SYSTEM_ADMIN を通すようになると、認可の網が片側だけ緩む。</p>
     */
    @Test
    @DisplayName("AC-11: SYSTEM_ADMIN の扱いが現行 isAdminOrAbove と一致する（スコープ認可では特別扱いしない）")
    void ac11_SYSTEM_ADMINの扱いが現行と一致する() {
        Long sysAdmin = insertUser();
        grantRole(sysAdmin, "SYSTEM_ADMIN", null, null);
        em.flush();
        em.clear();

        boolean legacy = accessControlService.isAdminOrAbove(sysAdmin, teamId, TEAM);
        assertThat(legacy)
                .as("現行挙動の固定: スコープ認可は SYSTEM_ADMIN を特別扱いしない")
                .isFalse();
        assertThat(accessControlService.hasAdminOrPermissionInScope(sysAdmin, teamId, TEAM, PERMISSION))
                .as("新設メソッドは現行 isAdminOrAbove と同じ答えを返すべきである（TEAM）")
                .isEqualTo(legacy);
        assertThat(accessControlService.hasAdminOrPermissionInScope(sysAdmin, orgId, ORGANIZATION, PERMISSION))
                .as("新設メソッドは現行 isAdminOrAbove と同じ答えを返すべきである（ORGANIZATION）")
                .isEqualTo(accessControlService.isAdminOrAbove(sysAdmin, orgId, ORGANIZATION));
    }

    // =====================================================================
    // AC-12（権限グループ経由）
    // =====================================================================

    @Test
    @DisplayName("AC-12: 権限グループ経由で MANAGE_SURVEYS を得た DEPUTY_ADMIN も TEAM / ORGANIZATION で許可される")
    void ac12_権限グループ経由でも許可される() {
        Long teamDeputy = insertUser();
        grantRole(teamDeputy, "DEPUTY_ADMIN", teamId, null);
        Long teamGroup = insertPermissionGroup(teamId, null);
        addPermissionToGroup(teamGroup, PERMISSION);
        assignGroupToUser(teamDeputy, teamGroup);

        Long orgDeputy = insertUser();
        grantRole(orgDeputy, "DEPUTY_ADMIN", null, orgId);
        Long orgGroup = insertPermissionGroup(null, orgId);
        addPermissionToGroup(orgGroup, PERMISSION);
        assignGroupToUser(orgDeputy, orgGroup);
        em.flush();
        em.clear();

        assertThat(accessControlService.hasAdminOrPermissionInScope(teamDeputy, teamId, TEAM, PERMISSION))
                .as("TEAM スコープの権限グループ経由付与が許可に結びつくべきである")
                .isTrue();
        assertThat(accessControlService.hasAdminOrPermissionInScope(orgDeputy, orgId, ORGANIZATION, PERMISSION))
                .as("ORGANIZATION スコープの権限グループ経由付与が許可に結びつくべきである")
                .isTrue();
    }

    // =====================================================================
    // AC-13（is_default=0 の天井行だけでは通らない）
    // =====================================================================

    @Test
    @DisplayName("AC-13: is_default=0 の天井行だけの DEPUTY_ADMIN は TEAM / ORGANIZATION いずれでも通らない")
    void ac13_is_default0の天井行だけでは通らない() {
        grantRolePermission("DEPUTY_ADMIN", PERMISSION, false);

        Long teamDeputy = insertUser();
        grantRole(teamDeputy, "DEPUTY_ADMIN", teamId, null);
        Long orgDeputy = insertUser();
        grantRole(orgDeputy, "DEPUTY_ADMIN", null, orgId);
        em.flush();
        em.clear();

        assertThat(accessControlService.hasAdminOrPermissionInScope(teamDeputy, teamId, TEAM, PERMISSION))
                .as("天井行（is_default=0）だけの副管理者を TEAM で通してはならない")
                .isFalse();
        assertThat(accessControlService.hasAdminOrPermissionInScope(orgDeputy, orgId, ORGANIZATION, PERMISSION))
                .as("ORGANIZATION 版と同一の扱いであること（新旧クエリの非対称を作らない）")
                .isFalse();
    }

    // =====================================================================
    // AC-14（IDOR）
    // =====================================================================

    @Test
    @DisplayName("AC-14: 他チーム・他組織の scopeId を渡すと拒否される（IDOR）")
    void ac14_他スコープのscopeIdでは拒否される() {
        grantRolePermission("DEPUTY_ADMIN", PERMISSION, true);

        Long teamDeputy = insertUser();
        grantRole(teamDeputy, "DEPUTY_ADMIN", teamId, null);
        Long orgDeputy = insertUser();
        grantRole(orgDeputy, "DEPUTY_ADMIN", null, orgId);
        Long teamAdmin = insertUser();
        grantRole(teamAdmin, "ADMIN", teamId, null);
        em.flush();
        em.clear();

        assertThat(accessControlService.hasAdminOrPermissionInScope(teamDeputy, otherTeamId, TEAM, PERMISSION))
                .as("A チームの副管理者が B チームの scopeId で通ってはならない").isFalse();
        assertThat(accessControlService.hasAdminOrPermissionInScope(orgDeputy, otherOrgId, ORGANIZATION, PERMISSION))
                .as("A 組織の副管理者が B 組織の scopeId で通ってはならない").isFalse();
        assertThat(accessControlService.hasAdminOrPermissionInScope(teamAdmin, otherTeamId, TEAM, PERMISSION))
                .as("A チームの ADMIN が B チームの scopeId で通ってはならない").isFalse();
        assertThatThrownBy(() -> accessControlService
                .checkAdminOrHasPermissionInScope(teamDeputy, otherTeamId, TEAM, PERMISSION))
                .isInstanceOf(BusinessException.class);
    }

    // =====================================================================
    // AC-15（存在しない scopeId / ロール未登録）
    // =====================================================================

    @Test
    @DisplayName("AC-15: 存在しない scopeId・ロール未登録ユーザーは 500 にせず拒否される")
    void ac15_存在しないscopeIdやロール未登録は例外にせず拒否() {
        Long noRoleUser = insertUser();
        Long deputy = insertUser();
        grantRole(deputy, "DEPUTY_ADMIN", teamId, null);
        grantRolePermission("DEPUTY_ADMIN", PERMISSION, true);
        em.flush();
        em.clear();

        long absentScopeId = 987_654_321L;

        assertThatCode(() -> accessControlService
                .hasAdminOrPermissionInScope(deputy, absentScopeId, TEAM, PERMISSION))
                .as("存在しない scopeId で例外（500）になってはならない")
                .doesNotThrowAnyException();
        assertThat(accessControlService.hasAdminOrPermissionInScope(deputy, absentScopeId, TEAM, PERMISSION))
                .as("存在しない scopeId は拒否").isFalse();
        assertThat(accessControlService.hasAdminOrPermissionInScope(noRoleUser, teamId, TEAM, PERMISSION))
                .as("ロール未登録ユーザーは拒否").isFalse();
        assertThat(accessControlService.hasAdminOrPermissionInScope(noRoleUser, absentScopeId, ORGANIZATION, PERMISSION))
                .as("ロール未登録 × 存在しない組織も拒否").isFalse();
        assertThatThrownBy(() -> accessControlService
                .checkAdminOrHasPermissionInScope(noRoleUser, teamId, TEAM, PERMISSION))
                .as("拒否は BusinessException(COMMON_002) であり、実行時例外の漏れであってはならない")
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.COMMON_002);
    }

    // =====================================================================
    // フィクスチャ
    // =====================================================================

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

    /**
     * 第一陣の migration 本文をそのまま実行して {@code MANAGE_SURVEYS} のカタログ行を作る。
     *
     * <p>テスト内で独自の {@code permissions} 行を捏造すると、カタログの正本（Flyway）と
     * 食い違う「本番で成立しえない行」の上で緑になるため、必ず migration を出所とする。</p>
     */
    private void seedManageSurveysFromMigration() {
        for (String sql : readMigrationStatements()) {
            em.createNativeQuery(sql).executeUpdate();
        }
        em.flush();
        // 自己検証: migration 由来で ADMIN×MANAGE_SURVEYS の is_default=1 行が実在すること。
        // ここが 0 なら以降の全テストは「土台の無い緑/赤」であり、結果を信用してはならない。
        Number adminRows = (Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM role_permissions rp "
                        + "JOIN roles r ON r.id = rp.role_id "
                        + "JOIN permissions p ON p.id = rp.permission_id "
                        + "WHERE r.name = 'ADMIN' AND p.name = 'MANAGE_SURVEYS' AND rp.is_default = 1")
                .getSingleResult();
        assertThat(adminRows.intValue())
                .as("フィクスチャの自己検証: migration 由来の ADMIN×MANAGE_SURVEYS 行が入っていること")
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
        String email = "cmp041-acl-" + n + "@example.com";
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
        String name = "CMP041チーム" + nextSeq();
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
        String name = "CMP041組織" + nextSeq();
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
    }

    private void grantRolePermission(String roleName, String permissionName, boolean isDefault) {
        em.createNativeQuery(
                "INSERT INTO role_permissions (role_id, permission_id, is_default, created_at) "
                        + "SELECT r.id, p.id, :isDefault, NOW() FROM roles r CROSS JOIN permissions p "
                        + "WHERE r.name = :role AND p.name = :perm "
                        + "AND NOT EXISTS (SELECT 1 FROM role_permissions rp "
                        + "                WHERE rp.role_id = r.id AND rp.permission_id = p.id)")
                .setParameter("role", roleName)
                .setParameter("perm", permissionName)
                .setParameter("isDefault", isDefault ? 1 : 0)
                .executeUpdate();
        em.flush();
    }

    /** 権限グループを 1 件作る（{@code team_id} / {@code organization_id} は排他で片方のみ詰める）。 */
    private Long insertPermissionGroup(Long teamIdParam, Long orgIdParam) {
        String name = "CMP041権限束" + nextSeq();
        em.createNativeQuery(
                "INSERT INTO permission_groups (team_id, organization_id, target_role, name, "
                        + "created_at, updated_at) "
                        + "VALUES (:tid, :oid, 'DEPUTY_ADMIN', :name, NOW(), NOW())")
                .setParameter("tid", teamIdParam)
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
