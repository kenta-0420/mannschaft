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
 * <b>クロスドメインFK撤廃 第三陣C（cms / blog ドメイン）の番人テスト。</b>
 *
 * <p>V104.001 で cms（blog）ドメインの「users を親とする ON DELETE SET NULL の監査/操作者カラム」FK 2件を撤廃only する:</p>
 * <ul>
 *   <li>{@code blog_post_series.fk_bps_created_by}（created_by → users SET NULL）</li>
 *   <li>{@code blog_posts.fk_bp_author}（author_id → users SET NULL）</li>
 * </ul>
 *
 * <p>本テストが守る不変条件:</p>
 * <ol>
 *   <li>V104.001 の直前（V103.001）まで適用 → 監査列＝対象 user を持つ子行をシード。</li>
 *   <li>V104.001 直前時点で対象2FKが実在することを sanity 確認。</li>
 *   <li>残り（V104.001 含む）を適用しても既存データを壊さず成功する。</li>
 *   <li>V104.001 で対象2FKが撤廃される。</li>
 *   <li><b>親 users 行（監査列でのみ参照される user）を物理 DELETE しても監査列が NULL 化されず孤児 user_id 値を保持する</b>
 *       （＝SET NULL 撤廃only の肝・「誰が連載シリーズを作成したか / 誰が記事を書いたか（著者）」の操作者証跡温存）。</li>
 *   <li>blog_posts は孤児 author_id 行でも CHECK 制約 {@code chk_bp_scope}（team/org/user の XOR）を満たし続ける。</li>
 * </ol>
 *
 * <p>方針: Spring を起動せず Testcontainers の実 MySQL 8.0 に {@link Flyway} を Java API で直接実行する。
 * Docker 未起動環境では {@code @EnabledIf} でスキップ（骨抜きにしない・根治原則）。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.cms.migration.FlywayExistingDataCmsSetNullFkMigrationTest#isDockerAvailable")
@DisplayName("Flyway 既存データ cms（blog）監査列 SET NULL FK撤廃（V104.001）番人テスト")
class FlywayExistingDataCmsSetNullFkMigrationTest {

    /** V104.001 の直前バージョン（origin/main 全体最大）。ここまで適用してから既存行をシードする。 */
    private static final String PRE_V104_001_TARGET = "103.001";

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_cms_setnull_fk")
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
    @DisplayName("既存子行を持つDBにV104.001適用_cms監査列SET_NULL_FK2件撤廃_親user物理削除でも監査列が孤児user_idを保持")
    void 既存データを持つDBでV104_001がcms監査列SET_NULL_FK撤廃onlyで安全に適用される() throws Exception {
        // given: V104.001 の直前（V103.001）まで適用 ＝ 対象2FKはまだ生きている
        Flyway pre = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .target(MigrationVersion.fromVersion(PRE_V104_001_TARGET))
                .load();
        MigrateResult preResult = pre.migrate();
        assertThat(preResult.success).as("V103.001 までの適用が成功すること").isTrue();

        final long seriesCreatedBy; // blog_post_series.created_by
        final long postAuthor;      // blog_posts.author_id
        final long teamId;          // blog_post_series は team/org XOR スコープ（user 不可）→ team 親を用意
        final long seriesId;
        final long postId;

        try (Connection c = conn()) {
            // sanity: V103.001 時点で対象2FKが実在すること
            assertThat(foreignKeyExists(c, "blog_post_series", "fk_bps_created_by"))
                    .as("V103.001 時点で fk_bps_created_by が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "blog_posts", "fk_bp_author"))
                    .as("V103.001 時点で fk_bp_author が実在すること").isTrue();

            teamId = insertTeam(c, "CMS監査FK撤廃テストチーム", "test-team-cms");
            seriesCreatedBy = insertUser(c, "bps-createdby@example.com");
            postAuthor = insertUser(c, "bp-author@example.com");

            // blog_post_series は chk_bps_scope（team XOR org・user 不可）→ team スコープでシード
            seriesId = insertTeamScopedSeries(c, teamId, seriesCreatedBy);
            // blog_posts は chk_bp_scope（team/org/user XOR）→ user スコープでシード（author_id は監査列で独立）
            postId = insertUserScopedBlogPost(c, postAuthor, postAuthor, "著者証跡温存テスト", "author-keep-slug-1");
        }

        // when: 残りのマイグレーション（V104.001 含む）を適用する
        Flyway rest = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .load();
        MigrateResult restResult = rest.migrate();
        assertThat(restResult.success).as("V104.001 を含む残りのマイグレーションが成功すること").isTrue();

        try (Connection c = conn()) {
            // then-1: 対象2FKが撤廃された
            assertThat(foreignKeyExists(c, "blog_post_series", "fk_bps_created_by"))
                    .as("V104.001 で fk_bps_created_by が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "blog_posts", "fk_bp_author"))
                    .as("V104.001 で fk_bp_author が撤廃されること").isFalse();

            // 対象外: author_id をカバーする名前付き index は FK 撤廃後も残存していること
            assertThat(indexExists(c, "blog_posts", "idx_bp_author"))
                    .as("idx_bp_author が FK 撤廃後も残存すること（author_id 引き継続可）").isTrue();

            // then-2: 既存子行が生存していること
            assertThat(rowExists(c, "blog_post_series", seriesId))
                    .as("FK 撤廃後も blog_post_series 子行が生存していること").isTrue();
            assertThat(rowExists(c, "blog_posts", postId))
                    .as("FK 撤廃後も blog_posts 子行が生存していること").isTrue();

            // then-3（中核）: 監査列でのみ参照される親 users を物理削除しても監査列が NULL 化されず孤児値を保持
            deleteUserPhysically(c, seriesCreatedBy);
            deleteUserPhysically(c, postAuthor);

            assertThat(rowExists(c, "users", seriesCreatedBy)).as("親 users（series created_by）が物理削除されたこと").isFalse();
            assertThat(rowExists(c, "users", postAuthor)).as("親 users（post author）が物理削除されたこと").isFalse();

            assertThat(longColumn(c, "blog_post_series", "created_by", seriesId))
                    .as("blog_post_series.created_by が SET NULL されず孤児 user_id を保持すること")
                    .isEqualTo(seriesCreatedBy);
            assertThat(longColumn(c, "blog_posts", "author_id", postId))
                    .as("blog_posts.author_id が SET NULL されず孤児 user_id を保持すること")
                    .isEqualTo(postAuthor);

            // then-4: 孤児 author_id 行でも CHECK 制約 chk_bp_scope を満たし続ける
            //         （user スコープ = user_id 非NULL・team_id/organization_id NULL）
            assertThat(isNullColumn(c, "blog_posts", "team_id", postId))
                    .as("user スコープ行の team_id は NULL であること（chk_bp_scope 充足）").isTrue();
            assertThat(isNullColumn(c, "blog_posts", "organization_id", postId))
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

    private long insertTeam(Connection c, String name, String slug) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO teams (name, slug, visibility, created_at, updated_at)
                VALUES (?, ?, 'PUBLIC', NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setString(2, slug);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** team スコープ（team_id 非NULL・organization_id NULL）の blog_post_series 行を挿入する（chk_bps_scope 充足）。 */
    private long insertTeamScopedSeries(Connection c, long teamId, long createdBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO blog_post_series
                    (team_id, organization_id, name, description, created_by, created_at, updated_at)
                VALUES (?, NULL, '監査FK撤廃テスト連載', '創設者証跡温存テスト', ?, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, teamId);
            ps.setLong(2, createdBy);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /**
     * 個人スコープ（user_id のみ非NULL）の blog_posts 行を挿入する（chk_bp_scope 充足）。
     * author_id は監査列で独立に当該 user を指す。
     */
    private long insertUserScopedBlogPost(Connection c, long userId, long authorId, String title, String slug)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO blog_posts
                    (user_id, author_id, title, slug, body)
                VALUES (?, ?, ?, ?, '本文')
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, userId);
            ps.setLong(2, authorId);
            ps.setString(3, title);
            ps.setString(4, slug);
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

    private static boolean isNullColumn(Connection c, String table, String column, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT " + column + " FROM " + table + " WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                rs.getLong(1);
                return rs.wasNull();
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
