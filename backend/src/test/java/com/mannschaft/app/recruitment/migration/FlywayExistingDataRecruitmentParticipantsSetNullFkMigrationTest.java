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
 * <b>クロスドメインFK撤廃 第三陣A の番人テスト（recruitment_participants）。</b>
 *
 * <p>V102.001 で {@code recruitment_participants} の users 親 ON DELETE SET NULL クロスドメイン監査列FK 2件を撤廃only する:</p>
 * <ul>
 *   <li>{@code fk_rp_applied_by}（applied_by → users SET NULL・代理申込の操作者）</li>
 *   <li>{@code fk_rp_cancelled_by}（cancelled_by → users SET NULL・キャンセルの操作者）</li>
 * </ul>
 *
 * <p>本テストが守る不変条件:</p>
 * <ol>
 *   <li>V102.001 の直前（V101.002）まで適用 → 募集枠＋USER 参加者行（applied_by/cancelled_by に監査 user の id）をシード。</li>
 *   <li>残り（V102.001 含む）を適用しても既存データを壊さず成功する。</li>
 *   <li>V102.001 で fk_rp_applied_by / fk_rp_cancelled_by がともに撤廃される。fk_rp_user（RESTRICT・対象外）は残る。</li>
 *   <li><b>親 users 行（監査 user）を物理 DELETE しても applied_by / cancelled_by が NULL 化されず孤児 user_id 値を保持する</b>
 *       （＝SET NULL 撤廃only の肝・操作者監査履歴温存）。</li>
 * </ol>
 *
 * <p>方針: Spring を起動せず Testcontainers の実 MySQL 8.0 に {@link Flyway} を Java API で直接実行する。
 * Docker 未起動環境では {@code @EnabledIf} でスキップ（骨抜きにしない・根治原則）。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.recruitment.migration.FlywayExistingDataRecruitmentParticipantsSetNullFkMigrationTest#isDockerAvailable")
@DisplayName("Flyway 既存データ recruitment_participants applied_by/cancelled_by SET NULL FK撤廃（V102.001）番人テスト")
class FlywayExistingDataRecruitmentParticipantsSetNullFkMigrationTest {

    private static final String PRE_V102_001_TARGET = "101.002";

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_rp_setnull_fk")
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
    @DisplayName("既存参加者行を持つDBにV102.001適用_applied_by/cancelled_by_SET_NULL_FK2件撤廃_親user物理削除でも監査列が孤児user_idを保持")
    void 既存データを持つDBでV102_001がapplied_by_cancelled_by_SET_NULL_FK撤廃onlyで安全に適用される() throws Exception {
        Flyway pre = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .target(MigrationVersion.fromVersion(PRE_V102_001_TARGET))
                .load();
        MigrateResult preResult = pre.migrate();
        assertThat(preResult.success).as("V101.002 までの適用が成功すること").isTrue();

        final long subjectUserId;   // 参加者本人（user_id・fk_rp_user RESTRICT 対象外）
        final long auditUserId;     // 申込/キャンセル操作者（applied_by/cancelled_by・撤廃対象）
        final long createdByUserId; // 募集枠作成者（fk_rl_created_by RESTRICT 対象外）
        final long participantId;
        try (Connection c = conn()) {
            assertThat(foreignKeyExists(c, "recruitment_participants", "fk_rp_applied_by"))
                    .as("V101.002 時点では fk_rp_applied_by が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "recruitment_participants", "fk_rp_cancelled_by"))
                    .as("V101.002 時点では fk_rp_cancelled_by が実在すること").isTrue();

            subjectUserId = insertUser(c, "rp-subject@example.com");
            auditUserId = insertUser(c, "rp-operator@example.com");
            createdByUserId = insertUser(c, "rp-listing-creator@example.com");
            long listingId = insertListing(c, createdByUserId);
            // USER 参加者: user_id=subject（RESTRICT 対象外）, applied_by=cancelled_by=auditUser（撤廃対象）
            participantId = insertParticipant(c, listingId, subjectUserId, auditUserId, auditUserId);
        }

        Flyway rest = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .load();
        MigrateResult restResult = rest.migrate();
        assertThat(restResult.success).as("V102.001 を含む残りのマイグレーションが成功すること").isTrue();

        try (Connection c = conn()) {
            // then-1: 監査列 FK が撤廃される。
            //   fk_rp_user（user_id RESTRICT → users）の残存対照は、最終局面 5c(V116.001) で当該 FK を撤廃するため除去。
            //   ※ 本テストの主眼（applied_by/cancelled_by SET NULL 撤廃onlyで監査列が孤児値を保持すること）は不変。
            //     本テストが物理削除するのは操作者 user（auditUserId）のみで subject user は削除しないため、fk_rp_user の撤廃は検証経路に影響しない。
            assertThat(foreignKeyExists(c, "recruitment_participants", "fk_rp_applied_by"))
                    .as("V102.001 で fk_rp_applied_by が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "recruitment_participants", "fk_rp_cancelled_by"))
                    .as("V102.001 で fk_rp_cancelled_by が撤廃されること").isFalse();

            assertThat(rowExists(c, "recruitment_participants", participantId))
                    .as("FK 撤廃後も既存参加者行が生存していること").isTrue();

            // then-2（中核）: 操作者 user（applied_by/cancelled_by のみで参照）を物理削除しても監査列が NULL 化されず孤児値を保持
            deleteUserPhysically(c, auditUserId);
            assertThat(rowExists(c, "users", auditUserId))
                    .as("親 users 行（操作者）が物理削除されたこと").isFalse();
            assertThat(rowExists(c, "recruitment_participants", participantId))
                    .as("操作者 users 物理削除でも参加者行が生存すること").isTrue();
            assertThat(longColumn(c, "recruitment_participants", "applied_by", participantId))
                    .as("applied_by が SET NULL されず孤児 user_id を保持すること（監査履歴温存）").isEqualTo(auditUserId);
            assertThat(longColumn(c, "recruitment_participants", "cancelled_by", participantId))
                    .as("cancelled_by が SET NULL されず孤児 user_id を保持すること（監査履歴温存）").isEqualTo(auditUserId);
        }
    }

    // ── helpers ────────────────────────────────────────────────

    private long insertUser(Connection c, String email) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO users
                    (email, last_name, first_name, display_name, status, created_at, updated_at)
                VALUES (?, '参加', '次郎', '参加次郎', 'ACTIVE', NOW(), NOW())
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
                VALUES ('USER', 1, 1, '参加者監査FK撤廃テスト募集枠', 'INDIVIDUAL',
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

    /**
     * USER 型参加者を挿入する。chk_rp_subject（USER なら user_id 非NULL・team_id NULL）を満たす。
     * applied_by / cancelled_by に監査 user をセットして両 SET NULL FK 撤廃を検証する。
     */
    private long insertParticipant(Connection c, long listingId, long userId, long appliedBy, long cancelledBy)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO recruitment_participants
                    (listing_id, participant_type, user_id, applied_by, cancelled_by, status, applied_at)
                VALUES (?, 'USER', ?, ?, ?, 'CANCELLED', NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, listingId);
            ps.setLong(2, userId);
            ps.setLong(3, appliedBy);
            ps.setLong(4, cancelledBy);
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
