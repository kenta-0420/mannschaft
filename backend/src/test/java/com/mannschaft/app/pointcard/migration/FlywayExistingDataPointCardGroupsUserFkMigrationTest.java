package com.mannschaft.app.pointcard.migration;

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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>クロスドメインFK撤廃 第二陣C の番人テスト（point_card_groups / fk_pcg_user）。</b>
 *
 * <p>V98.001 で {@code point_card_groups.fk_pcg_user}
 * （user_id → users ON DELETE CASCADE・pointcard→user のクロスドメインFK）を撤廃する。
 * グループは退会30日後（AccountPurgedEvent）にリスナーが先行削除する区分。
 * 本テストが守る不変条件:</p>
 * <ol>
 *   <li>V98.001 の直前（V97.001）まで適用 → users 親行＋カードグループ行をシード。</li>
 *   <li>残り（V98.001 含む）を適用しても既存データを壊さず成功する。</li>
 *   <li><b>親 users 行を物理 DELETE しても point_card_groups 行が CASCADE 削除されず生存し、
 *       user_id が孤児値として保持される</b>（FK が残っていれば CASCADE 削除されてしまうので本テストが落ちる）。</li>
 * </ol>
 *
 * <p>方針: Spring を起動せず Testcontainers の実 MySQL 8.0 に {@link Flyway} を Java API で直接実行する。
 * Docker 未起動環境では {@code @EnabledIf} でスキップ（骨抜きにしない・根治原則）。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.pointcard.migration.FlywayExistingDataPointCardGroupsUserFkMigrationTest#isDockerAvailable")
@DisplayName("Flyway 既存データ point_card_groups user CASCADE 撤廃（V98.001）番人テスト")
class FlywayExistingDataPointCardGroupsUserFkMigrationTest {

    /** V98.001 の直前バージョン（origin/main 全体最大）。 */
    private static final String PRE_V98_001_TARGET = "97.001";

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_point_card_groups_user_fk")
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
    @DisplayName("既存カードグループを持つDBにV98.001適用_FK撤廃_親user物理削除でも子行が孤児user_idで生存")
    void 既存データを持つDBでV98_001がFK撤廃で安全に適用される() throws Exception {
        // given: V98.001 の直前（V97.001）まで適用 ＝ fk_pcg_user はまだ生きている
        Flyway pre = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .target(MigrationVersion.fromVersion(PRE_V98_001_TARGET))
                .load();
        MigrateResult preResult = pre.migrate();
        assertThat(preResult.success).as("V97.001 までの適用が成功すること").isTrue();

        final long userId;
        final String groupId = UUID.randomUUID().toString();
        try (Connection c = conn()) {
            assertThat(foreignKeyExists(c, "point_card_groups", "fk_pcg_user"))
                    .as("V97.001 時点では fk_pcg_user が実在すること").isTrue();

            userId = insertUser(c, "card-group@example.com");
            insertPointCardGroup(c, groupId, userId);
        }

        // when: 残りのマイグレーション（V98.001 含む）を適用する
        Flyway rest = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .load();
        MigrateResult restResult = rest.migrate();
        assertThat(restResult.success).as("V98.001 を含む残りのマイグレーションが成功すること").isTrue();

        try (Connection c = conn()) {
            assertThat(foreignKeyExists(c, "point_card_groups", "fk_pcg_user"))
                    .as("V98.001 で fk_pcg_user が撤廃されること").isFalse();

            assertThat(rowExistsByCharId(c, "point_card_groups", groupId))
                    .as("FK 撤廃後も既存 point_card_groups 行が生存していること").isTrue();

            deleteUserPhysically(c, userId);
            assertThat(rowExistsByLongId(c, "users", userId))
                    .as("親 users 行が物理削除されたこと").isFalse();
            assertThat(rowExistsByCharId(c, "point_card_groups", groupId))
                    .as("親 users 物理削除でも子 point_card_groups 行が CASCADE 削除されず生存すること").isTrue();
            assertThat(userIdOfCharRow(c, "point_card_groups", groupId))
                    .as("子 point_card_groups.user_id が孤児値として保持されること").isEqualTo(userId);
        }
    }

    // ── helpers ────────────────────────────────────────────────

    private long insertUser(Connection c, String email) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO users
                    (email, last_name, first_name, display_name, status, created_at, updated_at)
                VALUES (?, 'グループ', '花子', 'グループ花子', 'ACTIVE', NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, email);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private void insertPointCardGroup(Connection c, String groupId, long userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO point_card_groups
                    (id, user_id, name, emoji, display_order, created_at, updated_at)
                VALUES (?, ?, '買い物用', '🛒', 0, NOW(6), NOW(6))
                """)) {
            ps.setString(1, groupId);
            ps.setLong(2, userId);
            ps.executeUpdate();
        }
    }

    private void deleteUserPhysically(Connection c, long userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM users WHERE id = ?")) {
            ps.setLong(1, userId);
            ps.executeUpdate();
        }
    }

    private static long userIdOfCharRow(Connection c, String table, String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT user_id FROM " + table + " WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static boolean rowExistsByCharId(Connection c, String table, String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM " + table + " WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1) > 0;
            }
        }
    }

    private static boolean rowExistsByLongId(Connection c, String table, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM " + table + " WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1) > 0;
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
