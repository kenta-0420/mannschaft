package com.mannschaft.app.shared.migration;

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
 * <b>クロスドメインFK撤廃 第三陣D（shared / ファイル共有ドメイン）の番人テスト。</b>
 *
 * <p>V105.001 で shared ドメインの「users を親とする ON DELETE SET NULL の監査/操作者カラム」FK 3件を撤廃only する:</p>
 * <ul>
 *   <li>{@code shared_file_versions.fk_file_versions_uploaded}（uploaded_by → users SET NULL）</li>
 *   <li>{@code shared_folders.fk_shared_folders_user}（user_id → users SET NULL）</li>
 *   <li>{@code shared_folders.fk_shared_folders_created}（created_by → users SET NULL）</li>
 * </ul>
 *
 * <p>本テストが守る不変条件:</p>
 * <ol>
 *   <li>V105.001 の直前（V104.001）まで適用 → 監査列＝対象 user を持つ子行をシード。</li>
 *   <li>V105.001 直前時点で対象3FKが実在することを sanity 確認。</li>
 *   <li>残り（V105.001 含む）を適用しても既存データを壊さず成功する。</li>
 *   <li>V105.001 で対象3FKが撤廃される。</li>
 *   <li><b>親 users 行（監査列でのみ参照される user）を物理 DELETE しても監査列が NULL 化されず孤児 user_id 値を保持する</b>
 *       （＝SET NULL 撤廃only の肝・「誰がフォルダを所有/作成したか / 誰がファイルをアップロードしたか」の操作者証跡温存）。</li>
 * </ol>
 *
 * <p>方針: Spring を起動せず Testcontainers の実 MySQL 8.0 に {@link Flyway} を Java API で直接実行する。
 * Docker 未起動環境では {@code @EnabledIf} でスキップ（骨抜きにしない・根治原則）。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.shared.migration.FlywayExistingDataSharedSetNullFkMigrationTest#isDockerAvailable")
@DisplayName("Flyway 既存データ shared（ファイル共有）監査列 SET NULL FK撤廃（V105.001）番人テスト")
class FlywayExistingDataSharedSetNullFkMigrationTest {

    /** V105.001 の直前バージョン（origin/main 全体最大＝第三陣C）。ここまで適用してから既存行をシードする。 */
    private static final String PRE_V105_001_TARGET = "104.001";

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_shared_setnull_fk")
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
    @DisplayName("既存子行を持つDBにV105.001適用_shared監査列SET_NULL_FK3件撤廃_親user物理削除でも監査列が孤児user_idを保持")
    void 既存データを持つDBでV105_001がshared監査列SET_NULL_FK撤廃onlyで安全に適用される() throws Exception {
        // given: V105.001 の直前（V104.001）まで適用 ＝ 対象3FKはまだ生きている
        Flyway pre = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .target(MigrationVersion.fromVersion(PRE_V105_001_TARGET))
                .load();
        MigrateResult preResult = pre.migrate();
        assertThat(preResult.success).as("V104.001 までの適用が成功すること").isTrue();

        final long folderOwner;   // shared_folders.user_id（個人スコープ所有者）
        final long folderCreator; // shared_folders.created_by（監査列）
        final long versionUploader; // shared_file_versions.uploaded_by（監査列）
        final long folderId;
        final long fileId;
        final long versionId;

        try (Connection c = conn()) {
            // sanity: V104.001 時点で対象3FKが実在すること
            assertThat(foreignKeyExists(c, "shared_file_versions", "fk_file_versions_uploaded"))
                    .as("V104.001 時点で fk_file_versions_uploaded が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "shared_folders", "fk_shared_folders_user"))
                    .as("V104.001 時点で fk_shared_folders_user が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "shared_folders", "fk_shared_folders_created"))
                    .as("V104.001 時点で fk_shared_folders_created が実在すること").isTrue();

            folderOwner = insertUser(c, "sf-owner-3d@example.com");
            folderCreator = insertUser(c, "sf-creator-3d@example.com");
            versionUploader = insertUser(c, "fv-uploader-3d@example.com");

            // shared_folders は個人（PERSONAL）スコープでシード（user_id=所有者・created_by=作成者・team/org NULL）
            folderId = insertPersonalFolder(c, folderOwner, folderCreator);
            // shared_files は当該フォルダ配下に置く（shared_file_versions の親）。
            // shared_files.fk_shared_files_created は V62.011 で既に撤廃済（created_by は孤児可）。
            fileId = insertFileInFolder(c, folderId, versionUploader);
            // 監査列 uploaded_by が撤廃対象（fk_file_versions_uploaded）。
            versionId = insertFileVersion(c, fileId, versionUploader);
        }

        // when: 残りのマイグレーション（V105.001 含む）を適用する
        Flyway rest = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .load();
        MigrateResult restResult = rest.migrate();
        assertThat(restResult.success).as("V105.001 を含む残りのマイグレーションが成功すること").isTrue();

        try (Connection c = conn()) {
            // then-1: 対象3FKが撤廃された
            assertThat(foreignKeyExists(c, "shared_file_versions", "fk_file_versions_uploaded"))
                    .as("V105.001 で fk_file_versions_uploaded が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "shared_folders", "fk_shared_folders_user"))
                    .as("V105.001 で fk_shared_folders_user が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "shared_folders", "fk_shared_folders_created"))
                    .as("V105.001 で fk_shared_folders_created が撤廃されること").isFalse();

            // 対象外: 同一ドメイン/他参照 FK は撤廃後も残存していること
            assertThat(foreignKeyExists(c, "shared_folders", "fk_shared_folders_team"))
                    .as("fk_shared_folders_team（CASCADE）は撤廃対象外で残存すること").isTrue();
            assertThat(foreignKeyExists(c, "shared_folders", "fk_shared_folders_parent"))
                    .as("fk_shared_folders_parent（自己参照 SET NULL）は撤廃対象外で残存すること").isTrue();
            assertThat(foreignKeyExists(c, "shared_file_versions", "fk_file_versions_file"))
                    .as("fk_file_versions_file（同一ドメイン CASCADE）は撤廃対象外で残存すること").isTrue();

            // then-2: 既存子行が生存していること
            assertThat(rowExists(c, "shared_folders", folderId))
                    .as("FK 撤廃後も shared_folders 子行が生存していること").isTrue();
            assertThat(rowExists(c, "shared_file_versions", versionId))
                    .as("FK 撤廃後も shared_file_versions 子行が生存していること").isTrue();

            // then-3（中核）: 監査列でのみ参照される親 users を物理削除しても監査列が NULL 化されず孤児値を保持
            deleteUserPhysically(c, folderOwner);
            deleteUserPhysically(c, folderCreator);
            deleteUserPhysically(c, versionUploader);

            assertThat(rowExists(c, "users", folderOwner)).as("親 users（folder owner）が物理削除されたこと").isFalse();
            assertThat(rowExists(c, "users", folderCreator)).as("親 users（folder creator）が物理削除されたこと").isFalse();
            assertThat(rowExists(c, "users", versionUploader)).as("親 users（version uploader）が物理削除されたこと").isFalse();

            assertThat(longColumn(c, "shared_folders", "user_id", folderId))
                    .as("shared_folders.user_id が SET NULL されず孤児 user_id を保持すること")
                    .isEqualTo(folderOwner);
            assertThat(longColumn(c, "shared_folders", "created_by", folderId))
                    .as("shared_folders.created_by が SET NULL されず孤児 user_id を保持すること")
                    .isEqualTo(folderCreator);
            assertThat(longColumn(c, "shared_file_versions", "uploaded_by", versionId))
                    .as("shared_file_versions.uploaded_by が SET NULL されず孤児 user_id を保持すること")
                    .isEqualTo(versionUploader);
        }
    }

    // ── helpers ────────────────────────────────────────────────

    private long insertUser(Connection c, String email) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO users
                    (email, last_name, first_name, display_name, status, created_at, updated_at)
                VALUES (?, '共有', '太郎', '共有太郎', 'ACTIVE', NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, email);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** 個人（PERSONAL）スコープ（user_id 非NULL・team/org NULL）の shared_folders 行を挿入する。 */
    private long insertPersonalFolder(Connection c, long ownerUserId, long createdBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO shared_folders
                    (scope_type, team_id, organization_id, user_id, parent_id, name, created_by,
                     version, created_at, updated_at)
                VALUES ('PERSONAL', NULL, NULL, ?, NULL, '監査FK撤廃テスト個人フォルダ', ?, 0, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, ownerUserId);
            ps.setLong(2, createdBy);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /**
     * フォルダ配下の shared_files 行を挿入する（shared_file_versions の親）。NOT NULL 列を全て充足する。
     * created_by の FK（fk_shared_files_created）は V62.011 で撤廃済のため孤児値でも問題ない。
     */
    private long insertFileInFolder(Connection c, long folderId, long createdBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO shared_files
                    (folder_id, name, file_key, file_size, content_type, created_by,
                     current_version, version, created_at, updated_at)
                VALUES (?, '監査FK撤廃テストファイル.txt', 'shared/test/3d/file.txt', 1024, 'text/plain', ?,
                        1, 0, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, folderId);
            ps.setLong(2, createdBy);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long insertFileVersion(Connection c, long fileId, long uploadedBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO shared_file_versions
                    (file_id, version_number, file_key, file_size, content_type, uploaded_by, created_at)
                VALUES (?, 1, 'shared/test/3d/file-v1.txt', 1024, 'text/plain', ?, NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, fileId);
            ps.setLong(2, uploadedBy);
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
