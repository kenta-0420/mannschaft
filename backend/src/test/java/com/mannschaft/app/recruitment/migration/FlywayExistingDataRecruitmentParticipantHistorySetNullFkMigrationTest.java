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
 * <b>クロスドメインFK撤廃 第三陣A の番人テスト（recruitment_participant_history / fk_rph_changed_by）。</b>
 *
 * <p>V102.001 で {@code recruitment_participant_history.fk_rph_changed_by}（changed_by → users ON DELETE SET NULL・
 * recruitment→user のクロスドメイン監査列FK）を撤廃only する。本テストが守る不変条件:</p>
 * <ol>
 *   <li>V102.001 の直前（V101.002）まで適用 → 募集枠＋参加者＋ステータス遷移履歴行（changed_by = 監査 user）をシード。</li>
 *   <li>残り（V102.001 含む）を適用しても既存データを壊さず成功する。</li>
 *   <li>V102.001 で fk_rph_changed_by が撤廃される。</li>
 *   <li><b>親 users 行（changed_by の監査 user）を物理 DELETE しても changed_by が NULL 化されず孤児 user_id 値を保持する</b>
 *       （＝SET NULL 撤廃only の肝・「誰がステータスを変更したか」の操作者証跡温存）。</li>
 * </ol>
 *
 * <p>方針: Spring を起動せず Testcontainers の実 MySQL 8.0 に {@link Flyway} を Java API で直接実行する。
 * Docker 未起動環境では {@code @EnabledIf} でスキップ（骨抜きにしない・根治原則）。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.recruitment.migration.FlywayExistingDataRecruitmentParticipantHistorySetNullFkMigrationTest#isDockerAvailable")
@DisplayName("Flyway 既存データ recruitment_participant_history changed_by SET NULL FK撤廃（V102.001）番人テスト")
class FlywayExistingDataRecruitmentParticipantHistorySetNullFkMigrationTest {

    private static final String PRE_V102_001_TARGET = "101.002";

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_rph_setnull_fk")
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
    @DisplayName("既存遷移履歴行を持つDBにV102.001適用_changed_by_SET_NULL_FK撤廃_親user物理削除でもchanged_byが孤児user_idを保持")
    void 既存データを持つDBでV102_001がchanged_by_SET_NULL_FK撤廃onlyで安全に適用される() throws Exception {
        Flyway pre = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .target(MigrationVersion.fromVersion(PRE_V102_001_TARGET))
                .load();
        MigrateResult preResult = pre.migrate();
        assertThat(preResult.success).as("V101.002 までの適用が成功すること").isTrue();

        final long subjectUserId;   // 参加者本人（fk_rp_user RESTRICT 対象外）
        final long changerUserId;   // ステータス変更操作者（changed_by・撤廃対象）
        final long createdByUserId; // 募集枠作成者（fk_rl_created_by RESTRICT 対象外）
        final long historyId;
        try (Connection c = conn()) {
            assertThat(foreignKeyExists(c, "recruitment_participant_history", "fk_rph_changed_by"))
                    .as("V101.002 時点では fk_rph_changed_by が実在すること").isTrue();

            subjectUserId = insertUser(c, "rph-subject@example.com");
            changerUserId = insertUser(c, "rph-changer@example.com");
            createdByUserId = insertUser(c, "rph-listing-creator@example.com");
            long listingId = insertListing(c, createdByUserId);
            long participantId = insertParticipant(c, listingId, subjectUserId);
            historyId = insertHistory(c, participantId, listingId, changerUserId);
        }

        Flyway rest = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .load();
        MigrateResult restResult = rest.migrate();
        assertThat(restResult.success).as("V102.001 を含む残りのマイグレーションが成功すること").isTrue();

        try (Connection c = conn()) {
            // then-1: changed_by FK が撤廃された
            assertThat(foreignKeyExists(c, "recruitment_participant_history", "fk_rph_changed_by"))
                    .as("V102.001 で fk_rph_changed_by が撤廃されること").isFalse();

            assertThat(rowExists(c, "recruitment_participant_history", historyId))
                    .as("FK 撤廃後も既存遷移履歴行が生存していること").isTrue();

            // then-2（中核）: 変更操作者 user（changed_by のみで参照）を物理削除しても changed_by が NULL 化されず孤児値を保持
            deleteUserPhysically(c, changerUserId);
            assertThat(rowExists(c, "users", changerUserId))
                    .as("親 users 行（変更操作者）が物理削除されたこと").isFalse();
            assertThat(rowExists(c, "recruitment_participant_history", historyId))
                    .as("操作者 users 物理削除でも遷移履歴行が生存すること").isTrue();
            assertThat(longColumn(c, "recruitment_participant_history", "changed_by", historyId))
                    .as("changed_by が SET NULL されず孤児 user_id を保持すること（操作者証跡温存）").isEqualTo(changerUserId);
        }
    }

    // ── helpers ────────────────────────────────────────────────

    private long insertUser(Connection c, String email) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO users
                    (email, last_name, first_name, display_name, status, created_at, updated_at)
                VALUES (?, '履歴', '三郎', '履歴三郎', 'ACTIVE', NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, email);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long insertListing(Connection c, long createdBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO recruitment_listings
                    (scope_type, scope_id, category_id, title, participation_type,
                     start_at, end_at, application_deadline, auto_cancel_at,
                     capacity, min_capacity, created_by)
                VALUES ('USER', 1, 1, '遷移履歴監査FK撤廃テスト募集枠', 'INDIVIDUAL',
                        NOW() + INTERVAL 7 DAY, NOW() + INTERVAL 7 DAY + INTERVAL 2 HOUR,
                        NOW() + INTERVAL 5 DAY, NOW() + INTERVAL 4 DAY,
                        10, 2, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, createdBy);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long insertParticipant(Connection c, long listingId, long userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO recruitment_participants
                    (listing_id, participant_type, user_id, status, applied_at)
                VALUES (?, 'USER', ?, 'APPLIED', NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, listingId);
            ps.setLong(2, userId);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** ステータス遷移履歴行を挿入する。changed_by に変更操作者をセットして SET NULL FK 撤廃を検証する。 */
    private long insertHistory(Connection c, long participantId, long listingId, long changedBy)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO recruitment_participant_history
                    (participant_id, listing_id, old_status, new_status, changed_by, change_reason, changed_at)
                VALUES (?, ?, 'APPLIED', 'CONFIRMED', ?, 'MANUAL', NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, participantId);
            ps.setLong(2, listingId);
            ps.setLong(3, changedBy);
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
