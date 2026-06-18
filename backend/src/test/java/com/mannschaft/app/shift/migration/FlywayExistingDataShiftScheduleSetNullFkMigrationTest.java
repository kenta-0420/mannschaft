package com.mannschaft.app.shift.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>クロスドメインFK撤廃 第三陣B（shift ドメイン）の番人テスト。</b>
 *
 * <p>V103.001 で shift ドメインの「users を親とする ON DELETE SET NULL の監査/操作者カラム」FK 2件を撤廃only する:</p>
 * <ul>
 *   <li>{@code shift_schedules.fk_ss_created_by}（created_by → users SET NULL）</li>
 *   <li>{@code shift_schedules.fk_ss_published_by}（published_by → users SET NULL）</li>
 * </ul>
 *
 * <p>本テストが守る不変条件:</p>
 * <ol>
 *   <li>V103.001 の直前（V102.001）まで適用 → シフト表行（created_by / published_by に監査 user）をシード。</li>
 *   <li>V103.001 直前時点で対象2FKが実在することを sanity 確認。</li>
 *   <li>残り（V103.001 含む）を適用しても既存データを壊さず成功する。</li>
 *   <li>V103.001 で対象2FKが撤廃される。</li>
 *   <li><b>親 users 行（created_by / published_by の監査 user）を物理 DELETE しても監査列が NULL 化されず孤児 user_id 値を保持する</b>
 *       （＝SET NULL 撤廃only の肝・「誰が作成/公開したか」の操作者証跡温存）。</li>
 * </ol>
 *
 * <p>方針: Spring を起動せず Testcontainers の実 MySQL 8.0 に {@link Flyway} を Java API で直接実行する。
 * Docker 未起動環境では {@code @EnabledIf} でスキップ（骨抜きにしない・根治原則）。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.shift.migration.FlywayExistingDataShiftScheduleSetNullFkMigrationTest#isDockerAvailable")
@DisplayName("Flyway 既存データ shift_schedules created_by/published_by SET NULL FK撤廃（V103.001）番人テスト")
class FlywayExistingDataShiftScheduleSetNullFkMigrationTest {

    private static final String PRE_V103_001_TARGET = "102.001";

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_ss_setnull_fk")
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

    private Connection conn() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    @Test
    @DisplayName("既存シフト表行を持つDBにV103.001適用_created_by/published_by_SET_NULL_FK撤廃_親user物理削除でも監査列が孤児user_idを保持")
    void 既存データを持つDBでV103_001がshift_schedules監査列SET_NULL_FK撤廃onlyで安全に適用される() throws Exception {
        Flyway pre = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .target(MigrationVersion.fromVersion(PRE_V103_001_TARGET))
                .load();
        MigrateResult preResult = pre.migrate();
        assertThat(preResult.success).as("V102.001 までの適用が成功すること").isTrue();

        final long createdBy;   // shift_schedules.created_by（撤廃対象）
        final long publishedBy; // shift_schedules.published_by（撤廃対象）
        final long scheduleId;
        final long teamId;

        try (Connection c = conn()) {
            // sanity: V102.001 時点で対象2FKが実在すること
            assertThat(foreignKeyExists(c, "shift_schedules", "fk_ss_created_by"))
                    .as("V102.001 時点で fk_ss_created_by が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "shift_schedules", "fk_ss_published_by"))
                    .as("V102.001 時点で fk_ss_published_by が実在すること").isTrue();

            teamId = insertTeam(c, "シフトFK撤廃テストチーム");
            createdBy = insertUser(c, "ss-createdby@example.com");
            publishedBy = insertUser(c, "ss-publishedby@example.com");
            scheduleId = insertSchedule(c, teamId, createdBy, publishedBy);
        }

        Flyway rest = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .load();
        MigrateResult restResult = rest.migrate();
        assertThat(restResult.success).as("V103.001 を含む残りのマイグレーションが成功すること").isTrue();

        try (Connection c = conn()) {
            // then-1: 対象2FKが撤廃された
            assertThat(foreignKeyExists(c, "shift_schedules", "fk_ss_created_by"))
                    .as("V103.001 で fk_ss_created_by が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "shift_schedules", "fk_ss_published_by"))
                    .as("V103.001 で fk_ss_published_by が撤廃されること").isFalse();

            // then-2: 既存シフト表行が生存していること
            assertThat(rowExists(c, "shift_schedules", scheduleId))
                    .as("FK 撤廃後も shift_schedules 行が生存していること").isTrue();

            // then-3（中核）: 監査列でのみ参照される親 users を物理削除しても監査列が NULL 化されず孤児値を保持
            deleteUserPhysically(c, createdBy);
            deleteUserPhysically(c, publishedBy);

            assertThat(rowExists(c, "users", createdBy)).as("親 users（created_by）が物理削除されたこと").isFalse();
            assertThat(rowExists(c, "users", publishedBy)).as("親 users（published_by）が物理削除されたこと").isFalse();
            assertThat(rowExists(c, "shift_schedules", scheduleId))
                    .as("操作者 users 物理削除でも shift_schedules 行が生存すること").isTrue();

            assertThat(longColumn(c, "shift_schedules", "created_by", scheduleId))
                    .as("shift_schedules.created_by が SET NULL されず孤児 user_id を保持すること（作成者証跡温存）")
                    .isEqualTo(createdBy);
            assertThat(longColumn(c, "shift_schedules", "published_by", scheduleId))
                    .as("shift_schedules.published_by が SET NULL されず孤児 user_id を保持すること（公開操作者証跡温存）")
                    .isEqualTo(publishedBy);
        }
    }

    // ── helpers ────────────────────────────────────────────────

    private long insertUser(Connection c, String email) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO users
                    (email, last_name, first_name, display_name, status, created_at, updated_at)
                VALUES (?, 'シフト', '担当', 'シフト担当', 'ACTIVE', NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, email);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long insertTeam(Connection c, String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO teams (name, slug, visibility, created_at, updated_at)
                VALUES (?, 'test-team-shift', 'PUBLIC', NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long insertSchedule(Connection c, long teamId, long createdBy, long publishedBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO shift_schedules
                    (team_id, title, period_type, start_date, end_date, status,
                     created_by, published_at, published_by, created_at, updated_at)
                VALUES (?, '監査FK撤廃テストシフト表', 'WEEKLY',
                        CURDATE() + INTERVAL 7 DAY, CURDATE() + INTERVAL 13 DAY, 'PUBLISHED',
                        ?, NOW(), ?, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, teamId);
            ps.setLong(2, createdBy);
            ps.setLong(3, publishedBy);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private void deleteUserPhysically(Connection c, long userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM users WHERE id = ?")) {
            ps.setLong(1, userId);
            ps.executeUpdate();
        }
    }

    private static boolean rowExists(Connection c, String table, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM " + table + " WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1) > 0;
            }
        }
    }

    private static long longColumn(Connection c, String table, String column, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT " + column + " FROM " + table + " WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                long v = rs.getLong(1);
                return rs.wasNull() ? -1L : v;
            }
        }
    }

    private static boolean foreignKeyExists(Connection c, String table, String constraintName)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT COUNT(*) FROM information_schema.table_constraints
                WHERE table_schema = DATABASE()
                  AND table_name = ?
                  AND constraint_name = ?
                  AND constraint_type = 'FOREIGN KEY'
                """)) {
            ps.setString(1, table);
            ps.setString(2, constraintName);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }
}
