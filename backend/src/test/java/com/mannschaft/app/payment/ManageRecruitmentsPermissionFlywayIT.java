package com.mannschaft.app.payment;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 支払い系（Connect onboarding・エスクロー返金など）が用いる権限
 * {@code MANAGE_RECRUITMENTS} が<b>実 Flyway スキーマの権限カタログに存在し、チーム管理者に効く</b>
 * ことを検証する契約テスト。
 *
 * <h2>なぜ専用クラス（Flyway 実スキーマ）が必要か</h2>
 * <p>本欠陥はユニットテストでは構造的に検出できない。
 * {@code ConnectChargeServiceTest} 等は {@link AccessControlService} をモックするため、
 * 「渡した権限名がカタログに実在するか」は一切問われない。
 * また通常の統合テスト基底 {@code AbstractMySqlIntegrationTest} は
 * {@code spring.flyway.enabled=false} / {@code ddl-auto=create} で動くため、
 * permissions / role_permissions は<b>空の表</b>として作られ、手で詰めたフィクスチャしか存在しない。
 * つまり「マイグレーションが権限を登録し忘れている」という欠陥は、
 * 実 Flyway を適用した DB でしか観測できない。</p>
 *
 * <p>そのため本クラスはクラス単位で {@code spring.flyway.enabled=true} /
 * {@code spring.jpa.hibernate.ddl-auto=none} を上書きし、実マイグレーションを適用した MySQL に対して
 * 実物の {@link AccessControlService} を走らせる。手本: {@code SharedFileLinkFlywayColumnIT}。</p>
 *
 * <h2>検証内容</h2>
 * <ol>
 *   <li><b>肯定側</b>: チーム ADMIN が {@code checkPermission(TEAM, MANAGE_RECRUITMENTS)} を通れる。</li>
 *   <li><b>否定側</b>: 同一チームの MEMBER / DEPUTY_ADMIN（個別付与なし）／非メンバーは 403（COMMON_002）。
 *       肯定側だけでは「判定が常に true」でも緑になってしまうため、対で置く。</li>
 *   <li><b>不変条件</b>: {@code role_permissions} の当該権限行は ADMIN のみ（{@code is_default=1}）。
 *       チーム経路の {@code RoleService.hasPermission} は {@code is_default} で絞らないため、
 *       DEPUTY_ADMIN へ行を足すと個別付与なしの副管理者全員へ権限が渡ってしまう。</li>
 * </ol>
 *
 * <p>Docker 未起動環境では {@code @EnabledIf} によりスキップされる。</p>
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
@EnabledIf("com.mannschaft.app.payment.ManageRecruitmentsPermissionFlywayIT#isDockerAvailable")
@DisplayName("MANAGE_RECRUITMENTS 権限カタログ登録とチーム管理者への実効性（Flyway 実スキーマ）")
class ManageRecruitmentsPermissionFlywayIT {

    private static final String PERMISSION_NAME = "MANAGE_RECRUITMENTS";
    private static final String SCOPE_TYPE_TEAM = "TEAM";

    /** 検証用の架空チーム ID（FK チェックを切って使うため実テーブル行は作らない）。 */
    private static final long TEAM_ID = 987_654_321L;
    private static final long ADMIN_USER_ID = 900_001L;
    private static final long DEPUTY_USER_ID = 900_002L;
    private static final long MEMBER_USER_ID = 900_003L;
    private static final long OUTSIDER_USER_ID = 900_004L;

    /** Flyway スキーマ適用用 MySQL コンテナ（tmpfs で WSL2 VHD 遅延を回避）。 */
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_permission_flyway")
            .withUsername("test")
            .withPassword("test")
            .withTmpFs(Map.of("/var/lib/mysql", "rw"))
            .withCommand("--log_bin_trust_function_creators=1");

    static {
        if (isDockerAvailable()) {
            MYSQL.start();
        }
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

    public static boolean isDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception e) {
            return false;
        }
    }

    @Autowired
    private AccessControlService accessControlService;

    @PersistenceContext
    private EntityManager em;

    /**
     * 権限カタログに {@code MANAGE_RECRUITMENTS} が実在し、ADMIN にだけ自動付与されていること。
     *
     * <p>マイグレーションから当該権限の登録を落とすと、1 件目の assert が 0 件で FAIL する
     * （＝この欠陥が二度と静かに戻らない）。</p>
     */
    @Test
    @DisplayName("permissions カタログに MANAGE_RECRUITMENTS が存在し ADMIN のみ is_default=1 で保持する")
    void permissionIsRegisteredInCatalogAndGrantedToAdminOnly() {
        @SuppressWarnings("unchecked")
        List<Object> permissionRows = em.createNativeQuery(
                        "SELECT id FROM permissions WHERE name = :name")
                .setParameter("name", PERMISSION_NAME)
                .getResultList();
        assertThat(permissionRows)
                .as("MANAGE_RECRUITMENTS が permissions カタログに登録されていること"
                        + "（未登録だとチーム経路の権限判定が誰に対しても成立しない）")
                .hasSize(1);

        @SuppressWarnings("unchecked")
        List<Object[]> grants = em.createNativeQuery(
                        "SELECT r.name, rp.is_default FROM role_permissions rp "
                                + "JOIN roles r ON r.id = rp.role_id "
                                + "JOIN permissions p ON p.id = rp.permission_id "
                                + "WHERE p.name = :name ORDER BY r.name")
                .setParameter("name", PERMISSION_NAME)
                .getResultList();

        assertThat(grants)
                .as("role_permissions の付与先は ADMIN 1 行のみであること"
                        + "（TEAM 経路の hasPermission は is_default で絞らないため、"
                        + "DEPUTY_ADMIN へ行を足すと個別付与なしの副管理者全員へ権限が渡る）")
                .hasSize(1);
        assertThat((String) grants.get(0)[0]).isEqualTo("ADMIN");
        assertThat(toBool(grants.get(0)[1]))
                .as("ADMIN への付与は is_default=1（自動付与・F03.11 §13）であること")
                .isTrue();
    }

    /**
     * 肯定側と否定側の対。チーム ADMIN は通り、それ以外は 403（COMMON_002）になること。
     *
     * <p>肯定側だけでは「判定が常に true」でも緑になるため、必ず否定側と対で検証する。</p>
     */
    @Test
    @DisplayName("チーム ADMIN は TEAM 受取の権限判定を通り、DEPUTY_ADMIN/MEMBER/非メンバーは 403 になる")
    void teamAdminPassesAndOthersAreRejected() {
        MembershipTestHelper.insertActiveUser(em, ADMIN_USER_ID);
        MembershipTestHelper.insertActiveUser(em, DEPUTY_USER_ID);
        MembershipTestHelper.insertActiveUser(em, MEMBER_USER_ID);
        MembershipTestHelper.insertActiveUser(em, OUTSIDER_USER_ID);
        // FK 先（users / teams）は本テストの関心外のため、セッション限定で FK チェックを外して
        // user_roles だけを最小構成で組む（SharedFileLinkFlywayColumnIT と同じ方針）。
        em.createNativeQuery("SET FOREIGN_KEY_CHECKS = 0").executeUpdate();

        assignTeamRole(ADMIN_USER_ID, "ADMIN");
        assignTeamRole(DEPUTY_USER_ID, "DEPUTY_ADMIN");
        // MEMBER_USER_ID / OUTSIDER_USER_ID には user_roles を与えない。
        // F00.5（V60.010）以降、MEMBER / SUPPORTER の所属は memberships 側が持ち user_roles から
        // 削除済みであるため、「user_roles に MEMBER 行がある」状態は本番では成立しない
        // （成立しえないフィクスチャを置くと死んだ機能が永久に緑になる）。
        // 権限解決 RoleService.resolveEffectivePermissions は user_roles しか見ないため、
        // 一般メンバーと非メンバーはこの経路では同じ「権限ロール無し」に落ちる。
        em.flush();
        em.clear();

        // 肯定側: ADMIN は role_permissions（is_default=1）経由で権限を保持する。
        assertThatCode(() -> accessControlService.checkPermission(
                ADMIN_USER_ID, TEAM_ID, SCOPE_TYPE_TEAM, PERMISSION_NAME))
                .as("チーム ADMIN は MANAGE_RECRUITMENTS を保持し、TEAM 受取の認可経路を通れること")
                .doesNotThrowAnyException();

        // 否定側 1: DEPUTY_ADMIN は permission_groups での個別付与が無い限り通らない（F03.11 §13「手動付与」）。
        assertPermissionDenied(DEPUTY_USER_ID, "個別付与のない DEPUTY_ADMIN");
        // 否定側 2: 権限ロールを持たない一般メンバーは通らない。
        assertPermissionDenied(MEMBER_USER_ID, "権限ロールを持たない一般メンバー");
        // 否定側 3: 非メンバーは決して通らない。
        assertPermissionDenied(OUTSIDER_USER_ID, "非メンバー");
    }

    /** 指定ユーザーが当該チームで MANAGE_RECRUITMENTS を持たず 403（COMMON_002）になることを検証する。 */
    private void assertPermissionDenied(long userId, String roleLabel) {
        assertThatThrownBy(() -> accessControlService.checkPermission(
                userId, TEAM_ID, SCOPE_TYPE_TEAM, PERMISSION_NAME))
                .as("%s は MANAGE_RECRUITMENTS を保持せず拒否されること", roleLabel)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.COMMON_002);
    }

    /** 架空チームに対して user_roles を 1 行作る（roles はマイグレーションのシード済みマスタから引く）。 */
    private void assignTeamRole(long userId, String roleName) {
        em.createNativeQuery(
                        "INSERT INTO user_roles (user_id, role_id, team_id, created_at, updated_at) "
                                + "SELECT :userId, r.id, :teamId, NOW(), NOW() FROM roles r WHERE r.name = :roleName")
                .setParameter("userId", userId)
                .setParameter("teamId", TEAM_ID)
                .setParameter("roleName", roleName)
                .executeUpdate();
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
