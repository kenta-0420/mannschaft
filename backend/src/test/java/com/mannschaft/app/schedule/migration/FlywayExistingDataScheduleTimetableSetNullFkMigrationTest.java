package com.mannschaft.app.schedule.migration;

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
 * <b>クロスドメインFK撤廃 第三陣D（schedule / timetable ドメイン）の番人テスト。</b>
 *
 * <p>V105.001 で schedule / timetable ドメインの「users を親とする ON DELETE SET NULL の監査カラム」FK 2件を撤廃only する:</p>
 * <ul>
 *   <li>{@code schedules.fk_sch_created_by}（created_by → users SET NULL）</li>
 *   <li>{@code timetables.fk_tm_created_by}（created_by → users SET NULL）</li>
 * </ul>
 *
 * <p>本テストが守る不変条件:</p>
 * <ol>
 *   <li>V105.001 の直前（V104.001）まで適用 → 監査列＝対象 user を持つ子行をシード。</li>
 *   <li>V105.001 直前時点で対象2FKが実在することを sanity 確認。</li>
 *   <li>残り（V105.001 含む）を適用しても既存データを壊さず成功する。</li>
 *   <li>V105.001 で対象2FKが撤廃される。</li>
 *   <li><b>親 users 行（監査列でのみ参照される user）を物理 DELETE しても監査列が NULL 化されず孤児 user_id 値を保持する</b>
 *       （＝SET NULL 撤廃only の肝・「誰が予定/時間割を作成したか」の操作者証跡温存）。</li>
 *   <li>schedules は孤児 created_by 行でも CHECK 制約 {@code ck_schedules_scope_xor}（team/org/user/committee の XOR）を満たし続ける。</li>
 * </ol>
 *
 * <p>schedules は user スコープ（user_id 非NULL・team/org/committee NULL）でシードすることで親 teams/org を不要にする。
 * created_by（撤廃対象の監査列）は scope の user_id とは別列であり、scope を汚さない。
 * timetables は team_id NOT NULL のため最小限の team + timetable_term（同一ドメイン RESTRICT 親）を用意してシードする。</p>
 *
 * <p>方針: Spring を起動せず Testcontainers の実 MySQL 8.0 に {@link Flyway} を Java API で直接実行する。
 * Docker 未起動環境では {@code @EnabledIf} でスキップ（骨抜きにしない・根治原則）。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.schedule.migration.FlywayExistingDataScheduleTimetableSetNullFkMigrationTest#isDockerAvailable")
@DisplayName("Flyway 既存データ schedule/timetable 監査列 SET NULL FK撤廃（V105.001）番人テスト")
class FlywayExistingDataScheduleTimetableSetNullFkMigrationTest {

    /** V105.001 の直前バージョン（origin/main 全体最大＝第三陣C）。ここまで適用してから既存行をシードする。 */
    private static final String PRE_V105_001_TARGET = "104.001";

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_schedule_setnull_fk")
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
    @DisplayName("既存子行を持つDBにV105.001適用_schedule_timetable監査列SET_NULL_FK2件撤廃_親user物理削除でも監査列が孤児user_idを保持")
    void 既存データを持つDBでV105_001がschedule_timetable監査列SET_NULL_FK撤廃onlyで安全に適用される() throws Exception {
        // given: V105.001 の直前（V104.001）まで適用 ＝ 対象2FKはまだ生きている
        Flyway pre = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .target(MigrationVersion.fromVersion(PRE_V105_001_TARGET))
                .load();
        MigrateResult preResult = pre.migrate();
        assertThat(preResult.success).as("V104.001 までの適用が成功すること").isTrue();

        final long scheduleScopeUser; // schedules.user_id（user スコープ・scope 充足用）
        final long scheduleCreatedBy; // schedules.created_by（撤廃対象の監査列）
        final long timetableCreatedBy; // timetables.created_by（撤廃対象の監査列）
        final long teamId;            // timetables / timetable_terms の team 親
        final long termId;            // timetables.term_id（同一ドメイン RESTRICT 親）
        final long scheduleId;
        final long timetableId;

        try (Connection c = conn()) {
            // sanity: V104.001 時点で対象2FKが実在すること
            assertThat(foreignKeyExists(c, "schedules", "fk_sch_created_by"))
                    .as("V104.001 時点で fk_sch_created_by が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "timetables", "fk_tm_created_by"))
                    .as("V104.001 時点で fk_tm_created_by が実在すること").isTrue();

            scheduleScopeUser = insertUser(c, "sch-scope-3d@example.com");
            scheduleCreatedBy = insertUser(c, "sch-createdby-3d@example.com");
            timetableCreatedBy = insertUser(c, "tm-createdby-3d@example.com");

            // schedules は user スコープ（user_id のみ非NULL）でシード → ck_schedules_scope_xor 充足・親 teams/org 不要
            scheduleId = insertUserScopedSchedule(c, scheduleScopeUser, scheduleCreatedBy);

            // timetables は team_id NOT NULL → team + timetable_term（team スコープ）を用意してシード
            teamId = insertTeam(c, "schedule監査FK撤廃テストチーム", "test-team-sched-3d");
            termId = insertTeamScopedTerm(c, teamId);
            timetableId = insertTimetable(c, teamId, termId, timetableCreatedBy);
        }

        // when: 残りのマイグレーション（V105.001 含む）を適用する
        Flyway rest = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .load();
        MigrateResult restResult = rest.migrate();
        assertThat(restResult.success).as("V105.001 を含む残りのマイグレーションが成功すること").isTrue();

        try (Connection c = conn()) {
            // then-1: 対象2FKが撤廃された
            assertThat(foreignKeyExists(c, "schedules", "fk_sch_created_by"))
                    .as("V105.001 で fk_sch_created_by が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "timetables", "fk_tm_created_by"))
                    .as("V105.001 で fk_tm_created_by が撤廃されること").isFalse();

            // 対象外: 同一ドメイン/他参照 FK は撤廃後も残存していること
            assertThat(foreignKeyExists(c, "schedules", "fk_sch_parent"))
                    .as("fk_sch_parent（同一ドメイン RESTRICT）は撤廃対象外で残存すること").isTrue();
            assertThat(foreignKeyExists(c, "timetables", "fk_tm_term"))
                    .as("fk_tm_term（同一ドメイン RESTRICT）は撤廃対象外で残存すること").isTrue();

            // then-2: 既存子行が生存していること
            assertThat(rowExists(c, "schedules", scheduleId))
                    .as("FK 撤廃後も schedules 子行が生存していること").isTrue();
            assertThat(rowExists(c, "timetables", timetableId))
                    .as("FK 撤廃後も timetables 子行が生存していること").isTrue();

            // then-3（中核）: 監査列でのみ参照される親 users を物理削除しても監査列が NULL 化されず孤児値を保持
            deleteUserPhysically(c, scheduleCreatedBy);
            deleteUserPhysically(c, timetableCreatedBy);

            assertThat(rowExists(c, "users", scheduleCreatedBy)).as("親 users（schedule created_by）が物理削除されたこと").isFalse();
            assertThat(rowExists(c, "users", timetableCreatedBy)).as("親 users（timetable created_by）が物理削除されたこと").isFalse();

            assertThat(longColumn(c, "schedules", "created_by", scheduleId))
                    .as("schedules.created_by が SET NULL されず孤児 user_id を保持すること")
                    .isEqualTo(scheduleCreatedBy);
            assertThat(longColumn(c, "timetables", "created_by", timetableId))
                    .as("timetables.created_by が SET NULL されず孤児 user_id を保持すること")
                    .isEqualTo(timetableCreatedBy);

            // then-4: 孤児 created_by 行でも schedules の CHECK 制約 ck_schedules_scope_xor を満たし続ける
            //         （user スコープ = user_id 非NULL・team/org/committee NULL）
            assertThat(longColumn(c, "schedules", "user_id", scheduleId))
                    .as("user スコープ行の user_id は保持されていること（ck_schedules_scope_xor 充足）")
                    .isEqualTo(scheduleScopeUser);
            assertThat(isNullColumn(c, "schedules", "team_id", scheduleId))
                    .as("user スコープ行の team_id は NULL であること").isTrue();
            assertThat(isNullColumn(c, "schedules", "organization_id", scheduleId))
                    .as("user スコープ行の organization_id は NULL であること").isTrue();
            assertThat(isNullColumn(c, "schedules", "committee_id", scheduleId))
                    .as("user スコープ行の committee_id は NULL であること").isTrue();
        }
    }

    // ── helpers ────────────────────────────────────────────────

    private long insertUser(Connection c, String email) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO users
                    (email, last_name, first_name, display_name, status, created_at, updated_at)
                VALUES (?, '予定', '太郎', '予定太郎', 'ACTIVE', NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, email);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long insertTeam(Connection c, String name, String slug) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO teams (name, slug, visibility, created_at, updated_at)
                VALUES (?, ?, 'PUBLIC', NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setString(2, slug);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** user スコープ（user_id のみ非NULL）の schedules 行を挿入する（ck_schedules_scope_xor 充足）。 */
    private long insertUserScopedSchedule(Connection c, long scopeUserId, long createdBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO schedules
                    (team_id, organization_id, user_id, committee_id, title, start_at, created_by, created_at, updated_at)
                VALUES (NULL, NULL, ?, NULL, '監査FK撤廃テスト予定', NOW(), ?, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, scopeUserId);
            ps.setLong(2, createdBy);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** team スコープ（team_id 非NULL・organization_id NULL）の timetable_terms 行を挿入する（chk_term_scope / chk_term_date_order 充足）。 */
    private long insertTeamScopedTerm(Connection c, long teamId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO timetable_terms
                    (team_id, organization_id, academic_year, name, start_date, end_date, sort_order, created_at, updated_at)
                VALUES (?, NULL, 2026, '監査FK撤廃テスト学期', '2026-04-01', '2026-07-31', 0, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, teamId);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long insertTimetable(Connection c, long teamId, long termId, long createdBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO timetables
                    (team_id, term_id, name, effective_from, created_by, created_at, updated_at)
                VALUES (?, ?, '監査FK撤廃テスト時間割', '2026-04-01', ?, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, teamId);
            ps.setLong(2, termId);
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

    private static boolean isNullColumn(Connection c, String table, String column, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT " + column + " FROM " + table + " WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                rs.getLong(1);
                return rs.wasNull();
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
