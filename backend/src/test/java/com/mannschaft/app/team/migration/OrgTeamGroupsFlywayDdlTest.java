package com.mannschaft.app.team.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F01.2.1 1-A（試練・red）: チーム加盟の双方向化とチームグループの Flyway DDL を、
 * 実 MySQL（Testcontainers）に対して検証する。モックなし・実 Flyway。
 *
 * <p>対応 AC: M01（既存行の移行）・M03（fresh から全 migration が通る）・
 * G135 の DDL 側（一意制約・CHECK・既定値）。設計書 F01.2.1 §5.2〜§5.5・§12。</p>
 *
 * <p>通常の IT は ddl-auto=create で Flyway を走らせないため、本クラスが DDL の唯一の担保になる。
 * 「baseline までを適用 → 旧形式のデータを投入 → 残りを適用」で、既存データ移行を本物の順序で測る。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIf("com.mannschaft.app.team.migration.OrgTeamGroupsFlywayDdlTest#isDockerAvailable")
@DisplayName("F01.2.1 1-A Flyway DDL（既存データ移行・一意制約・CHECK・既定値）")
class OrgTeamGroupsFlywayDdlTest {

    /**
     * F01.2.1 の migration を含まない最後の Flyway バージョン（2026-09-29 時点の origin/main の最大）。
     * これ以降に足された migration が「新DDL」とみなされる。他部隊の migration が後から入っても、
     * この値より新しい版は本クラスの対象（新DDL）側に入るだけで、M01 の判定は変わらない。
     */
    private static final String BASELINE_VERSION = "227.20260929040840";

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_org_groups_ddl")
            .withUsername("test")
            .withPassword("test")
            .withTmpFs(Map.of("/var/lib/mysql", "rw"))
            .withCommand("--log_bin_trust_function_creators=1");

    public static boolean isDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception e) {
            return false;
        }
    }

    @BeforeAll
    void startContainer() {
        MYSQL.start();
    }

    @AfterAll
    void stopContainer() {
        MYSQL.stop();
    }

    // ------------------------------------------------------------------
    // M01 / M03: 既存データを持つ DB に新 DDL を適用する
    // ------------------------------------------------------------------

    @Test
    @Order(1)
    @DisplayName("M01/M03: baseline 適用後の旧データが、新 DDL 適用で direction=ORG_INVITE・group_id NULL・message NULL になる")
    void M01_既存行は_ORG_INVITE_かつ_group_id_NULL_に移行される() throws Exception {
        // given: baseline までの migration だけを適用し、旧形式（direction 列なし）の加盟行を投入する
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .target(BASELINE_VERSION)
                .load()
                .migrate();
        try (Connection c = conn(); Statement st = c.createStatement()) {
            st.execute("SET FOREIGN_KEY_CHECKS=0");
            st.execute("INSERT INTO team_org_memberships "
                    + "(team_id, organization_id, status, invited_at, created_at) "
                    + "VALUES (9001, 8001, 'PENDING', '2026-01-01 00:00:00', '2026-01-01 00:00:00')");
            st.execute("INSERT INTO team_org_memberships "
                    + "(team_id, organization_id, status, invited_at, responded_at, created_at) "
                    + "VALUES (9002, 8001, 'ACTIVE', '2026-01-01 00:00:00', '2026-01-02 00:00:00', '2026-01-01 00:00:00')");
        }

        // when: 残り（新 DDL）を全て適用する
        MigrateResult result = migrateAll();

        // then: 新 DDL の migration が少なくとも4本（順1〜4）適用されている
        assertThat(result.success).as("残りの migration が全て成功すること").isTrue();
        assertThat(result.migrationsExecuted)
                .as("F01.2.1 順1〜4（organizations 4列・org_team_groups・team_org_memberships 列と索引・restrictions）")
                .isGreaterThanOrEqualTo(4);

        // then: 既存2行は ORG_INVITE / group_id NULL / message NULL / updated_at 非NULL
        try (Connection c = conn(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT team_id, direction, group_id, message, updated_at "
                             + "FROM team_org_memberships WHERE organization_id = 8001 ORDER BY team_id")) {
            int rows = 0;
            while (rs.next()) {
                rows++;
                assertThat(rs.getString("direction")).as("既存行 team=%d の direction", rs.getLong("team_id"))
                        .isEqualTo("ORG_INVITE");
                assertThat(rs.getBytes("group_id")).as("既存行の group_id は未分類(NULL)").isNull();
                assertThat(rs.getString("message")).as("既存行の message は NULL").isNull();
                assertThat(rs.getTimestamp("updated_at")).as("updated_at は移行時刻が入る").isNotNull();
            }
            assertThat(rows).as("既存行が失われていない").isEqualTo(2);
        }
    }

    @Test
    @Order(2)
    @DisplayName("M03: fresh 状態から全 migration が out-of-order 無効で成功する（新 DDL 込み・冪等）")
    void M03_全migrationが成功し_新テーブルが存在する() throws Exception {
        MigrateResult result = migrateAll();
        assertThat(result.success).isTrue();

        assertThat(tableExists("org_team_groups")).as("org_team_groups が存在する").isTrue();
        assertThat(tableExists("team_org_affiliation_restrictions"))
                .as("team_org_affiliation_restrictions が存在する").isTrue();
    }

    // ------------------------------------------------------------------
    // organizations の設定4列（§5.5）
    // ------------------------------------------------------------------

    @Test
    @Order(3)
    @DisplayName("organizations の4列: 既定値・NULL可否・group_mode の CHECK")
    void organizations_設定4列の既定値とCHECK() throws Exception {
        migrateAll();

        assertColumn("organizations", "team_application_enabled", "tinyint(1)", "NO", "0");
        assertColumn("organizations", "team_groups_enabled", "tinyint(1)", "NO", "0");
        assertColumn("organizations", "team_application_group_mode", "varchar(10)", "NO", "OFF");
        assertColumn("organizations", "team_application_guidance", "varchar(500)", "YES", null);

        assertThat(checkConstraintNames("organizations"))
                .as("group_mode の CHECK 制約")
                .contains("chk_organizations_team_app_group_mode");
    }

    // ------------------------------------------------------------------
    // org_team_groups（§5.2）
    // ------------------------------------------------------------------

    @Test
    @Order(4)
    @DisplayName("org_team_groups: 列定義・索引が設計書どおり")
    void org_team_groups_列と索引() throws Exception {
        migrateAll();

        assertColumn("org_team_groups", "id", "binary(16)", "NO", null);
        assertColumn("org_team_groups", "organization_id", "bigint unsigned", "NO", null);
        assertColumn("org_team_groups", "name", "varchar(50)", "NO", null);
        assertColumn("org_team_groups", "description", "varchar(200)", "YES", null);
        assertColumn("org_team_groups", "sort_order", "int", "NO", null);
        assertColumn("org_team_groups", "deleted_at", "datetime", "YES", null);
        assertThat(columnNames("org_team_groups")).contains("active_name", "created_by", "updated_by",
                "created_at", "updated_at");

        assertThat(indexNames("org_team_groups"))
                .contains("uq_org_team_groups_org_active_name", "idx_org_team_groups_org_sort");
        assertThat(isUniqueIndex("org_team_groups", "uq_org_team_groups_org_active_name")).isTrue();
    }

    @Test
    @Order(5)
    @DisplayName("G135(DDL): 同じ組織で生存グループが同名だと一意制約違反")
    void org_team_groups_同一組織の同名生存グループは一意制約違反() throws Exception {
        migrateAll();
        try (Connection c = conn()) {
            insertGroup(c, 7101L, "平成20年度卒", null);

            assertThatThrownBy(() -> insertGroup(c, 7101L, "平成20年度卒", null))
                    .as("同組織・同名の生存グループは重複不可")
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("Duplicate entry");
        }
    }

    @Test
    @Order(6)
    @DisplayName("別の組織なら同名グループを作れる")
    void org_team_groups_別組織なら同名可() throws Exception {
        migrateAll();
        try (Connection c = conn()) {
            insertGroup(c, 7201L, "同名グループ", null);
            insertGroup(c, 7202L, "同名グループ", null);
        }
        assertThat(countGroups(7201L, "同名グループ")).isEqualTo(1);
        assertThat(countGroups(7202L, "同名グループ")).isEqualTo(1);
    }

    @Test
    @Order(7)
    @DisplayName("論理削除したグループの名前は再利用できる。生成列 active_name は削除で NULL になる")
    void org_team_groups_論理削除後は名前を再利用できる() throws Exception {
        migrateAll();
        try (Connection c = conn()) {
            insertGroup(c, 7301L, "再利用名", null);
            assertThat(activeName(c, 7301L, "再利用名")).as("生存中は active_name = name").isEqualTo("再利用名");

            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE org_team_groups SET deleted_at = NOW() WHERE organization_id = ? AND name = ?")) {
                ps.setLong(1, 7301L);
                ps.setString(2, "再利用名");
                ps.executeUpdate();
            }
            assertThat(activeName(c, 7301L, "再利用名")).as("削除済みは active_name = NULL").isNull();

            // 削除済みと同名の新規作成は成功する
            insertGroup(c, 7301L, "再利用名", null);
            // 削除済みどうしが複数あっても一意制約に当たらない
            insertGroup(c, 7301L, "再利用名2", "2026-01-01 00:00:00");
            insertGroup(c, 7301L, "再利用名2", "2026-01-02 00:00:00");
        }
        assertThat(countGroups(7301L, "再利用名")).as("削除済み1 + 生存1").isEqualTo(2);
    }

    // ------------------------------------------------------------------
    // team_org_memberships の列・索引・CHECK（§5.3）
    // ------------------------------------------------------------------

    @Test
    @Order(8)
    @DisplayName("team_org_memberships: 追加4列の型・既定値・NULL可否、索引4本、direction の CHECK")
    void team_org_memberships_追加列と索引() throws Exception {
        migrateAll();

        assertColumn("team_org_memberships", "direction", "varchar(20)", "NO", "ORG_INVITE");
        assertColumn("team_org_memberships", "group_id", "binary(16)", "YES", null);
        assertColumn("team_org_memberships", "message", "varchar(500)", "YES", null);
        assertColumn("team_org_memberships", "updated_at", "datetime", "NO", "CURRENT_TIMESTAMP");

        assertThat(indexNames("team_org_memberships")).contains(
                "idx_team_org_memberships_org_status_dir",
                "idx_team_org_memberships_team_status_dir",
                "idx_team_org_memberships_org_group_status",
                "idx_team_org_memberships_status_invited");
        assertThat(checkConstraintNames("team_org_memberships"))
                .contains("chk_team_org_memberships_direction", "chk_team_org_memberships_status");
    }

    @Test
    @Order(9)
    @DisplayName("direction を省略して INSERT すると ORG_INVITE、不正値は CHECK 違反")
    void team_org_memberships_directionの既定値とCHECK() throws Exception {
        migrateAll();
        try (Connection c = conn(); Statement st = c.createStatement()) {
            st.execute("SET FOREIGN_KEY_CHECKS=0");
            st.execute("INSERT INTO team_org_memberships (team_id, organization_id, status, invited_at, created_at) "
                    + "VALUES (9101, 8101, 'PENDING', NOW(), NOW())");
            try (ResultSet rs = st.executeQuery(
                    "SELECT direction FROM team_org_memberships WHERE team_id = 9101 AND organization_id = 8101")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("ORG_INVITE");
            }
            assertThatThrownBy(() -> st.execute(
                    "INSERT INTO team_org_memberships (team_id, organization_id, status, direction, invited_at, created_at) "
                            + "VALUES (9102, 8101, 'PENDING', 'BOTH', NOW(), NOW())"))
                    .as("direction は ORG_INVITE / TEAM_APPLY のみ")
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("chk_team_org_memberships_direction");
        }
    }

    @Test
    @Order(10)
    @DisplayName("direction × status の4通りを INSERT できる。同じ (team, org) は向きが違っても UNIQUE 違反")
    void team_org_memberships_directionとstatusの組み合わせ() throws Exception {
        migrateAll();
        try (Connection c = conn(); Statement st = c.createStatement()) {
            st.execute("SET FOREIGN_KEY_CHECKS=0");
            long team = 9200L;
            for (String direction : new String[] {"ORG_INVITE", "TEAM_APPLY"}) {
                for (String status : new String[] {"PENDING", "ACTIVE"}) {
                    team++;
                    st.execute("INSERT INTO team_org_memberships "
                            + "(team_id, organization_id, status, direction, invited_at, created_at) VALUES ("
                            + team + ", 8201, '" + status + "', '" + direction + "', NOW(), NOW())");
                }
            }
            assertThatThrownBy(() -> st.execute(
                    "INSERT INTO team_org_memberships (team_id, organization_id, status, direction, invited_at, created_at) "
                            + "VALUES (9201, 8201, 'PENDING', 'TEAM_APPLY', NOW(), NOW())"))
                    .as("(team_id, organization_id) の UNIQUE は direction をまたいで効く")
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("Duplicate entry");
            assertThatThrownBy(() -> st.execute(
                    "INSERT INTO team_org_memberships (team_id, organization_id, status, direction, invited_at, created_at) "
                            + "VALUES (9299, 8201, 'REJECTED', 'TEAM_APPLY', NOW(), NOW())"))
                    .as("status は PENDING / ACTIVE の2値のまま（§4.2）")
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("chk_team_org_memberships_status");
        }
    }

    // ------------------------------------------------------------------
    // team_org_affiliation_restrictions（§5.4）
    // ------------------------------------------------------------------

    @Test
    @Order(11)
    @DisplayName("restrictions: 索引・一意制約・CHECK が設計書どおり")
    void restrictions_索引と制約() throws Exception {
        migrateAll();

        assertThat(indexNames("team_org_affiliation_restrictions"))
                .contains("uq_toar_org_team_dir", "idx_toar_team_dir", "idx_toar_kind_until");
        assertThat(isUniqueIndex("team_org_affiliation_restrictions", "uq_toar_org_team_dir")).isTrue();
        assertThat(checkConstraintNames("team_org_affiliation_restrictions")).contains(
                "chk_toar_direction", "chk_toar_kind", "chk_toar_reason", "chk_toar_until");
        assertColumn("team_org_affiliation_restrictions", "id", "binary(16)", "NO", null);
        assertColumn("team_org_affiliation_restrictions", "restricted_until", "datetime", "YES", null);
    }

    @Test
    @Order(12)
    @DisplayName("G135(DDL): 制限は (organization, team, direction) で一意。向きが違えば別行")
    void restrictions_一意制約() throws Exception {
        migrateAll();
        try (Connection c = conn()) {
            insertRestriction(c, 6001L, 5001L, "TEAM_APPLY", "BLOCK", "REJECTED", null);
            insertRestriction(c, 6001L, 5001L, "ORG_INVITE", "BLOCK", "DECLINED", null);

            assertThatThrownBy(() ->
                    insertRestriction(c, 6001L, 5001L, "TEAM_APPLY", "COOLDOWN", "WITHDRAWN", "2026-12-31 00:00:00"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("Duplicate entry");
        }
    }

    @Test
    @Order(13)
    @DisplayName("restrictions: kind と restricted_until の整合 CHECK、direction/kind/reason の値域 CHECK")
    void restrictions_CHECK制約() throws Exception {
        migrateAll();
        try (Connection c = conn()) {
            // 正常: COOLDOWN は期限あり、BLOCK は期限なし
            insertRestriction(c, 6101L, 5101L, "TEAM_APPLY", "COOLDOWN", "REJECTED", "2026-12-31 00:00:00");
            insertRestriction(c, 6101L, 5102L, "TEAM_APPLY", "BLOCK", "REJECTED", null);

            assertThatThrownBy(() -> insertRestriction(c, 6101L, 5103L, "TEAM_APPLY", "BLOCK", "REJECTED",
                    "2026-12-31 00:00:00"))
                    .as("BLOCK は restricted_until が NULL でなければならない")
                    .hasMessageContaining("chk_toar_until");
            assertThatThrownBy(() -> insertRestriction(c, 6101L, 5104L, "TEAM_APPLY", "COOLDOWN", "REJECTED", null))
                    .as("COOLDOWN は restricted_until 必須")
                    .hasMessageContaining("chk_toar_until");
            assertThatThrownBy(() -> insertRestriction(c, 6101L, 5105L, "BOTH", "BLOCK", "REJECTED", null))
                    .hasMessageContaining("chk_toar_direction");
            assertThatThrownBy(() -> insertRestriction(c, 6101L, 5106L, "TEAM_APPLY", "FOREVER", "REJECTED", null))
                    .hasMessageContaining("chk_toar_kind");
            assertThatThrownBy(() -> insertRestriction(c, 6101L, 5107L, "TEAM_APPLY", "BLOCK", "LEFT", null))
                    .hasMessageContaining("chk_toar_reason");
        }
    }

    // ------------------------------------------------------------------
    // ヘルパ
    // ------------------------------------------------------------------

    private static MigrateResult migrateAll() {
        return Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .load()
                .migrate();
    }

    private static Connection conn() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    private static String schema() {
        return MYSQL.getDatabaseName();
    }

    private static void insertGroup(Connection c, long orgId, String name, String deletedAt) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO org_team_groups (id, organization_id, name, sort_order, deleted_at) "
                        + "VALUES (UNHEX(REPLACE(UUID(),'-','')), ?, ?, 0, ?)")) {
            ps.setLong(1, orgId);
            ps.setString(2, name);
            ps.setString(3, deletedAt);
            ps.executeUpdate();
        }
    }

    private static void insertRestriction(Connection c, long orgId, long teamId, String direction, String kind,
                                          String reason, String until) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO team_org_affiliation_restrictions "
                        + "(id, organization_id, team_id, direction, kind, reason, restricted_until) "
                        + "VALUES (UNHEX(REPLACE(UUID(),'-','')), ?, ?, ?, ?, ?, ?)")) {
            ps.setLong(1, orgId);
            ps.setLong(2, teamId);
            ps.setString(3, direction);
            ps.setString(4, kind);
            ps.setString(5, reason);
            ps.setString(6, until);
            ps.executeUpdate();
        }
    }

    private static int countGroups(long orgId, String name) throws SQLException {
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM org_team_groups WHERE organization_id = ? AND name = ?")) {
            ps.setLong(1, orgId);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** 指定組織・名前のグループのうち、最初の行の active_name（行が無ければ失敗）。 */
    private static String activeName(Connection c, long orgId, String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT active_name FROM org_team_groups WHERE organization_id = ? AND name = ? "
                        + "ORDER BY deleted_at IS NULL DESC LIMIT 1")) {
            ps.setLong(1, orgId);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("グループ行が存在する").isTrue();
                return rs.getString(1);
            }
        }
    }

    private static boolean tableExists(String table) throws SQLException {
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?")) {
            ps.setString(1, schema());
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    private static Set<String> columnNames(String table) throws SQLException {
        Set<String> names = new HashSet<>();
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?")) {
            ps.setString(1, schema());
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    names.add(rs.getString(1).toLowerCase());
                }
            }
        }
        return names;
    }

    private static void assertColumn(String table, String column, String columnType, String nullable,
                                     String defaultValue) throws SQLException {
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(
                "SELECT COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND COLUMN_NAME = ?")) {
            ps.setString(1, schema());
            ps.setString(2, table);
            ps.setString(3, column);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("%s.%s が存在する", table, column).isTrue();
                assertThat(rs.getString(1).toLowerCase()).as("%s.%s の型", table, column).isEqualTo(columnType);
                assertThat(rs.getString(2)).as("%s.%s の NULL 可否", table, column).isEqualTo(nullable);
                String actualDefault = rs.getString(3);
                if (defaultValue == null) {
                    assertThat(actualDefault).as("%s.%s の既定値なし", table, column).isNull();
                } else {
                    assertThat(actualDefault).as("%s.%s の既定値", table, column)
                            .isEqualToIgnoringCase(defaultValue);
                }
            }
        }
    }

    private static Set<String> indexNames(String table) throws SQLException {
        Set<String> names = new HashSet<>();
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(
                "SELECT DISTINCT INDEX_NAME FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?")) {
            ps.setString(1, schema());
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
            }
        }
        return names;
    }

    private static boolean isUniqueIndex(String table, String index) throws SQLException {
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(
                "SELECT MIN(NON_UNIQUE) FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND INDEX_NAME = ?")) {
            ps.setString(1, schema());
            ps.setString(2, table);
            ps.setString(3, index);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                String nonUnique = rs.getString(1);
                assertThat(nonUnique).as("索引 %s.%s が存在する", table, index).isNotNull();
                return "0".equals(nonUnique);
            }
        }
    }

    private static Set<String> checkConstraintNames(String table) throws SQLException {
        Set<String> names = new HashSet<>();
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(
                "SELECT CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS "
                        + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND CONSTRAINT_TYPE = 'CHECK'")) {
            ps.setString(1, schema());
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
            }
        }
        return names;
    }
}
