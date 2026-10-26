package com.mannschaft.app.role;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.role.entity.PermissionEntity;
import com.mannschaft.app.role.entity.PermissionGroupEntity;
import com.mannschaft.app.role.entity.PermissionGroupPermissionEntity;
import com.mannschaft.app.role.entity.RolePermissionEntity;
import com.mannschaft.app.role.entity.UserPermissionGroupEntity;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CMP-037 第一陣: コードが参照しているのにカタログ未登録だった権限 3 件
 * （{@code VIEW_ATTENDANCE} / {@code MANAGE_COMMITTEE} / {@code jobs.manage}）が
 * <b>実 Flyway スキーマの権限カタログに存在し、意図したロールにだけ付与されている</b>
 * ことを検証する契約テスト。
 *
 * <h2>なぜ専用クラス（Flyway 実スキーマ）が必要か</h2>
 * <p>本欠陥はユニットテストでは構造的に検出できない。各サービスの単体テストは
 * {@link AccessControlService} をモックするため、「渡した権限名がカタログに実在するか」は
 * 一切問われない。また通常の統合テスト基底 {@code AbstractMySqlIntegrationTest} は
 * {@code spring.flyway.enabled=false} / {@code ddl-auto=create} で動くため、
 * permissions / role_permissions は<b>空の表</b>として作られる。
 * つまり「マイグレーションが権限を登録し忘れている」という欠陥は、
 * 実 Flyway を適用した DB でしか観測できない。</p>
 *
 * <p>手本: {@code ManageRecruitmentsPermissionFlywayIT}。</p>
 *
 * <h2>検証内容</h2>
 * <ol>
 *   <li><b>カタログ実在</b>: 3 件が {@code permissions} に 1 行ずつ存在する。</li>
 *   <li><b>付与先の不変条件</b>: {@code role_permissions} の当該権限行は ADMIN のみ
 *       （{@code is_default=1}）。3 件はいずれも {@code checkPermission} / {@code hasPermission}
 *       経路であり、{@code RoleService.resolveEffectivePermissions} は {@code is_default} で
 *       絞らないため、DEPUTY_ADMIN へ「天井行」を足すと個別付与のない副管理者全員へ権限が渡る。</li>
 *   <li><b>肯定側と否定側の対</b>: ADMIN は判定を通り、DEPUTY_ADMIN（個別付与なし）／
 *       権限ロールなしの一般メンバー／非メンバーは 403（COMMON_002）。
 *       肯定側だけでは「判定が常に true」でも緑になるため対で置く。</li>
 * </ol>
 *
 * <p><b>CMP-047</b>: PR #2816 で是正した権限グループ系 native query 3 本を、
 * Entity 生成ではなく本クラスの Flyway 実スキーマ上で発行する。Docker 不在時は
 * silent skip せずクラス初期化を失敗させ、契約テストが走らない状態を成功扱いしない。</p>
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none"
})
// test プロファイル（Redis 無効化等）を読んだ上で flyway/ddl-auto のみ上書きする。
// 欠落すると default プロファイルで context が組まれ CI 起動不能になる。
@ActiveProfiles("test")
@Testcontainers
@Transactional
@DisplayName("権限カタログと native query の契約（Flyway 実スキーマ）")
class DeadPermissionCatalogFlywayIT {

    private static final String PERMISSION_VIEW_ATTENDANCE = "VIEW_ATTENDANCE";
    private static final String PERMISSION_MANAGE_COMMITTEE = "MANAGE_COMMITTEE";
    private static final String PERMISSION_JOBS_MANAGE = "jobs.manage";

    private static final String SCOPE_TYPE_TEAM = "TEAM";
    private static final String SCOPE_TYPE_ORGANIZATION = "ORGANIZATION";

    /** 検証用の架空スコープ ID（FK チェックを切って使うため実テーブル行は作らない）。 */
    private static final long TEAM_ID = 987_654_322L;
    private static final long ORGANIZATION_ID = 987_654_323L;

    private static final long ADMIN_USER_ID = 910_001L;
    private static final long DEPUTY_USER_ID = 910_002L;
    private static final long MEMBER_USER_ID = 910_003L;
    private static final long OUTSIDER_USER_ID = 910_004L;

    private static final long CMP047_MEMBER_USER_ID = 920_001L;
    private static final long CMP047_ORG_DEPUTY_USER_ID = 920_002L;
    private static final long CMP047_TEAM_DEPUTY_USER_ID = 920_003L;
    private static final long CMP047_ORGANIZATION_ID = 987_654_330L;
    private static final long CMP047_DEPUTY_ORGANIZATION_ID = 987_654_331L;
    private static final long CMP047_TEAM_ID = 987_654_332L;

    /** Flyway スキーマ適用用 MySQL コンテナ（tmpfs で WSL2 VHD 遅延を回避）。 */
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_dead_permission_flyway")
            .withUsername("test")
            .withPassword("test")
            .withTmpFs(Map.of("/var/lib/mysql", "rw"))
            .withCommand("--log_bin_trust_function_creators=1");

    static {
        MYSQL.start();
    }

    /** Redis は外部依存のためモック化。 */
    @MockitoBean
    org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private AccessControlService accessControlService;

    @Autowired
    private UserRoleRepository userRoleRepository;

    @PersistenceContext
    private EntityManager em;

    @Test
    @DisplayName("CMP-047: Flyway適用履歴とpermission_groupsのCHECK制約が実在する")
    void cmp047_flyway適用履歴とCheck制約が実在する() {
        Number migrationCount = (Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1")
                .getSingleResult();
        Number checkCount = (Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM information_schema.table_constraints "
                                + "WHERE constraint_schema = DATABASE() "
                                + "AND table_name = 'permission_groups' "
                                + "AND constraint_name = 'chk_permission_groups_scope' "
                                + "AND constraint_type = 'CHECK'")
                .getSingleResult();

        assertThat(migrationCount.longValue())
                .as("Hibernate生成ではなくFlywayが1件以上適用された実スキーマであること")
                .isPositive();
        assertThat(checkCount.longValue())
                .as("Entity生成では表現できないpermission_groupsのスコープ排他CHECKが実在すること")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("CMP-047: native queryで比較するENUMとVARCHARの照合順序が一致する")
    void cmp047_nativeQuery比較列の照合順序が一致する() {
        @SuppressWarnings("unchecked")
        List<String> collations = em.createNativeQuery(
                        "SELECT DISTINCT collation_name FROM information_schema.columns "
                                + "WHERE table_schema = DATABASE() AND ("
                                + "(table_name = 'memberships' AND column_name = 'role_kind') OR "
                                + "(table_name = 'roles' AND column_name = 'name') OR "
                                + "(table_name = 'permissions' AND column_name = 'name') OR "
                                + "(table_name = 'permission_groups' AND column_name = 'target_role')"
                                + ")")
                .getResultList();

        assertThat(collations)
                .as("3本のnative queryが比較するENUM/VARCHAR列は同一collationであること")
                .doesNotContainNull()
                .hasSize(1);
    }

    @Test
    @DisplayName("CMP-047: 組織権限保有者一覧queryがFlyway実スキーマでmemberships専属者を返す")
    void cmp047_組織権限保有者一覧QueryをFlyway実スキーマで実行する() {
        disableForeignKeyChecks();
        try {
            MembershipTestHelper.insertActiveUser(em, CMP047_MEMBER_USER_ID);
            MembershipTestHelper.insertMembership(em, CMP047_MEMBER_USER_ID,
                    ScopeType.ORGANIZATION, CMP047_ORGANIZATION_ID, RoleKind.MEMBER);
            Long permissionId = persistPermission("CMP047_ORG_LIST_PERMISSION");
            grantRolePermission("MEMBER", permissionId);
            flushClear();
        } finally {
            enableForeignKeyChecks();
        }

        assertThat(userRoleRepository.findUserIdsByOrganizationIdAndPermissionName(
                CMP047_ORGANIZATION_ID, "CMP047_ORG_LIST_PERMISSION"))
                .containsExactly(CMP047_MEMBER_USER_ID);
    }

    @Test
    @DisplayName("CMP-047: 組織DEPUTY_ADMIN判定queryがFlyway実スキーマで権限グループを評価する")
    void cmp047_組織DeputyAdmin判定QueryをFlyway実スキーマで実行する() {
        disableForeignKeyChecks();
        try {
            MembershipTestHelper.insertActiveUser(em, CMP047_ORG_DEPUTY_USER_ID);
            MembershipTestHelper.insertUserRole(em, CMP047_ORG_DEPUTY_USER_ID, "DEPUTY_ADMIN",
                    null, CMP047_DEPUTY_ORGANIZATION_ID);
            MembershipTestHelper.insertMembership(em, CMP047_ORG_DEPUTY_USER_ID,
                    ScopeType.ORGANIZATION, CMP047_DEPUTY_ORGANIZATION_ID, RoleKind.MEMBER);
            Long permissionId = persistPermission("CMP047_ORG_DEPUTY_PERMISSION");
            assignPermissionGroup(CMP047_ORG_DEPUTY_USER_ID, permissionId,
                    CMP047_DEPUTY_ORGANIZATION_ID, null);
            flushClear();
        } finally {
            enableForeignKeyChecks();
        }

        assertThat(userRoleRepository.existsDeputyAdminWithPermissionInOrganization(
                CMP047_ORG_DEPUTY_USER_ID, CMP047_DEPUTY_ORGANIZATION_ID,
                "CMP047_ORG_DEPUTY_PERMISSION"))
                .isTrue();
    }

    @Test
    @DisplayName("CMP-047: チーム通知宛先queryがFlyway実スキーマで権限グループを評価する")
    void cmp047_チーム通知宛先QueryをFlyway実スキーマで実行する() {
        disableForeignKeyChecks();
        try {
            MembershipTestHelper.insertActiveUser(em, CMP047_TEAM_DEPUTY_USER_ID);
            MembershipTestHelper.insertUserRole(em, CMP047_TEAM_DEPUTY_USER_ID, "DEPUTY_ADMIN",
                    CMP047_TEAM_ID, null);
            MembershipTestHelper.insertMembership(em, CMP047_TEAM_DEPUTY_USER_ID,
                    ScopeType.TEAM, CMP047_TEAM_ID, RoleKind.MEMBER);
            Long permissionId = persistPermission("CMP047_TEAM_DEPUTY_PERMISSION");
            assignPermissionGroup(CMP047_TEAM_DEPUTY_USER_ID, permissionId, null, CMP047_TEAM_ID);
            flushClear();
        } finally {
            enableForeignKeyChecks();
        }

        assertThat(userRoleRepository.findDeputyAdminUserIdsByTeamIdAndPermission(
                CMP047_TEAM_ID, "CMP047_TEAM_DEPUTY_PERMISSION"))
                .containsExactly(CMP047_TEAM_DEPUTY_USER_ID);
    }

    @Test
    @DisplayName("permissions カタログに 3 件が存在し、いずれも ADMIN のみ is_default=1 で保持する")
    void permissionsAreRegisteredInCatalogAndGrantedToAdminOnly() {
        assertRegisteredAndGrantedToAdminOnly(PERMISSION_VIEW_ATTENDANCE);
        assertRegisteredAndGrantedToAdminOnly(PERMISSION_MANAGE_COMMITTEE);
        assertRegisteredAndGrantedToAdminOnly(PERMISSION_JOBS_MANAGE);
    }

    @Test
    @DisplayName("VIEW_ATTENDANCE: チーム ADMIN は通り、DEPUTY_ADMIN/一般メンバー/非メンバーは 403")
    void viewAttendanceIsEffectiveForTeamAdminOnly() {
        prepareRoles();
        assertPermissionEffectiveForAdminOnly(TEAM_ID, SCOPE_TYPE_TEAM, PERMISSION_VIEW_ATTENDANCE);
    }

    @Test
    @DisplayName("jobs.manage: チーム ADMIN は通り、DEPUTY_ADMIN/一般メンバー/非メンバーは 403")
    void jobsManageIsEffectiveForTeamAdminOnly() {
        prepareRoles();
        assertPermissionEffectiveForAdminOnly(TEAM_ID, SCOPE_TYPE_TEAM, PERMISSION_JOBS_MANAGE);
    }

    @Test
    @DisplayName("MANAGE_COMMITTEE: 組織 ADMIN は通り、DEPUTY_ADMIN/一般メンバー/非メンバーは 403")
    void manageCommitteeIsEffectiveForOrganizationAdminOnly() {
        prepareRoles();
        assertPermissionEffectiveForAdminOnly(ORGANIZATION_ID, SCOPE_TYPE_ORGANIZATION, PERMISSION_MANAGE_COMMITTEE);
    }

    /**
     * 当該権限がカタログに 1 行だけ存在し、role_permissions の付与先が ADMIN 1 行
     * （{@code is_default=1}）のみであることを検証する。
     *
     * <p>マイグレーションから登録を落とすと 1 件目の assert が 0 件で FAIL し、
     * DEPUTY_ADMIN へ天井行を足すと 2 件目の assert が 2 行で FAIL する。</p>
     */
    private void assertRegisteredAndGrantedToAdminOnly(String permissionName) {
        @SuppressWarnings("unchecked")
        List<Object> permissionRows = em.createNativeQuery(
                        "SELECT id FROM permissions WHERE name = :name")
                .setParameter("name", permissionName)
                .getResultList();
        assertThat(permissionRows)
                .as("%s が permissions カタログに登録されていること"
                        + "（未登録だと権限判定が誰に対しても成立しない）", permissionName)
                .hasSize(1);

        @SuppressWarnings("unchecked")
        List<Object[]> grants = em.createNativeQuery(
                        "SELECT r.name, rp.is_default FROM role_permissions rp "
                                + "JOIN roles r ON r.id = rp.role_id "
                                + "JOIN permissions p ON p.id = rp.permission_id "
                                + "WHERE p.name = :name ORDER BY r.name")
                .setParameter("name", permissionName)
                .getResultList();

        assertThat(grants)
                .as("%s の role_permissions 付与先は ADMIN 1 行のみであること"
                        + "（hasPermission 経路は is_default で絞らないため、DEPUTY_ADMIN へ行を足すと"
                        + "個別付与なしの副管理者全員へ権限が渡る）", permissionName)
                .hasSize(1);
        assertThat((String) grants.get(0)[0]).isEqualTo("ADMIN");
        assertThat(toBool(grants.get(0)[1]))
                .as("%s の ADMIN への付与は is_default=1（自動付与）であること", permissionName)
                .isTrue();
    }

    /**
     * 肯定側と否定側の対。ADMIN は通り、それ以外は 403（COMMON_002）になること。
     */
    private void assertPermissionEffectiveForAdminOnly(long scopeId, String scopeType, String permissionName) {
        // 肯定側: ADMIN は role_permissions（is_default=1）経由で権限を保持する。
        assertThatCode(() -> accessControlService.checkPermission(
                ADMIN_USER_ID, scopeId, scopeType, permissionName))
                .as("%s の ADMIN は %s を保持し、認可経路を通れること", scopeType, permissionName)
                .doesNotThrowAnyException();

        // 否定側 1: DEPUTY_ADMIN は permission_groups での個別付与が無い限り通らない（委任は手動付与）。
        assertPermissionDenied(DEPUTY_USER_ID, scopeId, scopeType, permissionName, "個別付与のない DEPUTY_ADMIN");
        // 否定側 2: 権限ロールを持たない一般メンバーは通らない。
        assertPermissionDenied(MEMBER_USER_ID, scopeId, scopeType, permissionName, "権限ロールを持たない一般メンバー");
        // 否定側 3: 非メンバーは決して通らない。
        assertPermissionDenied(OUTSIDER_USER_ID, scopeId, scopeType, permissionName, "非メンバー");
    }

    private void assertPermissionDenied(long userId, long scopeId, String scopeType,
                                        String permissionName, String roleLabel) {
        assertThatThrownBy(() -> accessControlService.checkPermission(
                userId, scopeId, scopeType, permissionName))
                .as("%s は %s を保持せず拒否されること", roleLabel, permissionName)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.COMMON_002);
    }

    /**
     * 検証用のロール割当を作る。
     *
     * <p>FK 先（users / teams / organizations）は本テストの関心外のため、セッション限定で
     * FK チェックを外して user_roles だけを最小構成で組む。</p>
     *
     * <p>MEMBER_USER_ID / OUTSIDER_USER_ID には user_roles を与えない。F00.5（V60.010）以降、
     * MEMBER / SUPPORTER の所属は memberships 側が持ち user_roles からは削除済みであり、
     * 「user_roles に MEMBER 行がある」状態は本番では成立しないためである
     * （成立しえないフィクスチャを置くと死んだ機能が永久に緑になる）。
     * 権限解決 {@code RoleService.resolveEffectivePermissions} は user_roles しか見ないため、
     * 一般メンバーと非メンバーはこの経路では同じ「権限ロール無し」に落ちる。</p>
     */
    private void prepareRoles() {
        MembershipTestHelper.insertActiveUser(em, ADMIN_USER_ID);
        MembershipTestHelper.insertActiveUser(em, DEPUTY_USER_ID);
        MembershipTestHelper.insertActiveUser(em, MEMBER_USER_ID);
        MembershipTestHelper.insertActiveUser(em, OUTSIDER_USER_ID);
        em.createNativeQuery("SET FOREIGN_KEY_CHECKS = 0").executeUpdate();

        assignRole(ADMIN_USER_ID, "ADMIN", SCOPE_TYPE_TEAM, TEAM_ID);
        assignRole(DEPUTY_USER_ID, "DEPUTY_ADMIN", SCOPE_TYPE_TEAM, TEAM_ID);
        assignRole(ADMIN_USER_ID, "ADMIN", SCOPE_TYPE_ORGANIZATION, ORGANIZATION_ID);
        assignRole(DEPUTY_USER_ID, "DEPUTY_ADMIN", SCOPE_TYPE_ORGANIZATION, ORGANIZATION_ID);

        em.flush();
        em.clear();
    }

    /** 架空スコープに対して user_roles を 1 行作る（roles はマイグレーションのシード済みマスタから引く）。 */
    private void assignRole(long userId, String roleName, String scopeType, long scopeId) {
        String column = SCOPE_TYPE_TEAM.equals(scopeType) ? "team_id" : "organization_id";
        em.createNativeQuery(
                        "INSERT INTO user_roles (user_id, role_id, " + column + ", created_at, updated_at) "
                                + "SELECT :userId, r.id, :scopeId, NOW(), NOW() FROM roles r WHERE r.name = :roleName")
                .setParameter("userId", userId)
                .setParameter("scopeId", scopeId)
                .setParameter("roleName", roleName)
                .executeUpdate();
    }

    private Long persistPermission(String name) {
        PermissionEntity permission = PermissionEntity.builder()
                .name(name)
                .displayName(name)
                .scope(PermissionEntity.Scope.ORGANIZATION)
                .build();
        em.persist(permission);
        em.flush();
        return permission.getId();
    }

    private void grantRolePermission(String roleName, Long permissionId) {
        Number roleId = (Number) em.createNativeQuery("SELECT id FROM roles WHERE name = :name")
                .setParameter("name", roleName)
                .getSingleResult();
        em.persist(RolePermissionEntity.builder()
                .roleId(roleId.longValue())
                .permissionId(permissionId)
                .isDefault(true)
                .build());
    }

    private void assignPermissionGroup(Long userId, Long permissionId,
                                       Long organizationId, Long teamId) {
        PermissionGroupEntity group = PermissionGroupEntity.builder()
                .organizationId(organizationId)
                .teamId(teamId)
                .targetRole(PermissionGroupEntity.TargetRole.DEPUTY_ADMIN)
                .name("CMP047権限グループ" + userId)
                .build();
        em.persist(group);
        em.flush();
        em.persist(PermissionGroupPermissionEntity.builder()
                .groupId(group.getId())
                .permissionId(permissionId)
                .build());
        em.persist(UserPermissionGroupEntity.builder()
                .userId(userId)
                .groupId(group.getId())
                .build());
    }

    private void disableForeignKeyChecks() {
        em.createNativeQuery("SET FOREIGN_KEY_CHECKS = 0").executeUpdate();
    }

    private void enableForeignKeyChecks() {
        em.createNativeQuery("SET FOREIGN_KEY_CHECKS = 1").executeUpdate();
    }

    private void flushClear() {
        em.flush();
        em.clear();
    }

    /** MySQL Connector/J は TINYINT(1) を Boolean でも Number でも返しうるため両対応で正規化する。 */
    private static boolean toBool(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Number n) {
            return n.intValue() != 0;
        }
        throw new IllegalStateException("想定外の型: " + (v == null ? "null" : v.getClass()));
    }
}
