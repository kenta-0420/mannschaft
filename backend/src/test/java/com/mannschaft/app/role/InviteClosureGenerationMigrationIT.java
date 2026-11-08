package com.mannschaft.app.role;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;

import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #3892 G01〜03: 実Flywayの直前schemaへ既存招待を置き、世代列追加だけでデータを保全する。
 *
 * <p>旧schemaには新Entityを永続化できないため、既存行fixtureはJDBCで作る。team/org行は作らず、
 * クロスdomain FK撤廃済みの論理ID参照を使う。通常Service ITとは異なりSpringを起動せず、
 * 実MySQLへのFlyway適用自体を観測する既存migrationテストの構成を使用する。</p>
 */
@EnabledIf("com.mannschaft.app.role.InviteClosureGenerationMigrationIT#isDockerAvailable")
@DisplayName("個人招待の閉鎖世代migrationは既存開発行を保全する")
class InviteClosureGenerationMigrationIT {

    static boolean isDockerAvailable() {
        return DockerClientFactory.instance().isDockerAvailable();
    }

    @Test
    @DisplayName("G01〜03: 新世代は0、既存ID/token/期限/回数/既失効日時を変更しない")
    void migrationPreservesExistingInvitesWithoutGenerationBackfill() throws Exception {
        try (MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
                .withDatabaseName("invite_generation_migration")
                .withUsername("test").withPassword("test")
                .withTmpFs(Map.of("/var/lib/mysql", "rw"))
                .withCommand("--log_bin_trust_function_creators=1")) {
            mysql.start();
            migrate(mysql, "247.20261008090647");
            String token = UUID.randomUUID().toString();
            LocalDateTime expires = LocalDateTime.of(2027, 1, 2, 3, 4, 5);
            LocalDateTime revoked = LocalDateTime.of(2026, 1, 2, 3, 4, 5);
            long id;
            try (var connection = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())) {
                try (var statement = connection.prepareStatement("""
                        INSERT INTO invite_tokens
                            (token, team_id, role_id, expires_at, max_uses, used_count, revoked_at, target_user_id, created_at)
                        SELECT ?, 123456, id, ?, 5, 2, ?, 90123, UTC_TIMESTAMP()
                        FROM roles WHERE name = 'MEMBER'
                        """)) {
                    statement.setString(1, token);
                    statement.setObject(2, expires);
                    statement.setObject(3, revoked);
                    assertThat(statement.executeUpdate()).isEqualTo(1);
                }
                try (var statement = connection.prepareStatement("SELECT id FROM invite_tokens WHERE token = ?")) {
                    statement.setString(1, token);
                    try (ResultSet result = statement.executeQuery()) {
                        assertThat(result.next()).isTrue();
                        id = result.getLong(1);
                    }
                }
            }

            migrate(mysql, "248.20261016010844");

            try (var connection = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
                 var statement = connection.prepareStatement("SELECT * FROM invite_tokens WHERE id = ?")) {
                statement.setLong(1, id);
                try (ResultSet result = statement.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString("token")).isEqualTo(token);
                    assertThat(result.getLong("team_id")).isEqualTo(123456L);
                    assertThat(result.getObject("expires_at", LocalDateTime.class)).isEqualTo(expires);
                    assertThat(result.getInt("max_uses")).isEqualTo(5);
                    assertThat(result.getInt("used_count")).isEqualTo(2);
                    assertThat(result.getLong("target_user_id")).isEqualTo(90123L);
                    assertThat(result.getObject("revoked_at", LocalDateTime.class)).isEqualTo(revoked);
                    assertThat(result.getLong("scope_closure_generation")).isZero();
                    assertThat(result.wasNull()).isFalse();
                    assertThat(result.next()).isFalse();
                }
            }
            try (var connection = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
                 var statement = connection.prepareStatement("""
                         SELECT DATA_TYPE, IS_NULLABLE, COLUMN_DEFAULT FROM information_schema.COLUMNS
                         WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'invite_tokens'
                           AND COLUMN_NAME = 'scope_closure_generation'
                         """);
                 ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("DATA_TYPE")).isEqualTo("bigint");
                assertThat(result.getString("IS_NULLABLE")).isEqualTo("NO");
                assertThat(result.getString("COLUMN_DEFAULT")).isEqualTo("0");
                assertThat(result.next()).isFalse();
            }
        }
    }

    private void migrate(MySQLContainer<?> mysql, String target) {
        Flyway.configure().dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .locations("classpath:db/migration").target(target).outOfOrder(false).load().migrate();
    }
}
