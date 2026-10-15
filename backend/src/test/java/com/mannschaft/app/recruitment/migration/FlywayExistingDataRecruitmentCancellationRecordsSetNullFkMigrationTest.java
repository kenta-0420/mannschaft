package com.mannschaft.app.recruitment.migration;

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
 * <b>クロスドメインFK撤廃 第三陣A の番人テスト（recruitment_cancellation_records）。</b>
 *
 * <p>V102.001 で {@code recruitment_cancellation_records} の users 親 ON DELETE SET NULL クロスドメインFK
 * 2件を撤廃only する:</p>
 * <ul>
 *   <li>{@code fk_rcr_cancelled_by}（cancelled_by → users SET NULL・操作者監査列）</li>
 *   <li>{@code fk_rcr_user}（user_id → users SET NULL）</li>
 * </ul>
 *
 * <p>本テストが守る不変条件（SET NULL 撤廃only の肝）:</p>
 * <ol>
 *   <li>V102.001 の直前（V101.002）まで適用 → users 親行＋キャンセル記録行（cancelled_by/user_id に当該 user の id をセット）をシード。</li>
 *   <li>残り（V102.001 含む）を適用しても既存データを壊さず成功する。</li>
 *   <li>V102.001 で fk_rcr_cancelled_by / fk_rcr_user がともに撤廃される。</li>
 *   <li><b>親 users 行を物理 DELETE しても cancelled_by / user_id が NULL 化されず孤児 user_id 値を保持する</b>
 *       （＝退会30日後の users 物理削除でも「誰がキャンセルしたか」の監査履歴が温存されることの恒久的回帰防止）。</li>
 * </ol>
 *
 * <p>方針: Spring を起動せず Testcontainers の実 MySQL 8.0 に {@link Flyway} を Java API で直接実行する。
 * Docker 未起動環境では {@code @EnabledIf} でスキップ（骨抜きにしない・根治原則）。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.recruitment.migration.FlywayExistingDataRecruitmentCancellationRecordsSetNullFkMigrationTest#isDockerAvailable")
@DisplayName("Flyway 既存データ recruitment_cancellation_records SET NULL 監査FK撤廃（V102.001）番人テスト")
class FlywayExistingDataRecruitmentCancellationRecordsSetNullFkMigrationTest {

    /** V102.001 の直前バージョン（origin/main 全体最大）。ここまで適用してから既存行をシードする。 */
    private static final String PRE_V102_001_TARGET = "101.002";

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_rcr_setnull_fk")
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
    @DisplayName("既存キャンセル記録行を持つDBにV102.001適用_SET_NULL_FK2件撤廃_親user物理削除でも監査列が孤児user_idを保持")
    void 既存データを持つDBでV102_001がSET_NULL_FK撤廃onlyで安全に適用される() throws Exception {
        // given: V102.001 の直前（V101.002）まで適用 ＝ fk_rcr_cancelled_by / fk_rcr_user はまだ生きている
        Flyway pre = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .target(MigrationVersion.fromVersion(PRE_V102_001_TARGET))
                .load();
        MigrateResult preResult = pre.migrate();
        assertThat(preResult.success).as("V101.002 までの適用が成功すること").isTrue();

        final long userId;
        final long recordId;
        try (Connection c = conn()) {
            // sanity: この時点では対象 FK が実在する（撤廃前スキーマの証明）
            assertThat(foreignKeyExists(c, "recruitment_cancellation_records", "fk_rcr_cancelled_by"))
                    .as("V101.002 時点では fk_rcr_cancelled_by が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "recruitment_cancellation_records", "fk_rcr_user"))
                    .as("V101.002 時点では fk_rcr_user が実在すること").isTrue();

            userId = insertUser(c, "rcr-canceller@example.com");
            // user_id と cancelled_by の両方に当該 user の id をセット（両 FK の SET NULL 撤廃を同時検証）
            recordId = insertCancellationRecord(c, userId, userId);
        }

        // when: 残りのマイグレーション（V102.001 含む）を適用する
        Flyway rest = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .load();
        MigrateResult restResult = rest.migrate();
        assertThat(restResult.success).as("V102.001 を含む残りのマイグレーションが成功すること").isTrue();

        try (Connection c = conn()) {
            // then-1: 対象 FK が撤廃された
            assertThat(foreignKeyExists(c, "recruitment_cancellation_records", "fk_rcr_cancelled_by"))
                    .as("V102.001 で fk_rcr_cancelled_by が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "recruitment_cancellation_records", "fk_rcr_user"))
                    .as("V102.001 で fk_rcr_user が撤廃されること").isFalse();

            // then-2: 既存行は無傷で生存
            assertThat(rowExists(c, "recruitment_cancellation_records", recordId))
                    .as("FK 撤廃後も既存キャンセル記録行が生存していること").isTrue();

            // then-3（中核）: 親 users 行を物理 DELETE しても cancelled_by / user_id が NULL 化されず孤児値を保持する
            deleteUserPhysically(c, userId);
            assertThat(rowExists(c, "users", userId))
                    .as("親 users 行が物理削除されたこと").isFalse();
            assertThat(rowExists(c, "recruitment_cancellation_records", recordId))
                    .as("親 users 物理削除でもキャンセル記録行が生存すること").isTrue();
            assertThat(longColumn(c, "recruitment_cancellation_records", "cancelled_by", recordId))
                    .as("cancelled_by が SET NULL されず孤児 user_id を保持すること（監査履歴温存）").isEqualTo(userId);
            assertThat(longColumn(c, "recruitment_cancellation_records", "user_id", recordId))
                    .as("user_id が SET NULL されず孤児 user_id を保持すること").isEqualTo(userId);
        }
    }

    // ── helpers ────────────────────────────────────────────────

    private long insertUser(Connection c, String email) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO users
                    (email, last_name, first_name, display_name, status, created_at, updated_at)
                VALUES (?, '解約', '花子', '解約花子', 'ACTIVE', NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, email);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /**
     * キャンセル記録行を挿入する。participant_id / listing_id は NULL 可（fk_rcr_participant/fk_rcr_listing は SET NULL）。
     * user_id（fk_rcr_user）・cancelled_by（fk_rcr_cancelled_by）に当該 user の id をセットして両 FK 撤廃を検証する。
     */
    private long insertCancellationRecord(Connection c, long userId, long cancelledBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO recruitment_cancellation_records
                    (user_id, cancelled_by, cancel_source, hours_before_start, fee_amount, payment_status, cancelled_at)
                VALUES (?, ?, 'USER', 24, 0, 'NOT_REQUIRED', NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, userId);
            ps.setLong(2, cancelledBy);
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

    /** 指定列の値を long で返す。NULL の場合は -1 を返す（孤児値保持の検証で NULL と区別するため）。 */
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
