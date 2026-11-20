package com.mannschaft.app.effortrecord.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F06.4.1 PR-1 の Flyway 実 MySQL 試験（試練。CMP-261115-2145）。AC-57。
 *
 * <p>effort 表の migration 直前まで適用して既存データ（users / teams / memberships / activity_templates）を投入し、
 * 全量適用後に (1) 既存データ無傷、(2) 4表の照合順序 utf8mb4_0900_ai_ci、(3) プリセット seed の UUID version nibble=7、
 * (4) CHECK 制約の拒否、(5) FK は effort 定義内部（revision→definition）だけ、(6) 設計の索引が EXPLAIN で使われる、
 * (7) 再実行で保留 migration なし、を実測する。timeline_posts への FK は PR-3（effort_team_shares / timeline_effort_sources）の範囲。</p>
 *
 * <p>PR-2（AC-67・2026-11-18 マスター裁定）: チーム側は組織に依存しない。TEAM 定義を組織なしで INSERT でき、
 * effort 表は組織列を持たず、TEAM 定義の索引は team_id 起点であることを全量適用後に実測する。</p>
 *
 * <p>migration の版番号はマージ時に付け替わりうるため、対象は説明名 {@code create_effort_records_tables} で探し、
 * その直前の版を事前適用の target にする。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.effortrecord.migration.FlywayEffortRecordsTablesMigrationTest#isDockerAvailable")
@DisplayName("F06.4.1 effort 表 migration の実 MySQL 試験（AC-57）")
class FlywayEffortRecordsTablesMigrationTest {

    private static final List<String> TABLES = List.of(
            "effort_definitions", "effort_definition_revisions", "effort_records", "effort_operation_receipts");

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_effort_migration")
            .withUsername("test")
            .withPassword("test")
            .withTmpFs(java.util.Map.of("/var/lib/mysql", "rw"))
            .withCommand("--log_bin_trust_function_creators=1");

    public static boolean isDockerAvailable() {
        try {
            return "true".equalsIgnoreCase(System.getenv("CI")) || DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception e) {
            return "true".equalsIgnoreCase(System.getenv("CI"));
        }
    }

    private List<String> checksumsBefore;

    @BeforeAll
    void 直前版まで適用して既存データを入れてから全量適用する() throws Exception {
        MYSQL.start();
        MigrationVersion previous = versionBeforeEffortMigration();
        flyway().target(previous).load().migrate();
        try (Connection c = connection(); Statement s = c.createStatement()) {
            s.executeUpdate("INSERT INTO users (email, last_name, first_name, display_name, status, is_searchable, "
                    + "handle_searchable, contact_approval_required, online_visibility, dm_receive_from, encryption_key_version, "
                    + "locale, timezone, reporting_restricted, follow_list_visibility, care_notification_enabled, offline_only, "
                    + "created_at, updated_at) VALUES ('effort-migration@example.com', '移行', '既存', '既存利用者', 'ACTIVE', 1, 1, 1, "
                    + "'NOBODY', 'ANYONE', 1, 'ja', 'Asia/Tokyo', 0, 'PUBLIC', 1, 0, NOW(), NOW())");
            s.executeUpdate("INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, created_at, updated_at) "
                    + "VALUES ('既存チーム', 'PUBLIC', 1, 0, 0, 'effort-migration-team', NOW(), NOW())");
            s.executeUpdate("INSERT INTO memberships (user_id, scope_type, scope_id, role_kind, joined_at, created_at, updated_at) "
                    + "SELECT u.id, 'TEAM', t.id, 'MEMBER', NOW(), NOW(), NOW() FROM users u, teams t "
                    + "WHERE u.email = 'effort-migration@example.com' AND t.slug = 'effort-migration-team'");
        }
        checksumsBefore = checksums();
        flyway().load().migrate();
    }

    @AfterAll
    void stop() {
        MYSQL.stop();
    }

    @Test
    @DisplayName("AC-57: 既存データは無傷で、再実行しても保留 migration が無い")
    void AC57_既存データ無傷と再実行() throws Exception {
        assertThat(checksums()).isEqualTo(checksumsBefore);
        Flyway again = flyway().load();
        assertThat(again.migrate().migrationsExecuted).isZero();
        assertThat(Arrays.stream(again.info().pending())).isEmpty();
    }

    @Test
    @DisplayName("AC-57: 4表と文字列列の照合順序は utf8mb4_0900_ai_ci")
    void AC57_照合順序() throws Exception {
        try (Connection c = connection(); Statement s = c.createStatement()) {
            for (String table : TABLES) {
                try (ResultSet rs = s.executeQuery("SELECT TABLE_COLLATION FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '" + table + "'")) {
                    assertThat(rs.next()).as(table + " が存在する").isTrue();
                    assertThat(rs.getString(1)).as(table).isEqualTo("utf8mb4_0900_ai_ci");
                }
                try (ResultSet rs = s.executeQuery("SELECT COLUMN_NAME, COLLATION_NAME FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '" + table + "' AND COLLATION_NAME IS NOT NULL")) {
                    while (rs.next()) {
                        assertThat(rs.getString(2)).as(table + "." + rs.getString(1)).isEqualTo("utf8mb4_0900_ai_ci");
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("AC-57: プリセット seed の定義・revision の主キーは UUIDv7（version nibble=7, variant=2）")
    void AC57_seedのUUIDv7() throws Exception {
        List<UUID> ids = new ArrayList<>();
        try (Connection c = connection(); Statement s = c.createStatement()) {
            for (String table : List.of("effort_definitions", "effort_definition_revisions")) {
                try (ResultSet rs = s.executeQuery("SELECT HEX(id) FROM " + table)) {
                    while (rs.next()) {
                        ids.add(uuid(rs.getString(1)));
                    }
                }
            }
            try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM effort_definitions WHERE source_type = 'PRESET' "
                    + "AND source_key IN ('STUDY_V1','EXERCISE_V1','WEIGHT_V1') AND current_revision = 1")) {
                rs.next();
                assertThat(rs.getLong(1)).isEqualTo(3);
            }
        }
        assertThat(ids).hasSize(6).doesNotHaveDuplicates().allSatisfy(id -> {
            assertThat(id.version()).isEqualTo(7);
            assertThat(id.variant()).isEqualTo(2);
        });
    }

    @Test
    @DisplayName("AC-57: CHECK 制約が不正な source_type / scope / 版 / share 組 / route_kind を拒否する")
    void AC57_CHECK拒否() throws Exception {
        String def = "UNHEX(REPLACE(UUID(),'-',''))";
        String presetDef = "UNHEX(REPLACE('01938a00-0000-7000-8000-000000000001','-',''))";
        List<String> rejected = List.of(
                "INSERT INTO effort_definitions (id, source_type, source_key, current_revision, version, created_at, updated_at) "
                        + "VALUES (" + def + ", 'OTHER', 'x1', 1, 0, NOW(6), NOW(6))",
                // PRESET に team を持たせる / TEAM に team が無い（組織列は 2026-11-18 裁定で使わないため、team 列で検査する）
                "INSERT INTO effort_definitions (id, source_type, source_key, team_id, current_revision, version, created_at, updated_at) "
                        + "VALUES (" + def + ", 'PRESET', 'x2', 1, 1, 0, NOW(6), NOW(6))",
                "INSERT INTO effort_definitions (id, source_type, source_key, team_template_id, current_revision, version, created_at, updated_at) "
                        + "VALUES (" + def + ", 'TEAM', 'x3', 1, 1, 0, NOW(6), NOW(6))",
                "INSERT INTO effort_definitions (id, source_type, source_key, current_revision, version, created_at, updated_at) "
                        + "VALUES (" + def + ", 'PRESET', 'x4', 0, 0, NOW(6), NOW(6))",
                "INSERT INTO effort_definition_revisions (id, definition_id, revision, definition_json, definition_hash, created_at, updated_at) "
                        + "VALUES (" + def + ", " + presetDef + ", 0, JSON_OBJECT(), REPEAT('a',64), NOW(6), NOW(6))",
                record("content_revision", "0", "share_state", "NULL"),
                record("content_revision", "1", "share_state", "'BOGUS'"),
                // share 組の片欠け（加入期間 ID なし）
                "INSERT INTO effort_records (id, owner_user_id, definition_id, definition_revision, definition_snapshot, recorded_on, "
                        + "record_timezone, values_json, share_id, share_team_id, share_state, created_at, updated_at) "
                        + "VALUES (" + def + ", 1, " + presetDef + ", 1, JSON_OBJECT(), '2026-11-01', 'Asia/Tokyo', JSON_OBJECT(), "
                        + def + ", 1, 'PENDING', NOW(6), NOW(6))",
                "INSERT INTO effort_operation_receipts (id, route_kind, route_id, actor_user_id, operation_key, operation_path, "
                        + "request_hash, created_at, updated_at, expires_at) VALUES (" + def + ", 'OTHER', 1, 1, '"
                        + UUID.randomUUID() + "', 'POST /api/v1/effort-records/me', REPEAT('a',64), NOW(6), NOW(6), NOW(6))");
        try (Connection c = connection(); Statement s = c.createStatement()) {
            for (String sql : rejected) {
                assertThatThrownBy(() -> s.executeUpdate(sql)).as(sql).isInstanceOf(SQLException.class);
            }
            // 対照: 妥当な行は入る（CHECK が全拒否になっていないこと）。
            assertThat(s.executeUpdate(record("content_revision", "1", "share_state", "NULL"))).isEqualTo(1);
            s.executeUpdate("DELETE FROM effort_records WHERE owner_user_id = 1");
        }
    }

    @Test
    @DisplayName("AC-67: TEAM 定義は組織なしで INSERT でき、share 組も組織なしで成立する（2026-11-18 マスター裁定）")
    void AC67_TEAM定義は組織なしでINSERTできる() throws Exception {
        String def = "UNHEX(REPLACE('01938a00-0000-7000-8000-0000000000a1','-',''))";
        String presetDef = "UNHEX(REPLACE('01938a00-0000-7000-8000-000000000001','-',''))";
        try (Connection c = connection(); Statement s = c.createStatement()) {
            try {
                assertThat(s.executeUpdate("INSERT INTO effort_definitions (id, source_type, source_key, team_id, team_template_id, "
                        + "current_revision, version, created_at, updated_at) VALUES (" + def + ", 'TEAM', 'team:7001:template:8001', "
                        + "7001, 8001, 1, 0, NOW(6), NOW(6))")).as("親組織を持たないチームの TEAM 定義").isEqualTo(1);
                assertThat(s.executeUpdate("INSERT INTO effort_records (id, owner_user_id, definition_id, definition_revision, "
                        + "definition_snapshot, recorded_on, record_timezone, values_json, share_id, share_team_id, share_state, "
                        + "grant_membership_id, created_at, updated_at) VALUES (UNHEX(REPLACE(UUID(),'-','')), 2, " + presetDef
                        + ", 1, JSON_OBJECT(), '2026-11-01', 'Asia/Tokyo', JSON_OBJECT(), UNHEX(REPLACE(UUID(),'-','')), 7001, "
                        + "'PENDING', 9001, NOW(6), NOW(6))")).as("組織なしの share 組").isEqualTo(1);
            } finally {
                s.executeUpdate("DELETE FROM effort_records WHERE owner_user_id = 2");
                s.executeUpdate("DELETE FROM effort_definitions WHERE id = " + def);
            }
        }
    }

    @Test
    @DisplayName("AC-67: チーム側の表は組織列を持たず、TEAM 定義の索引は team_id 起点（データ設計 §2）")
    void AC67_チーム側は組織列を持たずteam起点の索引() throws Exception {
        try (Connection c = connection(); Statement s = c.createStatement()) {
            try (ResultSet rs = s.executeQuery("SELECT TABLE_NAME, COLUMN_NAME FROM information_schema.COLUMNS "
                    + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME LIKE 'effort\\_%' AND COLUMN_NAME LIKE '%organization%'")) {
                List<String> found = new ArrayList<>();
                while (rs.next()) {
                    found.add(rs.getString(1) + "." + rs.getString(2));
                }
                assertThat(found).as("effort 表に組織列が無い（effort_definitions.organization_id・effort_records.share_organization_id を削除）")
                        .isEmpty();
            }
            List<String> columns = new ArrayList<>();
            try (ResultSet rs = s.executeQuery("SELECT COLUMN_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() "
                    + "AND TABLE_NAME = 'effort_definitions' AND INDEX_NAME = 'idx_effort_definition_team' ORDER BY SEQ_IN_INDEX")) {
                while (rs.next()) {
                    columns.add(rs.getString(1).toLowerCase());
                }
            }
            assertThat(columns).containsExactly("team_id", "deleted_at");
        }
    }

    @Test
    @DisplayName("AC-57: FK は effort_definition_revisions→effort_definitions の1本だけ（クロスドメイン FK なし）")
    void AC57_FKはeffort内部だけ() throws Exception {
        Set<String> fks = new HashSet<>();
        try (Connection c = connection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT TABLE_NAME, REFERENCED_TABLE_NAME FROM information_schema.REFERENTIAL_CONSTRAINTS "
                     + "WHERE CONSTRAINT_SCHEMA = DATABASE() AND (TABLE_NAME LIKE 'effort\\_%' OR REFERENCED_TABLE_NAME LIKE 'effort\\_%')")) {
            while (rs.next()) {
                fks.add(rs.getString(1) + "->" + rs.getString(2));
            }
        }
        assertThat(fks).containsExactly("effort_definition_revisions->effort_definitions");
    }

    @Test
    @DisplayName("AC-57: 個人一覧・集計・receipt 照会/期限削除が設計の索引を使う（EXPLAIN）")
    void AC57_EXPLAINで想定索引() throws Exception {
        try (Connection c = connection(); Statement s = c.createStatement()) {
            String study = "UNHEX(REPLACE('01938a00-0000-7000-8000-000000000001','-',''))";
            String weight = "UNHEX(REPLACE('01938a00-0000-7000-8000-000000000003','-',''))";
            // 30 人 × 40 件（2 定義）と receipt を投入し統計を更新する。
            for (int owner = 1001; owner <= 1030; owner++) {
                StringBuilder sql = new StringBuilder("INSERT INTO effort_records (id, owner_user_id, definition_id, definition_revision, "
                        + "definition_snapshot, recorded_on, record_timezone, values_json, created_at, updated_at) VALUES ");
                for (int i = 0; i < 40; i++) {
                    if (i > 0) {
                        sql.append(',');
                    }
                    sql.append("(UNHEX(REPLACE(UUID(),'-','')), ").append(owner).append(", ").append(i % 2 == 0 ? study : weight)
                            .append(", 1, JSON_OBJECT(), DATE_SUB('2026-11-01', INTERVAL ").append(i).append(" DAY), 'Asia/Tokyo', ")
                            .append("JSON_OBJECT(), NOW(6), NOW(6))");
                }
                s.executeUpdate(sql.toString());
                s.executeUpdate("INSERT INTO effort_operation_receipts (id, route_kind, route_id, actor_user_id, operation_key, "
                        + "operation_path, request_hash, response_status, response_json, created_at, updated_at, expires_at) VALUES "
                        + "(UNHEX(REPLACE(UUID(),'-','')), 'OWNER', " + owner + ", " + owner + ", '" + receiptKey(owner)
                        + "', 'POST /api/v1/effort-records/me', REPEAT('a',64), 201, JSON_OBJECT(), NOW(6), NOW(6), "
                        + "DATE_ADD(NOW(6), INTERVAL " + owner + " MINUTE))");
            }
            s.execute("ANALYZE TABLE effort_records, effort_operation_receipts");

            assertThat(key(s, "EXPLAIN SELECT id FROM effort_records WHERE owner_user_id = 1005 AND deleted_at IS NULL "
                    + "ORDER BY recorded_on DESC, created_at DESC, id DESC LIMIT 21")).isEqualTo("idx_effort_owner_date");
            assertThat(key(s, "EXPLAIN SELECT recorded_on, values_json FROM effort_records WHERE owner_user_id = 1005 "
                    + "AND definition_id = " + study + " AND deleted_at IS NULL AND recorded_on BETWEEN '2026-10-01' AND '2026-11-01'"))
                    .isEqualTo("idx_effort_owner_definition");
            assertThat(key(s, "EXPLAIN SELECT id FROM effort_operation_receipts WHERE route_kind = 'OWNER' AND route_id = 1005 "
                    + "AND actor_user_id = 1005 AND operation_path = 'POST /api/v1/effort-records/me' AND operation_key = '" + receiptKey(1005) + "'"))
                    .isEqualTo("uq_effort_receipt");
            assertThat(key(s, "EXPLAIN SELECT id FROM effort_operation_receipts WHERE expires_at < DATE_ADD(NOW(6), INTERVAL 1 MINUTE)"))
                    .isEqualTo("idx_effort_receipt_expiry");
            s.executeUpdate("DELETE FROM effort_records WHERE owner_user_id BETWEEN 1001 AND 1030");
            s.executeUpdate("DELETE FROM effort_operation_receipts WHERE actor_user_id BETWEEN 1001 AND 1030");
        }
    }

    // ─────────────────────────────────────────────────────────────

    /** 照会対象が実在しないと const 表として key=null になるため、owner ごとに決まった key を投入する。 */
    private static String receiptKey(int owner) {
        return UUID.nameUUIDFromBytes(("effort-receipt-" + owner).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    private static String record(String column1, String value1, String column2, String value2) {
        return "INSERT INTO effort_records (id, owner_user_id, definition_id, definition_revision, definition_snapshot, recorded_on, "
                + "record_timezone, values_json, " + column1 + ", " + column2 + ", created_at, updated_at) VALUES ("
                + "UNHEX(REPLACE(UUID(),'-','')), 1, UNHEX(REPLACE('01938a00-0000-7000-8000-000000000001','-','')), 1, "
                + "JSON_OBJECT(), '2026-11-01', 'Asia/Tokyo', JSON_OBJECT(), " + value1 + ", " + value2 + ", NOW(6), NOW(6))";
    }

    private static String key(Statement s, String explain) throws SQLException {
        try (ResultSet rs = s.executeQuery(explain)) {
            assertThat(rs.next()).isTrue();
            return rs.getString("key");
        }
    }

    private List<String> checksums() throws SQLException {
        List<String> result = new ArrayList<>();
        try (Connection c = connection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("CHECKSUM TABLE users, teams, memberships, activity_templates EXTENDED")) {
            while (rs.next()) {
                result.add(rs.getString(1) + "=" + rs.getString(2));
            }
        }
        return result;
    }

    private MigrationVersion versionBeforeEffortMigration() {
        MigrationInfo[] all = flyway().load().info().all();
        MigrationVersion previous = null;
        for (MigrationInfo info : all) {
            if (info.getScript().endsWith("__create_effort_records_tables.sql")) {
                assertThat(previous).as("effort migration より前の版がある").isNotNull();
                return previous;
            }
            previous = info.getVersion();
        }
        throw new AssertionError("create_effort_records_tables の migration が見つからない");
    }

    private static org.flywaydb.core.api.configuration.FluentConfiguration flyway() {
        return Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration");
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    private static UUID uuid(String hex) {
        String h = hex.toLowerCase();
        return UUID.fromString(h.substring(0, 8) + "-" + h.substring(8, 12) + "-" + h.substring(12, 16) + "-"
                + h.substring(16, 20) + "-" + h.substring(20));
    }
}
