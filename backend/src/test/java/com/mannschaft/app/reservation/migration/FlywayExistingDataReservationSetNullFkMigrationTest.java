package com.mannschaft.app.reservation.migration;

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
 * <b>クロスドメインFK撤廃 第三陣B（reservation ドメイン）の番人テスト。</b>
 *
 * <p>V103.001 で reservation ドメインの「users を親とする ON DELETE SET NULL の監査/操作者カラム」FK 4件を撤廃only する:</p>
 * <ul>
 *   <li>{@code reservation_blocked_times.fk_reservation_bt_created_by}（created_by → users SET NULL）</li>
 *   <li>{@code reservation_lines.fk_reservation_lines_default_staff}（default_staff_user_id → users SET NULL）</li>
 *   <li>{@code reservation_slots.fk_reservation_slots_staff}（staff_user_id → users SET NULL）</li>
 *   <li>{@code reservation_slots.fk_reservation_slots_created_by}（created_by → users SET NULL）</li>
 * </ul>
 *
 * <p>本テストが守る不変条件:</p>
 * <ol>
 *   <li>V103.001 の直前（V102.001）まで適用 → 各テーブルに監査列＝対象 user を持つ子行をシード。</li>
 *   <li>V103.001 直前時点で対象4FKが実在することを sanity 確認。</li>
 *   <li>残り（V103.001 含む）を適用しても既存データを壊さず成功する。</li>
 *   <li>V103.001 で対象4FKが撤廃される。</li>
 *   <li><b>親 users 行（監査列でのみ参照される user）を物理 DELETE しても監査列が NULL 化されず孤児 user_id 値を保持する</b>
 *       （＝SET NULL 撤廃only の肝・「誰が作成/担当したか」の操作者証跡温存）。</li>
 * </ol>
 *
 * <p>方針: Spring を起動せず Testcontainers の実 MySQL 8.0 に {@link Flyway} を Java API で直接実行する。
 * Docker 未起動環境では {@code @EnabledIf} でスキップ（骨抜きにしない・根治原則）。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.reservation.migration.FlywayExistingDataReservationSetNullFkMigrationTest#isDockerAvailable")
@DisplayName("Flyway 既存データ reservation 監査列 SET NULL FK撤廃（V103.001）番人テスト")
class FlywayExistingDataReservationSetNullFkMigrationTest {

    private static final String PRE_V103_001_TARGET = "102.001";

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_reservation_setnull_fk")
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
    @DisplayName("既存子行を持つDBにV103.001適用_reservation監査列SET_NULL_FK4件撤廃_親user物理削除でも監査列が孤児user_idを保持")
    void 既存データを持つDBでV103_001がreservation監査列SET_NULL_FK撤廃onlyで安全に適用される() throws Exception {
        Flyway pre = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .target(MigrationVersion.fromVersion(PRE_V103_001_TARGET))
                .load();
        MigrateResult preResult = pre.migrate();
        assertThat(preResult.success).as("V102.001 までの適用が成功すること").isTrue();

        // 監査列でのみ参照される user 群（物理削除して孤児化を検証する）
        final long btCreatedBy;     // reservation_blocked_times.created_by
        final long lineStaff;       // reservation_lines.default_staff_user_id
        final long slotStaff;       // reservation_slots.staff_user_id
        final long slotCreatedBy;   // reservation_slots.created_by
        final long blockedTimeId;
        final long lineId;
        final long slotId;
        // team_id 用の値（reservation 系の team FK は V95.001 で既に撤廃済のため任意値で良いが、
        // 監査列とは独立に存在を確かめられるよう teams 行を作っておく）
        final long teamId;

        try (Connection c = conn()) {
            // sanity: V102.001 時点で対象4FKが実在すること
            assertThat(foreignKeyExists(c, "reservation_blocked_times", "fk_reservation_bt_created_by"))
                    .as("V102.001 時点で fk_reservation_bt_created_by が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "reservation_lines", "fk_reservation_lines_default_staff"))
                    .as("V102.001 時点で fk_reservation_lines_default_staff が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "reservation_slots", "fk_reservation_slots_staff"))
                    .as("V102.001 時点で fk_reservation_slots_staff が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "reservation_slots", "fk_reservation_slots_created_by"))
                    .as("V102.001 時点で fk_reservation_slots_created_by が実在すること").isTrue();

            teamId = insertTeam(c, "予約監査FK撤廃テストチーム");
            btCreatedBy = insertUser(c, "res-bt-createdby@example.com");
            lineStaff = insertUser(c, "res-line-staff@example.com");
            slotStaff = insertUser(c, "res-slot-staff@example.com");
            slotCreatedBy = insertUser(c, "res-slot-createdby@example.com");

            blockedTimeId = insertBlockedTime(c, teamId, btCreatedBy);
            lineId = insertLine(c, teamId, lineStaff);
            slotId = insertSlot(c, teamId, slotStaff, slotCreatedBy);
        }

        Flyway rest = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .load();
        MigrateResult restResult = rest.migrate();
        assertThat(restResult.success).as("V103.001 を含む残りのマイグレーションが成功すること").isTrue();

        try (Connection c = conn()) {
            // then-1: 対象4FKが撤廃された
            assertThat(foreignKeyExists(c, "reservation_blocked_times", "fk_reservation_bt_created_by"))
                    .as("V103.001 で fk_reservation_bt_created_by が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "reservation_lines", "fk_reservation_lines_default_staff"))
                    .as("V103.001 で fk_reservation_lines_default_staff が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "reservation_slots", "fk_reservation_slots_staff"))
                    .as("V103.001 で fk_reservation_slots_staff が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "reservation_slots", "fk_reservation_slots_created_by"))
                    .as("V103.001 で fk_reservation_slots_created_by が撤廃されること").isFalse();

            // 対象外: staff_user_id をカバーする名前付き index は FK 撤廃後も残存していること
            assertThat(indexExists(c, "reservation_slots", "idx_reservation_slots_staff_date"))
                    .as("idx_reservation_slots_staff_date が FK 撤廃後も残存すること（staff_user_id 引き継続可）").isTrue();

            // then-2: 既存子行が生存していること
            assertThat(rowExists(c, "reservation_blocked_times", blockedTimeId))
                    .as("FK 撤廃後も reservation_blocked_times 子行が生存していること").isTrue();
            assertThat(rowExists(c, "reservation_lines", lineId))
                    .as("FK 撤廃後も reservation_lines 子行が生存していること").isTrue();
            assertThat(rowExists(c, "reservation_slots", slotId))
                    .as("FK 撤廃後も reservation_slots 子行が生存していること").isTrue();

            // then-3（中核）: 監査列でのみ参照される親 users を物理削除しても監査列が NULL 化されず孤児値を保持
            deleteUserPhysically(c, btCreatedBy);
            deleteUserPhysically(c, lineStaff);
            deleteUserPhysically(c, slotStaff);
            deleteUserPhysically(c, slotCreatedBy);

            assertThat(rowExists(c, "users", btCreatedBy)).as("親 users（bt created_by）が物理削除されたこと").isFalse();
            assertThat(rowExists(c, "users", lineStaff)).as("親 users（line staff）が物理削除されたこと").isFalse();
            assertThat(rowExists(c, "users", slotStaff)).as("親 users（slot staff）が物理削除されたこと").isFalse();
            assertThat(rowExists(c, "users", slotCreatedBy)).as("親 users（slot created_by）が物理削除されたこと").isFalse();

            assertThat(longColumn(c, "reservation_blocked_times", "created_by", blockedTimeId))
                    .as("reservation_blocked_times.created_by が SET NULL されず孤児 user_id を保持すること")
                    .isEqualTo(btCreatedBy);
            assertThat(longColumn(c, "reservation_lines", "default_staff_user_id", lineId))
                    .as("reservation_lines.default_staff_user_id が SET NULL されず孤児 user_id を保持すること")
                    .isEqualTo(lineStaff);
            assertThat(longColumn(c, "reservation_slots", "staff_user_id", slotId))
                    .as("reservation_slots.staff_user_id が SET NULL されず孤児 user_id を保持すること")
                    .isEqualTo(slotStaff);
            assertThat(longColumn(c, "reservation_slots", "created_by", slotId))
                    .as("reservation_slots.created_by が SET NULL されず孤児 user_id を保持すること")
                    .isEqualTo(slotCreatedBy);
        }
    }

    // ── helpers ────────────────────────────────────────────────

    private long insertUser(Connection c, String email) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO users
                    (email, last_name, first_name, display_name, status, created_at, updated_at)
                VALUES (?, '予約', '担当', '予約担当', 'ACTIVE', NOW(), NOW())
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
                INSERT INTO teams (name, visibility, created_at, updated_at)
                VALUES (?, 'PUBLIC', NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long insertBlockedTime(Connection c, long teamId, long createdBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO reservation_blocked_times
                    (team_id, blocked_date, start_time, end_time, reason, created_by, created_at, updated_at)
                VALUES (?, CURDATE() + INTERVAL 3 DAY, '10:00:00', '11:00:00', 'メンテナンス', ?, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, teamId);
            ps.setLong(2, createdBy);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long insertLine(Connection c, long teamId, long defaultStaffUserId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO reservation_lines
                    (team_id, name, description, display_order, is_active, default_staff_user_id, created_at, updated_at)
                VALUES (?, '個人レッスン', '監査FK撤廃テストライン', 1, TRUE, ?, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, teamId);
            ps.setLong(2, defaultStaffUserId);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long insertSlot(Connection c, long teamId, long staffUserId, long createdBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO reservation_slots
                    (team_id, staff_user_id, title, slot_date, start_time, end_time,
                     booked_count, slot_status, is_exception, created_by, created_at, updated_at)
                VALUES (?, ?, '監査FK撤廃テストスロット', CURDATE() + INTERVAL 5 DAY, '13:00:00', '14:00:00',
                        0, 'AVAILABLE', FALSE, ?, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, teamId);
            ps.setLong(2, staffUserId);
            ps.setLong(3, createdBy);
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

    private static boolean indexExists(Connection c, String table, String indexName) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = ?
                  AND index_name = ?
                """)) {
            ps.setString(1, table);
            ps.setString(2, indexName);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }
}
