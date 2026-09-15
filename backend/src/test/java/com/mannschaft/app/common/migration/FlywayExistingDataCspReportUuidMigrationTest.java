package com.mannschaft.app.common.migration;

import org.flywaydb.core.Flyway;
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
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 既存行を持つ旧スキーマから CSP 報告の UUIDv7 主キー移行を実測する。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.common.migration.FlywayExistingDataCspReportUuidMigrationTest#isDockerAvailable")
@DisplayName("Flyway 既存CSP報告データのUUIDv7移行")
class FlywayExistingDataCspReportUuidMigrationTest {

    private static final String PRE_V212_TARGET = "211.20260914010000";

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_csp_uuid")
            .withUsername("test")
            .withPassword("test")
            .withTmpFs(java.util.Map.of("/var/lib/mysql", "rw"))
            .withCommand("--log_bin_trust_function_creators=1");

    public static boolean isDockerAvailable() {
        try {
            return "true".equalsIgnoreCase(System.getenv("CI"))
                    || DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception e) {
            return "true".equalsIgnoreCase(System.getenv("CI"));
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

    @Test
    @DisplayName("既存行と索引を保持し、交換した主キーが全てUUIDv7になる")
    void 既存行と索引を保持してUUIDv7へ移行できる() throws Exception {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion(PRE_V212_TARGET))
                .load()
                .migrate();

        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO csp_reports
                        (id, document_uri, blocked_uri, violated_directive, effective_directive,
                         original_policy, disposition, script_sample, status_code, report_hash,
                         occurrence_count, ip_address, user_agent, first_seen_at, last_seen_at)
                    VALUES
                        (1, 'https://example.test/日本語', 'https://cdn.test/a.js', 'script-src',
                         'script-src-elem', 'default-src ''self''', 'enforce', 'sample', 200,
                         REPEAT('a', 64), 3, '2001:db8::1', 'migration-test',
                         '2026-09-01 01:02:03.123456', '2026-09-02 02:03:04.654321'),
                        (9223372036854775806, NULL, '', NULL, NULL, NULL, NULL, NULL, NULL,
                         REPEAT('b', 64), 1, NULL, NULL,
                         '2026-09-03 03:04:05.000001', '2026-09-03 03:04:05.000002')
                    """);
        }

        List<List<Object>> before = businessRows();

        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        assertThat(businessRows()).isEqualTo(before);

        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            try (ResultSet rs = statement.executeQuery("""
                    SELECT COLUMN_TYPE, IS_NULLABLE, COLUMN_KEY, EXTRA
                      FROM information_schema.COLUMNS
                     WHERE TABLE_SCHEMA = DATABASE()
                       AND TABLE_NAME = 'csp_reports' AND COLUMN_NAME = 'id'
                    """)) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("COLUMN_TYPE")).isEqualToIgnoringCase("binary(16)");
                assertThat(rs.getString("IS_NULLABLE")).isEqualTo("NO");
                assertThat(rs.getString("COLUMN_KEY")).isEqualTo("PRI");
                assertThat(rs.getString("EXTRA")).doesNotContainIgnoringCase("auto_increment");
            }

            List<UUID> ids = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery("SELECT HEX(id) FROM csp_reports")) {
                while (rs.next()) {
                    ids.add(uuidFromHex(rs.getString(1)));
                }
            }
            assertThat(ids).hasSize(2).doesNotHaveDuplicates();
            assertThat(ids).allSatisfy(id -> {
                assertThat(id.version()).isEqualTo(7);
                assertThat(id.variant()).isEqualTo(2);
            });

            assertThat(singleLong(statement, """
                    SELECT COUNT(*) FROM information_schema.COLUMNS
                     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'csp_reports'
                       AND COLUMN_NAME = 'id_uuid'
                    """)).isZero();
            assertThat(singleLong(statement, """
                    SELECT COUNT(*) FROM information_schema.STATISTICS
                     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'csp_reports'
                       AND INDEX_NAME = 'uq_csp_reports_uuid_backfill'
                    """)).isZero();
            assertThat(indexNames(statement)).contains(
                    "idx_csp_reports_hash", "idx_csp_reports_directive", "idx_csp_reports_last_seen");
        }
    }

    private List<List<Object>> businessRows() throws Exception {
        List<List<Object>> rows = new ArrayList<>();
        try (Connection connection = connection(); Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("""
                     SELECT document_uri, blocked_uri, violated_directive, effective_directive,
                            original_policy, disposition, script_sample, status_code, report_hash,
                            occurrence_count, ip_address, user_agent,
                            CAST(first_seen_at AS CHAR), CAST(last_seen_at AS CHAR)
                       FROM csp_reports ORDER BY report_hash
                     """)) {
            while (rs.next()) {
                List<Object> row = new ArrayList<>();
                for (int column = 1; column <= 14; column++) {
                    row.add(rs.getObject(column));
                }
                rows.add(row);
            }
        }
        return rows;
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    private static long singleLong(Statement statement, String sql) throws Exception {
        try (ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static List<String> indexNames(Statement statement) throws Exception {
        List<String> names = new ArrayList<>();
        try (ResultSet rs = statement.executeQuery("""
                SELECT DISTINCT INDEX_NAME FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'csp_reports'
                """)) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        }
        return names;
    }

    private static UUID uuidFromHex(String hex) {
        String value = hex.toLowerCase();
        return UUID.fromString(value.substring(0, 8) + "-" + value.substring(8, 12) + "-"
                + value.substring(12, 16) + "-" + value.substring(16, 20) + "-" + value.substring(20));
    }
}
