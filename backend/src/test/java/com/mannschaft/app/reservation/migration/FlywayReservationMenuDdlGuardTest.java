package com.mannschaft.app.reservation.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>V141.001 / V141.002（F03.4.1 予約メニュー・提供可否）の実 DDL セマンティクス</b>を
 * from-scratch で検証する番人テスト（設計書 §7「from-scratch の DDL 検証（CHECK 制約の
 * 実 enforce 含む）で足りる」）。
 *
 * <h2>このテストが守る不変条件（AC トレーサビリティ）</h2>
 * <ul>
 *   <li><b>E-3（DB 最終防御）</b>: {@code chk_rm_duration}（30 の倍数・30〜480）が MySQL 8.0.16+ で
 *       実 enforce され、Service 検証を迂回した直接 INSERT（45/510）が拒否される。境界値 30/480 は通る。</li>
 *   <li><b>§3 CASCADE</b>: メニュー物理削除で提供可否行が CASCADE 削除される（孤児行なし）。</li>
 *   <li><b>§3 RESTRICT</b>: 提供可否行が参照するラインの物理削除は RESTRICT で拒否される
 *       （提供範囲が音もなく変わる事故の番人）。</li>
 *   <li><b>§3 型整合</b>: id = BINARY(16)（UUIDv7・アーキ原則6）、
 *       team_id / created_by = BIGINT UNSIGNED（V3.060/061 と同型に統一）。</li>
 * </ul>
 *
 * <p>共有コンテキストの統合テスト（{@code AbstractMySqlIntegrationTest}）は
 * {@code ddl-auto: create}（Hibernate 生成スキーマ・Flyway 無効）のため実 DDL の観測点にならない。
 * 本テストは専用コンテナに全マイグレーションを適用して実 DDL を直接観測する
 * （新規テーブル 2 本のみ・既存データ移行なしのため既存データ番人テストは不要 — §7）。
 * Docker 未起動環境では {@code @EnabledIf} によりスキップされる。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.reservation.migration.FlywayReservationMenuDdlGuardTest#isDockerAvailable")
@DisplayName("Flyway V141 予約メニュー DDL 番人テスト（from-scratch・CHECK/CASCADE/RESTRICT/型）")
class FlywayReservationMenuDdlGuardTest {

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_menu_ddl")
            .withUsername("test")
            .withPassword("test")
            .withTmpFs(java.util.Map.of("/var/lib/mysql", "rw"))
            .withCommand("--log_bin_trust_function_creators=1");

    public static boolean isDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception e) {
            return false;
        }
    }

    @BeforeAll
    void startContainerAndMigrate() {
        MYSQL.start();
        Flyway flyway = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .load();
        MigrateResult result = flyway.migrate();
        assertThat(result.success).as("V141 を含む全マイグレーションが from-scratch で成功すること").isTrue();
    }

    @AfterAll
    void stopContainer() {
        MYSQL.stop();
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    /** 一意な 32 桁 hex（BINARY(16) 直接 INSERT 用）。 */
    private static String uuidHex() {
        return java.util.UUID.randomUUID().toString().replace("-", "");
    }

    private static void insertMenu(Connection conn, String idHex, long teamId, int duration)
            throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("INSERT INTO reservation_menus "
                    + "(id, team_id, name, duration_minutes, display_order, is_active, created_at, updated_at) "
                    + "VALUES (UNHEX('" + idHex + "'), " + teamId + ", 'メニュー', " + duration
                    + ", 1, TRUE, NOW(6), NOW(6))");
        }
    }

    private static void insertLine(Connection conn, long lineId, long teamId) throws SQLException {
        try (Statement st = conn.createStatement()) {
            // reservation_lines のクロスドメイン FK（→teams/users）は V95.001/V103.001 で撤廃済みのため
            // 親行なしで直接シードできる。
            st.executeUpdate("INSERT INTO reservation_lines "
                    + "(id, team_id, name, display_order, is_active, created_at, updated_at) "
                    + "VALUES (" + lineId + ", " + teamId + ", '席', 1, TRUE, NOW(6), NOW(6))");
        }
    }

    private static void insertMenuLine(Connection conn, String menuIdHex, long lineId) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("INSERT INTO reservation_menu_lines (menu_id, line_id, created_at) "
                    + "VALUES (UNHEX('" + menuIdHex + "'), " + lineId + ", NOW(6))");
        }
    }

    private static long countMenuLines(Connection conn, String menuIdHex) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM reservation_menu_lines WHERE menu_id = UNHEX('" + menuIdHex + "')")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String columnType(Connection conn, String table, String column) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COLUMN_TYPE FROM information_schema.COLUMNS "
                             + "WHERE TABLE_SCHEMA = DATABASE() "
                             + "AND TABLE_NAME = '" + table + "' AND COLUMN_NAME = '" + column + "'")) {
            assertThat(rs.next()).as(table + "." + column + " が存在すること").isTrue();
            return rs.getString(1).toLowerCase();
        }
    }

    @Test
    @DisplayName("E-3: CHECK chk_rm_duration が実 enforce — 境界値30/480は通り、45/510の直接INSERTは拒否")
    void CHECK制約が実enforceされる() throws Exception {
        try (Connection conn = connection()) {
            // 境界値は通る
            insertMenu(conn, uuidHex(), 901L, 30);
            insertMenu(conn, uuidHex(), 901L, 480);

            // 非30倍数・上限超は DB 最終防御で拒否（Service 検証 RESERVATION_034 の迂回経路）
            assertThatThrownBy(() -> insertMenu(conn, uuidHex(), 901L, 45))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("chk_rm_duration");
            assertThatThrownBy(() -> insertMenu(conn, uuidHex(), 901L, 510))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("chk_rm_duration");
        }
    }

    @Test
    @DisplayName("§3: メニュー物理削除で提供可否行が CASCADE 削除される（孤児行なし）")
    void メニュー物理削除でCASCADEが発火する() throws Exception {
        try (Connection conn = connection()) {
            String menuIdHex = uuidHex();
            insertMenu(conn, menuIdHex, 902L, 60);
            insertLine(conn, 90201L, 902L);
            insertMenuLine(conn, menuIdHex, 90201L);
            assertThat(countMenuLines(conn, menuIdHex)).isEqualTo(1);

            // テストデータ掃除・GDPR 起点の物理削除を模す
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("DELETE FROM reservation_menus WHERE id = UNHEX('" + menuIdHex + "')");
            }

            assertThat(countMenuLines(conn, menuIdHex))
                    .as("CASCADE で提供可否行が同時削除されること").isZero();
        }
    }

    @Test
    @DisplayName("§3: 提供可否行が参照するラインの物理削除は RESTRICT で拒否される（番人）")
    void ライン物理削除はRESTRICTで拒否される() throws Exception {
        try (Connection conn = connection()) {
            String menuIdHex = uuidHex();
            insertMenu(conn, menuIdHex, 903L, 60);
            insertLine(conn, 90301L, 903L);
            insertMenuLine(conn, menuIdHex, 90301L);

            assertThatThrownBy(() -> {
                try (Statement st = conn.createStatement()) {
                    st.executeUpdate("DELETE FROM reservation_lines WHERE id = 90301");
                }
            })
                    .as("提供可否行が残るラインの物理削除は FK RESTRICT で失敗すること")
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    @DisplayName("§3: 型整合 — id=BINARY(16)・team_id/created_by=BIGINT UNSIGNED・line_id=BIGINT UNSIGNED")
    void カラム型が設計書どおりである() throws Exception {
        try (Connection conn = connection()) {
            assertThat(columnType(conn, "reservation_menus", "id")).isEqualTo("binary(16)");
            assertThat(columnType(conn, "reservation_menus", "team_id")).contains("bigint").contains("unsigned");
            assertThat(columnType(conn, "reservation_menus", "created_by")).contains("bigint").contains("unsigned");
            assertThat(columnType(conn, "reservation_menus", "price")).isEqualTo("decimal(10,2)");
            assertThat(columnType(conn, "reservation_menu_lines", "menu_id")).isEqualTo("binary(16)");
            assertThat(columnType(conn, "reservation_menu_lines", "line_id")).contains("bigint").contains("unsigned");
        }
    }
}
