package com.mannschaft.app.activity.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.containers.MySQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 既存activity移行金型と同じ専用MySQLで、236系の実既存行を237のDDLへ移行する。 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class FlywayExistingDataAutomaticActivityFlagsMigrationTest {
    @Test
    void 既存通常と削除行の実績とversionを保持し二flagsはfalseになる() throws Exception {
        try (var mysql = new MySQLContainer<>("mysql:8.0").withDatabaseName("automatic_flags_migration")
                .withUsername("test").withPassword("test").withTmpFs(java.util.Map.of("/var/lib/mysql", "rw"))
                .withCommand("--log_bin_trust_function_creators=1")) {
            mysql.start();
            var before = Flyway.configure().dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                    .locations("classpath:db/migration").outOfOrder(false)
                    .target(MigrationVersion.fromVersion("236.20261007094759")).load().migrate();
            assertThat(before.success).isTrue();
            List<List<Object>> existing;
            try (var connection = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())) {
                assertThat(flagColumns(connection)).isEmpty();
                try (var insert = connection.prepareStatement("INSERT INTO activity_results "
                        + "(id,scope_type,scope_id,title,activity_date,status,visibility,description,field_values,attachments,"
                        + "created_by,created_at,updated_at,version,deleted_at) "
                        + "VALUES (?,'TEAM',100,'移行前題名','2026-10-08','DRAFT','MEMBERS_ONLY','移行前本文',"
                        + "'{\"zero\":0,\"flag\":false,\"empty\":\"\"}','{\"file_ids\":[]}',1,NOW(),NOW(),7,?)")) {
                    for (int i = 0; i < 2; i++) {
                        insert.setLong(1, 910208001L + i);
                        insert.setTimestamp(2, i == 0 ? null : java.sql.Timestamp.valueOf("2026-10-08 00:00:00"));
                        insert.executeUpdate();
                    }
                }
                existing = actualRows(connection);
                assertThat(existing).hasSize(2);
            }
            var after = Flyway.configure().dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                    .locations("classpath:db/migration").outOfOrder(false).load().migrate();
            assertThat(after.success).isTrue();
            assertThat(after.migrationsExecuted).isEqualTo(1);
            try (var connection = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())) {
                assertThat(actualRows(connection)).isEqualTo(existing);
                assertThat(flagColumns(connection)).containsExactlyInAnyOrder(
                        "is_auto_generated_from_schedule:NO:0", "is_planned:NO:0");
                try (var query = connection.createStatement(); var rows = query.executeQuery("SELECT "
                        + "is_auto_generated_from_schedule,is_planned FROM activity_results WHERE id IN (910208001,910208002)")) {
                    int count = 0;
                    while (rows.next()) {
                        assertThat(rows.getBoolean(1)).isFalse();
                        assertThat(rows.getBoolean(2)).isFalse();
                        count++;
                    }
                    assertThat(count).isEqualTo(2);
                }
            }
        }
    }

    private List<List<Object>> actualRows(Connection connection) throws Exception {
        var values = new ArrayList<List<Object>>();
        try (var query = connection.createStatement(); var rows = query.executeQuery("SELECT "
                + "id,title,description,field_values,attachments,version,deleted_at FROM activity_results "
                + "WHERE id IN (910208001,910208002) ORDER BY id")) {
            while (rows.next()) {
                var row = new ArrayList<Object>();
                for (int i = 1; i <= 7; i++) row.add(rows.getObject(i));
                values.add(row);
            }
        }
        return values;
    }

    private List<String> flagColumns(Connection connection) throws Exception {
        var values = new ArrayList<String>();
        try (var query = connection.createStatement(); var rows = query.executeQuery("SELECT "
                + "COLUMN_NAME,IS_NULLABLE,COLUMN_DEFAULT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() "
                + "AND TABLE_NAME='activity_results' AND COLUMN_NAME IN ('is_auto_generated_from_schedule','is_planned')")) {
            while (rows.next()) values.add(rows.getString(1) + ":" + rows.getString(2) + ":" + rows.getString(3));
        }
        return values;
    }
}
