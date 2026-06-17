package com.mannschaft.app.resident.migration;

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
 * <b>クロスドメインFK撤廃 第二陣E の番人テスト（property_listing_inquiries / fk_pli_user）。</b>
 *
 * <p>V100.001 で {@code property_listing_inquiries.fk_pli_user}
 * （user_id → users ON DELETE CASCADE・resident→user のクロスドメインFK）を撤廃し、
 * 併せて user_id バッキングインデックス {@code idx_pli_user} を新設する。
 * 本テストが守る不変条件:</p>
 * <ol>
 *   <li>V100.001 の直前（V99.001）まで適用 → users 親行＋問い合わせ行をシード。</li>
 *   <li>残り（V100.001 含む）を適用しても既存データを壊さず成功する。</li>
 *   <li><b>親 users 行を物理 DELETE しても問い合わせ行が CASCADE 削除されず生存し、
 *       user_id が孤児値として保持される</b>（＝退会リスナー先行削除への移行証明）。</li>
 *   <li>同一ドメイン CASCADE の {@code fk_pli_listing} は撤廃対象外＝残存。</li>
 *   <li>user_id バッキングインデックス {@code idx_pli_user} が新設されていること
 *       （FK 撤廃で消える user_id index をフルスキャン化させない回帰防止）。</li>
 * </ol>
 *
 * <p>方針: Spring を起動せず Testcontainers の実 MySQL 8.0 に {@link Flyway} を Java API で直接実行する。
 * property_listings の祖先（dwelling_units / teams）まで本物でシードするのは過剰なため、
 * 親 listing 行とその参照は {@code FOREIGN_KEY_CHECKS=0} で軽量挿入する
 * （本テストの検証対象は user FK の撤廃と user 物理削除の非 CASCADE であり、他親 FK の中身ではない）。
 * Docker 未起動環境では {@code @EnabledIf} でスキップ（骨抜きにしない・根治原則）。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.resident.migration.FlywayExistingDataPropertyListingInquiriesUserFkMigrationTest#isDockerAvailable")
@DisplayName("Flyway 既存データ property_listing_inquiries user CASCADE 撤廃（V100.001）番人テスト")
class FlywayExistingDataPropertyListingInquiriesUserFkMigrationTest {

    /** V100.001 の直前バージョン（origin/main 全体最大）。 */
    private static final String PRE_V100_001_TARGET = "99.001";

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_pli_user_fk")
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
    @DisplayName("既存問い合わせを持つDBにV100.001適用_FK撤廃_idx新設_親user物理削除でも子行が孤児user_idで生存")
    void 既存データを持つDBでV100_001がFK撤廃で安全に適用される() throws Exception {
        // given: V100.001 の直前（V99.001）まで適用 ＝ fk_pli_user はまだ生きている
        Flyway pre = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .target(MigrationVersion.fromVersion(PRE_V100_001_TARGET))
                .load();
        MigrateResult preResult = pre.migrate();
        assertThat(preResult.success).as("V99.001 までの適用が成功すること").isTrue();

        final long userId;
        final long inquiryId;
        try (Connection c = conn()) {
            // sanity: この時点では fk_pli_user が実在し、idx_pli_user はまだ存在しない
            assertThat(foreignKeyExists(c, "property_listing_inquiries", "fk_pli_user"))
                    .as("V99.001 時点では fk_pli_user が実在すること").isTrue();
            assertThat(indexExists(c, "property_listing_inquiries", "idx_pli_user"))
                    .as("V99.001 時点では idx_pli_user はまだ存在しないこと").isFalse();

            userId = insertUser(c, "inquirer@example.com");
            long listingId = insertListingLenient(c);
            inquiryId = insertInquiry(c, listingId, userId);
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
            // then-1: fk_pli_user が撤廃された
            assertThat(foreignKeyExists(c, "property_listing_inquiries", "fk_pli_user"))
                    .as("V100.001 で fk_pli_user が撤廃されること").isFalse();

            // then-2: 同一ドメイン CASCADE の fk_pli_listing は対象外＝残存
            assertThat(foreignKeyExists(c, "property_listing_inquiries", "fk_pli_listing"))
                    .as("同一ドメイン CASCADE fk_pli_listing は撤廃対象外で残存すること").isTrue();

            // then-3: user_id バッキングインデックス idx_pli_user が新設された
            assertThat(indexExists(c, "property_listing_inquiries", "idx_pli_user"))
                    .as("V100.001 で idx_pli_user が新設されること").isTrue();

            // then-4: 既存行は無傷で生存
            assertThat(rowExistsByLongId(c, "property_listing_inquiries", inquiryId))
                    .as("FK 撤廃後も既存 property_listing_inquiries 行が生存していること").isTrue();

            // then-5（中核）: 親 users 行を物理 DELETE しても子行は CASCADE 削除されず生存し、user_id が孤児値で保持
            deleteUserPhysically(c, userId);
            assertThat(rowExistsByLongId(c, "users", userId))
                    .as("親 users 行が物理削除されたこと").isFalse();
            assertThat(rowExistsByLongId(c, "property_listing_inquiries", inquiryId))
                    .as("親 users 物理削除でも子 property_listing_inquiries 行が CASCADE 削除されず生存すること").isTrue();
            assertThat(userIdOfRow(c, "property_listing_inquiries", inquiryId))
                    .as("子 property_listing_inquiries.user_id が孤児値として保持されること").isEqualTo(userId);
        }
    }

    // ── helpers ────────────────────────────────────────────────

    private long insertUser(Connection c, String email) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO users
                    (email, last_name, first_name, display_name, status, created_at, updated_at)
                VALUES (?, '問合', '太郎', '問合太郎', 'ACTIVE', NOW(), NOW())
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
     * property_listings 行を {@code FOREIGN_KEY_CHECKS=0} で軽量挿入する
     * （dwelling_units / users(listed_by) の祖先チェーンを本物でシードしない。
     * 本テストの検証対象は user FK の撤廃であり、listing の参照整合ではない）。
     */
    private long insertListingLenient(Connection c) throws SQLException {
        try (Statement off = c.createStatement()) {
            off.execute("SET FOREIGN_KEY_CHECKS=0");
        }
        long listingId;
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO property_listings
                    (dwelling_unit_id, listed_by, listing_type, title, status, created_at, updated_at)
                VALUES (999999, 999999, 'SALE', 'テスト物件', 'ACTIVE', NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                listingId = rs.getLong(1);
            }
        }
        try (Statement on = c.createStatement()) {
            on.execute("SET FOREIGN_KEY_CHECKS=1");
        }
        return listingId;
    }

    private long insertInquiry(Connection c, long listingId, long userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO property_listing_inquiries
                    (listing_id, user_id, message, created_at)
                VALUES (?, ?, '内見希望です', NOW())
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
