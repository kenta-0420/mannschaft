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
 * <b>クロスドメインFK撤廃 第二陣E の番人テスト
 * （emergency_closure_confirmations / fk_ecc_user）。</b>
 *
 * <p>V100.001 で {@code emergency_closure_confirmations.fk_ecc_user}
 * （user_id → users ON DELETE CASCADE・reservation→user のクロスドメインFK）を撤廃し、
 * 併せて user_id バッキングインデックス {@code idx_ecc_user} を新設する。
 * 本テストが守る不変条件:</p>
 * <ol>
 *   <li>V100.001 の直前（V99.001）まで適用 → users 親行＋緊急休業確認行をシード。</li>
 *   <li>残り（V100.001 含む）を適用しても既存データを壊さず成功する。</li>
 *   <li><b>親 users 行を物理 DELETE しても確認行が CASCADE 削除されず生存し、
 *       user_id が孤児値として保持される</b>（＝退会リスナー先行削除への移行証明）。</li>
 *   <li>同一ドメイン CASCADE の {@code fk_ecc_closure} は撤廃対象外＝残存。</li>
 *   <li>user_id バッキングインデックス {@code idx_ecc_user} が新設されていること。</li>
 * </ol>
 *
 * <p>方針: Spring を起動せず Testcontainers の実 MySQL 8.0 に {@link Flyway} を Java API で直接実行する。
 * emergency_closures の祖先（teams）まで本物でシードするのは過剰なため、親 closure 行は
 * {@code FOREIGN_KEY_CHECKS=0} で軽量挿入する（検証対象は user FK の撤廃であり他親 FK の中身ではない）。
 * Docker 未起動環境では {@code @EnabledIf} でスキップ（骨抜きにしない・根治原則）。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.reservation.migration.FlywayExistingDataEmergencyClosureConfirmationsUserFkMigrationTest#isDockerAvailable")
@DisplayName("Flyway 既存データ emergency_closure_confirmations user CASCADE 撤廃（V100.001）番人テスト")
class FlywayExistingDataEmergencyClosureConfirmationsUserFkMigrationTest {

    /** V100.001 の直前バージョン（origin/main 全体最大）。 */
    private static final String PRE_V100_001_TARGET = "99.001";

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_ecc_user_fk")
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
    @DisplayName("既存緊急休業確認を持つDBにV100.001適用_FK撤廃_idx新設_親user物理削除でも子行が孤児user_idで生存")
    void 既存データを持つDBでV100_001がFK撤廃で安全に適用される() throws Exception {
        // given: V100.001 の直前（V99.001）まで適用 ＝ fk_ecc_user はまだ生きている
        Flyway pre = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .target(MigrationVersion.fromVersion(PRE_V100_001_TARGET))
                .load();
        MigrateResult preResult = pre.migrate();
        assertThat(preResult.success).as("V99.001 までの適用が成功すること").isTrue();

        final long userId;
        final long confirmationId;
        try (Connection c = conn()) {
            // sanity: この時点では fk_ecc_user が実在し、idx_ecc_user はまだ存在しない
            assertThat(foreignKeyExists(c, "emergency_closure_confirmations", "fk_ecc_user"))
                    .as("V99.001 時点では fk_ecc_user が実在すること").isTrue();
            assertThat(indexExists(c, "emergency_closure_confirmations", "idx_ecc_user"))
                    .as("V99.001 時点では idx_ecc_user はまだ存在しないこと").isFalse();

            userId = insertUser(c, "ecc-user@example.com");
            long closureId = insertClosureLenient(c);
            confirmationId = insertConfirmation(c, closureId, userId);
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
            // then-1: fk_ecc_user が撤廃された
            assertThat(foreignKeyExists(c, "emergency_closure_confirmations", "fk_ecc_user"))
                    .as("V100.001 で fk_ecc_user が撤廃されること").isFalse();

            // then-2: 同一ドメイン CASCADE の fk_ecc_closure は対象外＝残存
            assertThat(foreignKeyExists(c, "emergency_closure_confirmations", "fk_ecc_closure"))
                    .as("同一ドメイン CASCADE fk_ecc_closure は撤廃対象外で残存すること").isTrue();

            // then-3: user_id バッキングインデックス idx_ecc_user が新設された
            assertThat(indexExists(c, "emergency_closure_confirmations", "idx_ecc_user"))
                    .as("V100.001 で idx_ecc_user が新設されること").isTrue();

            // then-4: 既存行は無傷で生存
            assertThat(rowExistsByLongId(c, "emergency_closure_confirmations", confirmationId))
                    .as("FK 撤廃後も既存 emergency_closure_confirmations 行が生存していること").isTrue();

            // then-5（中核）: 親 users 行を物理 DELETE しても子行は CASCADE 削除されず生存し、user_id が孤児値で保持
            deleteUserPhysically(c, userId);
            assertThat(rowExistsByLongId(c, "users", userId))
                    .as("親 users 行が物理削除されたこと").isFalse();
            assertThat(rowExistsByLongId(c, "emergency_closure_confirmations", confirmationId))
                    .as("親 users 物理削除でも子 emergency_closure_confirmations 行が CASCADE 削除されず生存すること").isTrue();
            assertThat(userIdOfRow(c, "emergency_closure_confirmations", confirmationId))
                    .as("子 emergency_closure_confirmations.user_id が孤児値として保持されること").isEqualTo(userId);
        }
    }

    // ── helpers ────────────────────────────────────────────────

    private long insertUser(Connection c, String email) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO users
                    (email, last_name, first_name, display_name, status, created_at, updated_at)
                VALUES (?, '休業', '太郎', '休業太郎', 'ACTIVE', NOW(), NOW())
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
     * emergency_closures 行を {@code FOREIGN_KEY_CHECKS=0} で軽量挿入する
     * （teams(team_id) / users(created_by) の祖先チェーンを本物でシードしない）。
     */
    private long insertClosureLenient(Connection c) throws SQLException {
        try (Statement off = c.createStatement()) {
            off.execute("SET FOREIGN_KEY_CHECKS=0");
        }
        long closureId;
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO emergency_closures
                    (team_id, start_date, end_date, reason, subject, message_body, created_by, created_at, updated_at)
                VALUES (999999, CURDATE(), CURDATE(), '臨時休業', '臨時休業のお知らせ', '本文', 999999, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                closureId = rs.getLong(1);
            }
        }
        try (Statement on = c.createStatement()) {
            on.execute("SET FOREIGN_KEY_CHECKS=1");
        }
        return closureId;
    }

    private long insertConfirmation(Connection c, long closureId, long userId) throws SQLException {
        // reservation_id は NOT NULL だが FK なし（同一ドメイン内の論理参照）。任意の値で可。
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO emergency_closure_confirmations
                    (emergency_closure_id, user_id, reservation_id, appointment_at, created_at, updated_at)
                VALUES (?, ?, 12345, NOW(), NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, closureId);
            ps.setLong(2, userId);
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
