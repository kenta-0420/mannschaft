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
 * <b>クロスドメインFK撤廃 第三陣A の番人テスト（recruitment_listings / fk_rl_cancelled_by）。</b>
 *
 * <p>V102.001 で {@code recruitment_listings.fk_rl_cancelled_by}（cancelled_by → users ON DELETE SET NULL・
 * recruitment→user のクロスドメイン監査列FK）を撤廃only する。本テストが守る不変条件:</p>
 * <ol>
 *   <li>V102.001 の直前（V101.002）まで適用 → users 親行＋募集枠行（created_by = cancelled_by = 当該 user）をシード。</li>
 *   <li>残り（V102.001 含む）を適用しても既存データを壊さず成功する。</li>
 *   <li>V102.001 で fk_rl_cancelled_by が撤廃される。一方 fk_rl_created_by（RESTRICT・対象外）は残る。</li>
 *   <li><b>fk_rl_created_by が RESTRICT のままなので、created_by に当該 user を持つ行があると users 物理削除は拒否される。</b>
 *       これを確認した上で created_by を別 user に付け替えてから物理削除し、
 *       <b>cancelled_by が NULL 化されず孤児 user_id を保持する</b>ことを検証（＝監査履歴温存・SET NULL 撤廃only の肝）。</li>
 * </ol>
 *
 * <p>方針: Spring を起動せず Testcontainers の実 MySQL 8.0 に {@link Flyway} を Java API で直接実行する。
 * Docker 未起動環境では {@code @EnabledIf} でスキップ（骨抜きにしない・根治原則）。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.recruitment.migration.FlywayExistingDataRecruitmentListingsSetNullFkMigrationTest#isDockerAvailable")
@DisplayName("Flyway 既存データ recruitment_listings cancelled_by SET NULL FK撤廃（V102.001）番人テスト")
class FlywayExistingDataRecruitmentListingsSetNullFkMigrationTest {

    private static final String PRE_V102_001_TARGET = "101.002";

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_rl_setnull_fk")
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
    @DisplayName("既存募集枠行を持つDBにV102.001適用_cancelled_by_SET_NULL_FK撤廃_親user物理削除でもcancelled_byが孤児user_idを保持")
    void 既存データを持つDBでV102_001がcancelled_by_SET_NULL_FK撤廃onlyで安全に適用される() throws Exception {
        Flyway pre = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .target(MigrationVersion.fromVersion(PRE_V102_001_TARGET))
                .load();
        MigrateResult preResult = pre.migrate();
        assertThat(preResult.success).as("V101.002 までの適用が成功すること").isTrue();

        final long cancellerUserId;
        final long otherUserId;
        final long listingId;
        try (Connection c = conn()) {
            assertThat(foreignKeyExists(c, "recruitment_listings", "fk_rl_cancelled_by"))
                    .as("V101.002 時点では fk_rl_cancelled_by が実在すること").isTrue();

            cancellerUserId = insertUser(c, "rl-canceller@example.com");
            otherUserId = insertUser(c, "rl-other-creator@example.com");
            // created_by も cancelled_by も当該 canceller user にして、撤廃対象の cancelled_by を検証
            listingId = insertListing(c, cancellerUserId, cancellerUserId);
        }

        Flyway rest = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .load();
        MigrateResult restResult = rest.migrate();
        assertThat(restResult.success).as("V102.001 を含む残りのマイグレーションが成功すること").isTrue();

        try (Connection c = conn()) {
            // then-1: cancelled_by FK は撤廃され、created_by FK（RESTRICT・対象外）は残る
            assertThat(foreignKeyExists(c, "recruitment_listings", "fk_rl_cancelled_by"))
                    .as("V102.001 で fk_rl_cancelled_by が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "recruitment_listings", "fk_rl_created_by"))
                    .as("対象外の fk_rl_created_by（RESTRICT）は残ること").isTrue();

            assertThat(rowExists(c, "recruitment_listings", listingId))
                    .as("FK 撤廃後も既存募集枠行が生存していること").isTrue();

            // then-2: fk_rl_created_by が RESTRICT のままなので、当該 user を created_by に持つ間は物理削除が拒否される
            assertThat(deleteUserFails(c, cancellerUserId))
                    .as("created_by RESTRICT のため、当該 user の物理削除は拒否されること").isTrue();

            // created_by を別 user に付け替えてから物理削除する（cancelled_by はそのまま canceller user を保持）
            updateListingCreatedBy(c, listingId, otherUserId);
            deleteUserPhysically(c, cancellerUserId);
            assertThat(rowExists(c, "users", cancellerUserId))
                    .as("親 users 行（canceller）が物理削除されたこと").isFalse();

            // then-3（中核）: cancelled_by が SET NULL されず孤児 user_id を保持する
            assertThat(rowExists(c, "recruitment_listings", listingId))
                    .as("親 users 物理削除でも募集枠行が生存すること").isTrue();
            assertThat(longColumn(c, "recruitment_listings", "cancelled_by", listingId))
                    .as("cancelled_by が SET NULL されず孤児 user_id を保持すること（監査履歴温存）")
                    .isEqualTo(cancellerUserId);
        }
    }

    // ── helpers ────────────────────────────────────────────────

    private long insertUser(Connection c, String email) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO users
                    (email, last_name, first_name, display_name, status, created_at, updated_at)
                VALUES (?, '募集', '一郎', '募集一郎', 'ACTIVE', NOW(), NOW())
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
     * 募集枠行を挿入する。category_id=1 は V3.116 のシード（futsal_open）。
     * 日時は CHECK 制約（deadline &lt; start, auto_cancel &le; deadline, start &lt; end）を満たすよう設定。
     */
    private long insertListing(Connection c, long createdBy, long cancelledBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO recruitment_listings
                    (scope_type, scope_id, category_id, title, participation_type,
                     start_at, end_at, application_deadline, auto_cancel_at,
                     capacity, min_capacity, created_by, cancelled_by)
                VALUES ('USER', 1, 1, '監査FK撤廃テスト募集枠', 'INDIVIDUAL',
                        NOW() + INTERVAL 7 DAY, NOW() + INTERVAL 7 DAY + INTERVAL 2 HOUR,
                        NOW() + INTERVAL 5 DAY, NOW() + INTERVAL 4 DAY,
                        10, 2, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, createdBy);
            ps.setLong(2, cancelledBy);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private void updateListingCreatedBy(Connection c, long listingId, long newCreatedBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE recruitment_listings SET created_by = ? WHERE id = ?")) {
            ps.setLong(1, newCreatedBy);
            ps.setLong(2, listingId);
            ps.executeUpdate();
        }
    }

    private void deleteUserPhysically(Connection c, long userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM users WHERE id = ?")) {
            ps.setLong(1, userId);
            ps.executeUpdate();
        }
    }

    /** users 物理削除が RESTRICT FK で拒否されるか。拒否（SQLException）なら true。 */
    private boolean deleteUserFails(Connection c, long userId) {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM users WHERE id = ?")) {
            ps.setLong(1, userId);
            ps.executeUpdate();
            return false;
        } catch (SQLException e) {
            return true;
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
