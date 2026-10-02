package com.mannschaft.app.match.migration;

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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 組織に属さない単独チーム試合を保持したまま V181 を適用できることを検証する。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.match.migration.FlywayExistingStandaloneTeamMatchMigrationTest#isDockerAvailable")
@DisplayName("Flyway 既存単独チーム試合 migration（V181）")
class FlywayExistingStandaloneTeamMatchMigrationTest {

    private static final String PRE_V181_TARGET = "180.20260811135837";

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_standalone_match")
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

    @Test
    @DisplayName("organization_id が NULL の既存試合を保持し UNSIGNED NULL へ移行できる")
    void existingStandaloneMatchSurvivesUnsignedMigration() throws Exception {
        Flyway pre = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .target(MigrationVersion.fromVersion(PRE_V181_TARGET))
                .load();
        assertThat(pre.migrate().success).isTrue();

        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            // 障害が発生した既存DBと同じく、V181適用前は organization_id が NULL 許容だった状態を再現する。
            statement.executeUpdate("""
                    ALTER TABLE matches MODIFY COLUMN organization_id BIGINT NULL
                    """);
            statement.executeUpdate("""
                    INSERT INTO matches (id, organization_id, team_id, kind, created_by)
                    VALUES (UUID_TO_BIN(UUID()), NULL, 92001, 'PRACTICE', 93001)
                    """);
        }

        Flyway rest = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .load();
        MigrateResult result = rest.migrate();
        assertThat(result.success).isTrue();

        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            try (ResultSet row = statement.executeQuery("""
                    SELECT COUNT(*) FROM matches WHERE team_id = 92001 AND organization_id IS NULL
                    """)) {
                row.next();
                assertThat(row.getInt(1)).as("単独チーム試合が NULL のまま保持されること").isEqualTo(1);
            }

            try (ResultSet column = statement.executeQuery("""
                    SELECT IS_NULLABLE, COLUMN_TYPE
                    FROM information_schema.columns
                    WHERE table_schema = DATABASE()
                      AND table_name = 'matches'
                      AND column_name = 'organization_id'
                    """)) {
                column.next();
                assertThat(column.getString("IS_NULLABLE")).isEqualTo("YES");
                assertThat(column.getString("COLUMN_TYPE")).isEqualTo("bigint unsigned");
            }
        }
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }
}
