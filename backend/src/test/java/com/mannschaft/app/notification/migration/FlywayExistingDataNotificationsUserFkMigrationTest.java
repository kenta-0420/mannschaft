package com.mannschaft.app.notification.migration;

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
 * <b>クロスドメインFK撤廃 第二陣E の番人テスト（notifications / fk_notifications_user）。</b>
 *
 * <p>V100.001 で {@code notifications.fk_notifications_user}
 * （user_id → users ON DELETE CASCADE・notification→user のクロスドメインFK）を撤廃する。
 * 本テストが守る不変条件:</p>
 * <ol>
 *   <li>V100.001 の直前（V99.001）まで適用 → users 親行＋通知行をシード。</li>
 *   <li>残り（V100.001 含む）を適用しても既存データを壊さず成功する。</li>
 *   <li><b>親 users 行を物理 DELETE しても notifications 行が CASCADE 削除されず生存し、
 *       user_id が孤児値として保持される</b>（＝退会リスナー先行削除への移行証明。
 *       FK が残っていれば CASCADE 削除されるので本テストが落ちる）。</li>
 * </ol>
 *
 * <p>方針: Spring を起動せず Testcontainers の実 MySQL 8.0 に {@link Flyway} を Java API で直接実行する。
 * Docker 未起動環境では {@code @EnabledIf} でスキップ（骨抜きにしない・根治原則）。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.notification.migration.FlywayExistingDataNotificationsUserFkMigrationTest#isDockerAvailable")
@DisplayName("Flyway 既存データ notifications user CASCADE 撤廃（V100.001）番人テスト")
class FlywayExistingDataNotificationsUserFkMigrationTest {

    /** V100.001 の直前バージョン（origin/main 全体最大）。ここまで適用してから既存行をシードする。 */
    private static final String PRE_V100_001_TARGET = "99.001";

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_notifications_user_fk")
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
    @DisplayName("既存通知を持つDBにV100.001適用_FK撤廃_親user物理削除でも子行が孤児user_idで生存")
    void 既存データを持つDBでV100_001がFK撤廃で安全に適用される() throws Exception {
        // given: V100.001 の直前（V99.001）まで適用 ＝ fk_notifications_user はまだ生きている
        Flyway pre = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .target(MigrationVersion.fromVersion(PRE_V100_001_TARGET))
                .load();
        MigrateResult preResult = pre.migrate();
        assertThat(preResult.success).as("V99.001 までの適用が成功すること").isTrue();

        final long userId;
        final long notificationId;
        try (Connection c = conn()) {
            // sanity: この時点では fk_notifications_user が実在する（撤廃前スキーマの証明）
            assertThat(foreignKeyExists(c, "notifications", "fk_notifications_user"))
                    .as("V99.001 時点では fk_notifications_user が実在すること").isTrue();

            userId = insertUser(c, "notification-recipient@example.com");
            notificationId = insertNotification(c, userId);
        }

        // when: 残りのマイグレーション（V100.001 含む）を適用する
        Flyway rest = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .load();
        MigrateResult restResult = rest.migrate();
        assertThat(restResult.success).as("V100.001 を含む残りのマイグレーションが成功すること").isTrue();

        try (Connection c = conn()) {
            // then-1: fk_notifications_user が撤廃された
            assertThat(foreignKeyExists(c, "notifications", "fk_notifications_user"))
                    .as("V100.001 で fk_notifications_user が撤廃されること").isFalse();

            // 注: fk_notifications_actor は本テスト作成時(第二陣E/V100)は対象外だったが、
            //     第三陣E V106.001 で撤廃される。本テストは全migration適用後に検証するため
            //     「残存」対照は成立しなくなった→当該sanityを除去（本テストの主眼=fk_notifications_user撤廃+孤児保持は不変）。

            // then-2: 既存行は無傷で生存
            assertThat(rowExistsByLongId(c, "notifications", notificationId))
                    .as("FK 撤廃後も既存 notifications 行が生存していること").isTrue();

            // then-3（中核）: 親 users 行を物理 DELETE しても子行は CASCADE 削除されず生存し、
            //                user_id が孤児値として保持される
            deleteUserPhysically(c, userId);
            assertThat(rowExistsByLongId(c, "users", userId))
                    .as("親 users 行が物理削除されたこと").isFalse();
            assertThat(rowExistsByLongId(c, "notifications", notificationId))
                    .as("親 users 物理削除でも子 notifications 行が CASCADE 削除されず生存すること").isTrue();
            assertThat(userIdOfRow(c, "notifications", notificationId))
                    .as("子 notifications.user_id が孤児値として保持されること").isEqualTo(userId);
        }
    }

    // ── helpers ────────────────────────────────────────────────

    private long insertUser(Connection c, String email) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO users
                    (email, last_name, first_name, display_name, status, created_at, updated_at)
                VALUES (?, '通知', '太郎', '通知太郎', 'ACTIVE', NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, email);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long insertNotification(Connection c, long userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO notifications
                    (user_id, notification_type, title, source_type, scope_type, created_at)
                VALUES (?, 'SYSTEM', 'お知らせ', 'SYSTEM', 'USER', NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, userId);
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

    private static long userIdOfRow(Connection c, String table, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT user_id FROM " + table + " WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
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
