package com.mannschaft.app.promotion.migration;

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
 * <b>クロスドメインFK撤廃 第二陣A の番人テスト（coupon_distributions / fk_cd_user）。</b>
 *
 * <p>V96.001 で {@code coupon_distributions.fk_cd_user}（user_id → users ON DELETE CASCADE・
 * promotion→user のクロスドメインFK）を撤廃only する。本テストが守る不変条件:</p>
 * <ol>
 *   <li>V96.001 の直前（V95.001）まで適用 → users 親行＋クーポン＋クーポン配布行をシード。</li>
 *   <li>残り（V96.001 含む）を適用しても既存データを壊さず成功する。</li>
 *   <li><b>配布先 users 行を物理 DELETE しても coupon_distributions 行が CASCADE 削除されず生存し、
 *       user_id が孤児値として保持される</b>（＝退会30日後の users 物理削除でも配布統計が温存される
 *       ことの恒久的回帰防止）。</li>
 * </ol>
 *
 * <p>coupons.created_by は ON DELETE RESTRICT のため、配布先 user とは別の作成者 user を用意し、
 * 配布先 user のみを物理削除する（RESTRICT に阻まれず CASCADE 撤廃の効果だけを検証するため）。</p>
 *
 * <p>方針: Spring を起動せず Testcontainers の実 MySQL 8.0 に {@link Flyway} を Java API で直接実行する。
 * Docker 未起動環境では {@code @EnabledIf} でスキップ（骨抜きにしない・根治原則）。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.promotion.migration.FlywayExistingDataCouponDistributionsUserFkMigrationTest#isDockerAvailable")
@DisplayName("Flyway 既存データ coupon_distributions user CASCADE 撤廃（V96.001）番人テスト")
class FlywayExistingDataCouponDistributionsUserFkMigrationTest {

    /** V96.001 の直前バージョン（origin/main 全体最大）。ここまで適用してから既存行をシードする。 */
    private static final String PRE_V96_001_TARGET = "95.001";

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_coupon_dist_user_fk")
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
    @DisplayName("既存クーポン配布行を持つDBにV96.001適用_FK撤廃_配布先user物理削除でも子行が孤児user_idで生存")
    void 既存データを持つDBでV96_001がFK撤廃onlyで安全に適用される() throws Exception {
        // given: V96.001 の直前（V95.001）まで適用 ＝ fk_cd_user はまだ生きている
        Flyway pre = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .target(MigrationVersion.fromVersion(PRE_V96_001_TARGET))
                .load();
        MigrateResult preResult = pre.migrate();
        assertThat(preResult.success).as("V95.001 までの適用が成功すること").isTrue();

        final long targetUserId;
        final long distributionId;
        try (Connection c = conn()) {
            // sanity: この時点では fk_cd_user が実在する（撤廃前スキーマの証明）
            assertThat(foreignKeyExists(c, "coupon_distributions", "fk_cd_user"))
                    .as("V95.001 時点では fk_cd_user が実在すること").isTrue();

            // coupons.created_by は RESTRICT のため、配布先とは別の作成者 user を用意する
            long creatorUserId = insertUser(c, "coupon-creator@example.com");
            targetUserId = insertUser(c, "coupon-recipient@example.com");
            long couponId = insertCoupon(c, creatorUserId);
            distributionId = insertDistribution(c, couponId, targetUserId);
        }

        // when: 残りのマイグレーション（V96.001 含む）を適用する
        Flyway rest = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .load();
        MigrateResult restResult = rest.migrate();
        assertThat(restResult.success).as("V96.001 を含む残りのマイグレーションが成功すること").isTrue();

        try (Connection c = conn()) {
            // then-1: fk_cd_user が撤廃された
            assertThat(foreignKeyExists(c, "coupon_distributions", "fk_cd_user"))
                    .as("V96.001 で fk_cd_user が撤廃されること").isFalse();

            // then-2: 既存行は無傷で生存
            assertThat(distributionExists(c, distributionId))
                    .as("FK 撤廃後も既存 coupon_distributions 行が生存していること").isTrue();

            // then-3（中核）: 配布先 users 行を物理 DELETE しても子 coupon_distributions 行は
            //                CASCADE 削除されず生存し、user_id が孤児値として保持される（＝統計温存）
            deleteUserPhysically(c, targetUserId);
            assertThat(userExists(c, targetUserId))
                    .as("配布先 users 行が物理削除されたこと").isFalse();
            assertThat(distributionExists(c, distributionId))
                    .as("配布先 users 物理削除でも子 coupon_distributions 行が CASCADE 削除されず生存すること（統計温存）")
                    .isTrue();
            assertThat(userIdOfDistribution(c, distributionId))
                    .as("子 coupon_distributions.user_id が孤児値として保持されること").isEqualTo(targetUserId);
        }
    }

    // ── helpers ────────────────────────────────────────────────

    private long insertUser(Connection c, String email) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO users
                    (email, last_name, first_name, display_name, status, created_at, updated_at)
                VALUES (?, 'クーポン', '太郎', 'クーポン太郎', 'ACTIVE', NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, email);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long insertCoupon(Connection c, long createdBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO coupons
                    (scope_type, scope_id, created_by, title, coupon_type, valid_from, valid_until)
                VALUES ('TEAM', 1, ?, 'テストクーポン', 'PERCENTAGE', NOW(), DATE_ADD(NOW(), INTERVAL 30 DAY))
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, createdBy);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long insertDistribution(Connection c, long couponId, long userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO coupon_distributions
                    (coupon_id, user_id, distributed_at, expires_at)
                VALUES (?, ?, NOW(), DATE_ADD(NOW(), INTERVAL 30 DAY))
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, couponId);
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

    private static boolean userExists(Connection c, long userId) throws SQLException {
        return countById(c, "users", userId) > 0;
    }

    private static boolean distributionExists(Connection c, long distributionId) throws SQLException {
        return countById(c, "coupon_distributions", distributionId) > 0;
    }

    private static long userIdOfDistribution(Connection c, long distributionId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT user_id FROM coupon_distributions WHERE id = ?")) {
            ps.setLong(1, distributionId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static long countById(Connection c, String table, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM " + table + " WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
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
