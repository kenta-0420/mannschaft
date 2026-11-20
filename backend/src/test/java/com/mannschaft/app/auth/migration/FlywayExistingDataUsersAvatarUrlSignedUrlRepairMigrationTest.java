package com.mannschaft.app.auth.migration;

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
 * <b>CMP-261120-0200 AC4: users.avatar_url の署名付き URL 破損を修復する migration（V264）の既存データ移行の番人テスト。</b>
 *
 * <p>最終的に残してよいのは「本人の正規アイコンキー（{@code user/{id}/icon/<ファイル名>}）」だけ。
 * http(s) URL はパス部分がちょうど {@code /<bucket>/<本人の正規キー>}（その後は ? か終端）の場合のみキーを復元し、
 * クエリ内だけの一致・別パスの部分一致・他人キー・解析不能な値は NULL にする。
 * 生キー（本人）・NULL の行は不変。VARCHAR(500) ちょうどの行も処理できること。</p>
 *
 * <p>方針は既存の {@code FlywayExistingData*MigrationTest} に倣う（Spring 非起動・Testcontainers MySQL 8.0 へ
 * {@link Flyway} を直接実行。V264 直前まで適用 → 既存行をシード → 残りを適用して検証）。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.auth.migration.FlywayExistingDataUsersAvatarUrlSignedUrlRepairMigrationTest#isDockerAvailable")
@DisplayName("Flyway 既存データ users.avatar_url 署名付きURL破損の修復（V264）番人テスト")
class FlywayExistingDataUsersAvatarUrlSignedUrlRepairMigrationTest {

    /** V264 の直前バージョン（origin/main 全体最大）。 */
    private static final String PRE_V264_TARGET = "263.20261118093938";

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_avatar_url_repair")
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
    @DisplayName("AC4: 本人キーを含む URL はキーへ復元、他人キー・別パス・クエリ内のみ・解析不能は NULL、本人の生キーと NULL は不変")
    void 既存データのavatar_urlが修復される() throws Exception {
        MigrateResult pre = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").outOfOrder(false)
                .target(MigrationVersion.fromVersion(PRE_V264_TARGET)).load().migrate();
        assertThat(pre.success).as("V264 直前までの適用が成功すること").isTrue();

        long signedId; long otherKeyUrlId; long rawOwnId; long nullId; long longId;
        long partialPathId; long queryOnlyId; long foreignRawId; long garbageId; long nestedId;
        String signedKey; String rawKey; String longKey;
        try (Connection c = conn()) {
            long otherUserId = insertUser(c, "av-other@example.com", null);
            String uuid = UUID.randomUUID().toString();

            signedId = insertUser(c, "av-signed@example.com", null);
            signedKey = "user/" + signedId + "/icon/" + uuid + ".png";
            setAvatar(c, signedId, "https://acct.r2.cloudflarestorage.com/mannschaft-media/" + signedKey
                    + "?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Expires=3600&X-Amz-Signature=abcdef");

            otherKeyUrlId = insertUser(c, "av-otherurl@example.com", null);
            setAvatar(c, otherKeyUrlId, "https://acct.r2.cloudflarestorage.com/mannschaft-media/user/" + otherUserId
                    + "/icon/" + uuid + ".png?X-Amz-Signature=abc");

            rawOwnId = insertUser(c, "av-raw@example.com", null);
            rawKey = "user/" + rawOwnId + "/icon/" + uuid + ".jpg";
            setAvatar(c, rawOwnId, rawKey);

            nullId = insertUser(c, "av-null@example.com", null);

            // 500 文字ちょうどの署名付き URL（本人キー）
            longId = insertUser(c, "av-long@example.com", null);
            longKey = "user/" + longId + "/icon/" + uuid + ".webp";
            String head = "https://acct.r2.cloudflarestorage.com/mannschaft-media/" + longKey + "?X-Amz-Signature=";
            setAvatar(c, longId, head + "a".repeat(500 - head.length()));

            partialPathId = insertUser(c, "av-partial@example.com", null);
            setAvatar(c, partialPathId, "https://acct.r2.cloudflarestorage.com/mannschaft-media/otheruser/" + partialPathId
                    + "/icon/" + uuid + ".png");

            queryOnlyId = insertUser(c, "av-query@example.com", null);
            setAvatar(c, queryOnlyId, "https://evil.example.com/pic.png?next=user/" + queryOnlyId + "/icon/" + uuid + ".png");

            foreignRawId = insertUser(c, "av-foreign@example.com", null);
            setAvatar(c, foreignRawId, "user/" + otherUserId + "/icon/" + uuid + ".png");

            garbageId = insertUser(c, "av-garbage@example.com", null);
            setAvatar(c, garbageId, "legacy/avatar-dummy.png");

            // 入れ子（署名付き URL を再署名した破損）は、パスがちょうど /<bucket>/<本人の正規キー> ではないので NULL
            nestedId = insertUser(c, "av-nested@example.com", null);
            setAvatar(c, nestedId, "https://acct.r2.cloudflarestorage.com/mannschaft-media/https://acct.r2.cloudflarestorage.com/mannschaft-media/user/"
                    + nestedId + "/icon/" + uuid + ".png?X-Amz-Signature=a?X-Amz-Signature=b");
        }

        MigrateResult rest = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").outOfOrder(false).load().migrate();
        assertThat(rest.success).as("V264 を含む残りの migration が成功すること").isTrue();

        try (Connection c = conn()) {
            assertThat(avatar(c, signedId)).as("本人キー入りの署名付き URL はキーへ復元").isEqualTo(signedKey);
            assertThat(avatar(c, longId)).as("500 文字ちょうどの行もキーへ復元").isEqualTo(longKey);
            assertThat(avatar(c, rawOwnId)).as("本人の生キーは不変").isEqualTo(rawKey);
            assertThat(avatar(c, nullId)).as("NULL は不変").isNull();
            assertThat(avatar(c, otherKeyUrlId)).as("他人キーの URL は NULL").isNull();
            assertThat(avatar(c, partialPathId)).as("別パスの部分一致は NULL").isNull();
            assertThat(avatar(c, queryOnlyId)).as("クエリ内だけの一致は NULL").isNull();
            assertThat(avatar(c, foreignRawId)).as("移行前に保存された他人の生キーは NULL（署名が発行されない）").isNull();
            assertThat(avatar(c, garbageId)).as("解析不能な値は NULL").isNull();
            assertThat(avatar(c, nestedId)).as("入れ子の破損 URL は NULL").isNull();
        }
    }

    // ── helpers ────────────────────────────────────────────────

    private long insertUser(Connection c, String email, String avatar) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO users
                    (email, last_name, first_name, display_name, avatar_url, status, created_at, updated_at)
                VALUES (?, '画像', '太郎', '画像太郎', ?, 'ACTIVE', NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, email);
            ps.setString(2, avatar);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private void setAvatar(Connection c, long id, String value) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE users SET avatar_url = ? WHERE id = ?")) {
            ps.setString(1, value);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    private static String avatar(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT avatar_url FROM users WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }
}
