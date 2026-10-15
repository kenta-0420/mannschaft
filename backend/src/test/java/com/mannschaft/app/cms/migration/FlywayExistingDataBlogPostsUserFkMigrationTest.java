package com.mannschaft.app.cms.migration;

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
 * <b>クロスドメインFK撤廃 第二陣A の番人テスト（blog_posts / fk_bp_user）。</b>
 *
 * <p>V96.001 で {@code blog_posts.fk_bp_user}（user_id → users ON DELETE CASCADE・cms→user の
 * クロスドメインFK）を撤廃only する。本テストが守る不変条件:</p>
 * <ol>
 *   <li>V96.001 の直前（V95.001）まで適用 → users 親行＋個人スコープ blog_posts 行をシード。</li>
 *   <li>残り（V96.001 含む）を適用しても既存データを壊さず成功する。</li>
 *   <li><b>親 users 行を物理 DELETE しても blog_posts 行が CASCADE 削除されず生存し、
 *       user_id が孤児値として保持される</b>（＝退会30日後の users 物理削除でも投稿統計が温存される
 *       ことの恒久的回帰防止）。</li>
 *   <li>孤児 user_id 行でも CHECK 制約 {@code chk_bp_scope}（team/org/user の XOR）を満たし続ける。</li>
 * </ol>
 *
 * <p>方針: Spring を起動せず Testcontainers の実 MySQL 8.0 に {@link Flyway} を Java API で直接実行する。
 * Docker 未起動環境では {@code @EnabledIf} でスキップ（骨抜きにしない・根治原則）。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.cms.migration.FlywayExistingDataBlogPostsUserFkMigrationTest#isDockerAvailable")
@DisplayName("Flyway 既存データ blog_posts user CASCADE 撤廃（V96.001）番人テスト")
class FlywayExistingDataBlogPostsUserFkMigrationTest {

    /** V96.001 の直前バージョン（origin/main 全体最大）。ここまで適用してから既存行をシードする。 */
    private static final String PRE_V96_001_TARGET = "95.001";

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_blog_posts_user_fk")
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
    @DisplayName("既存個人ブログ行を持つDBにV96.001適用_FK撤廃_親user物理削除でも子行が孤児user_idで生存")
    void 既存データを持つDBでV96_001がFK撤廃onlyで安全に適用される() throws Exception {
        // given: V96.001 の直前（V95.001）まで適用 ＝ fk_bp_user はまだ生きている
        Flyway pre = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .target(MigrationVersion.fromVersion(PRE_V96_001_TARGET))
                .load();
        MigrateResult preResult = pre.migrate();
        assertThat(preResult.success).as("V95.001 までの適用が成功すること").isTrue();

        final long userId;
        final long postId;
        try (Connection c = conn()) {
            // sanity: この時点では fk_bp_user が実在する（撤廃前スキーマの証明）
            assertThat(foreignKeyExists(c, "blog_posts", "fk_bp_user"))
                    .as("V95.001 時点では fk_bp_user が実在すること").isTrue();

            userId = insertUser(c, "blog-author@example.com");
            postId = insertUserScopedBlogPost(c, userId, "個人ブログ統計温存テスト", "stat-keep-slug-1");
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
            // then-1: fk_bp_user が撤廃された
            assertThat(foreignKeyExists(c, "blog_posts", "fk_bp_user"))
                    .as("V96.001 で fk_bp_user が撤廃されること").isFalse();

            // then-2: 既存行は無傷で生存
            assertThat(blogPostExists(c, postId))
                    .as("FK 撤廃後も既存 blog_posts 行が生存していること").isTrue();

            // then-3（中核）: 親 users 行を物理 DELETE しても子 blog_posts 行は CASCADE 削除されず生存し、
            //                user_id が孤児値として保持される（＝統計温存）
            deleteUserPhysically(c, userId);
            assertThat(userExists(c, userId))
                    .as("親 users 行が物理削除されたこと").isFalse();
            assertThat(blogPostExists(c, postId))
                    .as("親 users 物理削除でも子 blog_posts 行が CASCADE 削除されず生存すること（統計温存）").isTrue();
            assertThat(userIdOfBlogPost(c, postId))
                    .as("子 blog_posts.user_id が孤児値として保持されること").isEqualTo(userId);

            // then-4: 孤児 user_id 行でも CHECK 制約 chk_bp_scope を満たし続ける
            //         （user スコープ = user_id 非NULL・team_id/organization_id NULL）
            assertThat(scopeColumnIsNull(c, postId, "team_id"))
                    .as("user スコープ行の team_id は NULL であること（chk_bp_scope 充足）").isTrue();
            assertThat(scopeColumnIsNull(c, postId, "organization_id"))
                    .as("user スコープ行の organization_id は NULL であること（chk_bp_scope 充足）").isTrue();
        }
    }

    // ── helpers ────────────────────────────────────────────────

    private long insertUser(Connection c, String email) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO users
                    (email, last_name, first_name, display_name, status, created_at, updated_at)
                VALUES (?, '投稿', '太郎', '投稿太郎', 'ACTIVE', NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, email);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** 個人スコープ（user_id のみ非NULL）の blog_posts 行を挿入する（chk_bp_scope 充足）。 */
    private long insertUserScopedBlogPost(Connection c, long userId, String title, String slug)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO blog_posts
                    (user_id, title, slug, body)
                VALUES (?, ?, ?, '本文')
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, userId);
            ps.setString(2, title);
            ps.setString(3, slug);
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

    private static boolean blogPostExists(Connection c, long postId) throws SQLException {
        return countById(c, "blog_posts", postId) > 0;
    }

    private static long userIdOfBlogPost(Connection c, long postId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT user_id FROM blog_posts WHERE id = ?")) {
            ps.setLong(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static boolean scopeColumnIsNull(Connection c, long postId, String column) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT " + column + " FROM blog_posts WHERE id = ?")) {
            ps.setLong(1, postId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                rs.getLong(1);
                return rs.wasNull();
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
