package com.mannschaft.app.common.migration;

import org.flywaydb.core.Flyway;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-260826-1457: {@code invite_token_claims} の索引・FK・照合順序を<b>実 Flyway migration</b>で検証する（陣立て書 §9.1）。
 *
 * <p>統合テスト（IT）のスキーマは test プロファイルの {@code ddl-auto=create}（Entity 由来）で、migration に書いた
 * 索引・FK・照合順序は存在しない。そのため DDL の形はここで fresh な MySQL に全 migration を流して確かめる。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.common.migration.FlywayInviteTokenClaimsMigrationTest#isDockerAvailable")
@DisplayName("Flyway invite_token_claims の索引・FK・照合順序")
class FlywayInviteTokenClaimsMigrationTest {

    private static final String TABLE = "invite_token_claims";

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_invite_claims")
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
    void migrate() {
        MYSQL.start();
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @AfterAll
    void stopContainer() {
        MYSQL.stop();
    }

    @Test
    @DisplayName("M1: 索引 (status,created_at)・(token_id)、一意制約なし、FK は invite_tokens へ CASCADE なし、照合順序 utf8mb4_0900_ai_ci")
    void 索引とFKと照合順序が揃う() throws Exception {
        try (Connection c = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             Statement s = c.createStatement()) {
            List<String> indexes = new ArrayList<>();
            try (ResultSet rs = s.executeQuery("SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS cols "
                    + "FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '" + TABLE
                    + "' GROUP BY INDEX_NAME")) {
                while (rs.next()) {
                    indexes.add(rs.getString("cols"));
                }
            }
            assertThat(indexes).contains("status,created_at", "token_id");

            try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM information_schema.STATISTICS "
                    + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '" + TABLE
                    + "' AND NON_UNIQUE = 0 AND INDEX_NAME <> 'PRIMARY'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong(1)).as("(token_id,user_id) 一意制約は付けない").isZero();
            }

            List<String> fks = new ArrayList<>();
            try (ResultSet rs = s.executeQuery("SELECT k.COLUMN_NAME, k.REFERENCED_TABLE_NAME, r.DELETE_RULE "
                    + "FROM information_schema.KEY_COLUMN_USAGE k JOIN information_schema.REFERENTIAL_CONSTRAINTS r "
                    + "ON r.CONSTRAINT_SCHEMA = k.CONSTRAINT_SCHEMA AND r.CONSTRAINT_NAME = k.CONSTRAINT_NAME "
                    + "WHERE k.TABLE_SCHEMA = DATABASE() AND k.TABLE_NAME = '" + TABLE
                    + "' AND k.REFERENCED_TABLE_NAME IS NOT NULL")) {
                while (rs.next()) {
                    assertThat(rs.getString("DELETE_RULE")).as("CASCADE なし").isNotEqualTo("CASCADE");
                    fks.add(rs.getString("COLUMN_NAME") + "->" + rs.getString("REFERENCED_TABLE_NAME"));
                }
            }
            assertThat(fks).containsExactly("token_id->invite_tokens");

            try (ResultSet rs = s.executeQuery("SELECT TABLE_COLLATION FROM information_schema.TABLES "
                    + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '" + TABLE + "'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("utf8mb4_0900_ai_ci");
            }
        }
    }
}
