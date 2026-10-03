package com.mannschaft.app.common.storage.acl.migration;

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
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/** V192既存行へV209を適用する実MySQL/Flyway番人。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.common.storage.acl.migration.FlywayStorageAclClaimBoundaryMigrationTest#isDockerAvailable")
@DisplayName("Flyway Storage ACL claim境界移行（V192からV209）")
class FlywayStorageAclClaimBoundaryMigrationTest {

    private static final String PRE_V209_TARGET = "192.20260829120000";

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_storage_acl_claim")
            .withUsername("test")
            .withPassword("test")
            .withTmpFs(java.util.Map.of("/var/lib/mysql", "rw"))
            .withCommand("--log_bin_trust_function_creators=1");

    public static boolean isDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception exception) {
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

    @Test
    @DisplayName("V192のlegacy行を保ったままV209でscope_keyと親参照をbackfillする")
    void v209MigratesExistingV192StorageAcl() throws Exception {
        Flyway pre = flyway(MigrationVersion.fromVersion(PRE_V209_TARGET));
        assertThat(pre.migrate().success).isTrue();

        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO storage_acls "
                    + "(id, file_key, owner_id, scope_type, scope_id, acl_mode, content_type, reference_type, "
                    + "reference_id, status, expires_at, created_at, updated_at) "
                    + "VALUES (UUID_TO_BIN(UUID()), 'legacy/storage-acl', 7, 'TEAM', 77, 'CONTENT_BOUND', "
                    + "'image/png', 'WORKFLOW_REQUEST', 88, 'PENDING', DATE_ADD(UTC_TIMESTAMP(), INTERVAL 1 HOUR), "
                    + "UTC_TIMESTAMP(), UTC_TIMESTAMP())");
        }

        assertThat(flyway(null).migrate().success).isTrue();

        try (Connection connection = connection()) {
            try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(
                    "SELECT scope_id, scope_key, reference_type, reference_id, parent_content_reference_type, "
                            + "parent_content_reference_key FROM storage_acls WHERE file_key = 'legacy/storage-acl'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong("scope_id")).isEqualTo(77L);
                assertThat(rs.getString("scope_key")).isEqualTo("77");
                assertThat(rs.getString("reference_type")).isEqualTo("WORKFLOW_REQUEST");
                assertThat(rs.getLong("reference_id")).isEqualTo(88L);
                assertThat(rs.getString("parent_content_reference_type")).isEqualTo("WORKFLOW_REQUEST");
                assertThat(rs.getString("parent_content_reference_key")).isEqualTo("88");
            }
            assertThat(columnNullable(connection, "storage_acls", "scope_id")).isTrue();
            assertThat(indexExists(connection, "storage_acls", "idx_storage_acls_scope_key")).isTrue();
        }
    }

    private Flyway flyway(MigrationVersion target) {
        var configuration = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false);
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    private boolean columnNullable(Connection connection, String table, String column) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(
                "SELECT IS_NULLABLE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                        + "AND TABLE_NAME = '" + table + "' AND COLUMN_NAME = '" + column + "'")) {
            assertThat(rs.next()).isTrue();
            return "YES".equalsIgnoreCase(rs.getString(1));
        }
    }

    private boolean indexExists(Connection connection, String table, String index) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(
                "SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() "
                        + "AND TABLE_NAME = '" + table + "' AND INDEX_NAME = '" + index + "'")) {
            rs.next();
            return rs.getLong(1) > 0;
        }
    }
}
