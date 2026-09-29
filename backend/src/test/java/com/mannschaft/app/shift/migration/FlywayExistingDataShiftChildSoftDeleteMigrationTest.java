package com.mannschaft.app.shift.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.containers.MySQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-260923-0953 の既存孤児データ保全を実MySQL/Flywayで固定する。
 * 歴史スキーマへ投入するため、現在のEntityでなくSQLでフィクスチャを作る。
 */
@EnabledIf("com.mannschaft.app.shift.migration.FlywayExistingDataShiftScheduleSetNullFkMigrationTest#isDockerAvailable")
class FlywayExistingDataShiftChildSoftDeleteMigrationTest {
    private static final List<String> CHILD_TABLES = List.of(
            "shift_slots", "shift_requests", "shift_assignments");
    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("shift_child_delete").withUsername("test").withPassword("test")
            .withTmpFs(Map.of("/var/lib/mysql", "rw"))
            .withCommand("--log_bin_trust_function_creators=1");

    @BeforeAll
    static void start() {
        MYSQL.start();
    }

    @AfterAll
    static void stop() {
        MYSQL.stop();
    }

    @Test
    void 削除親だけに削除日時をコピーして全行と業務値を保持する() throws Exception {
        Map<String, List<List<String>>> before = new LinkedHashMap<>();
        String deletedParentTimestamp;
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("227.20260929040840")).load().migrate();
        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO shift_schedules
                      (id, team_id, title, period_type, start_date, end_date, status, deleted_at)
                    VALUES (900001, 900001, '削除親', 'WEEKLY', '2026-01-01', '2026-01-07',
                            'PUBLISHED', '2026-02-03 04:05:06'),
                           (900002, 900002, '稼働親', 'WEEKLY', '2026-01-01', '2026-01-07',
                            'PUBLISHED', NULL)
                    """);
            statement.executeUpdate("""
                    INSERT INTO shift_slots
                      (id, schedule_id, slot_date, start_time, end_time, assigned_user_ids, note)
                    VALUES (900001, 900001, '2026-01-02', '09:00:00', '17:00:00', '[900001]', '保持枠'),
                           (900002, 900002, '2026-01-02', '09:00:00', '17:00:00', '[900002]', '稼働枠')
                    """);
            statement.executeUpdate("""
                    INSERT INTO shift_requests
                      (id, schedule_id, user_id, slot_id, slot_date, preference, note)
                    VALUES (900001, 900001, 900001, 900001, '2026-01-02', 'PREFERRED', '保持希望'),
                           (900002, 900002, 900002, NULL, '2026-01-02', 'AVAILABLE', '稼働希望')
                    """);
            statement.executeUpdate("""
                    INSERT INTO shift_assignments (id, slot_id, user_id, status, assigned_by, note)
                    VALUES (900001, 900001, 900001, 'CONFIRMED', 900003, '保持割当'),
                           (900002, 900002, 900002, 'CONFIRMED', 900003, '稼働割当')
                    """);
            try (ResultSet parent = statement.executeQuery(
                    "SELECT deleted_at FROM shift_schedules WHERE id = 900001")) {
                assertThat(parent.next()).isTrue();
                deletedParentTimestamp = parent.getString("deleted_at");
                assertThat(deletedParentTimestamp).isNotNull();
            }
            for (String table : CHILD_TABLES) {
                before.put(table, snapshotBusinessRows(statement, table));
            }
            try (ResultSet request = statement.executeQuery(
                    "SELECT delete_reason, active_uq FROM shift_requests WHERE id = 900001")) {
                assertThat(request.next()).isTrue();
                assertThat(request.getString("delete_reason")).isEqualTo("PARENT_DELETED");
                assertThat(request.getObject("active_uq")).isNull();
            }
            try (ResultSet indexes = statement.executeQuery("""
                    SELECT index_name, GROUP_CONCAT(column_name ORDER BY seq_in_index) AS columns_csv
                    FROM information_schema.statistics
                    WHERE table_schema = DATABASE() AND table_name = 'shift_requests'
                      AND index_name IN ('uq_sr_schedule_user_slot', 'uq_sr_schedule_user_slot_active')
                    GROUP BY index_name
                    """)) {
                assertThat(indexes.next()).isTrue();
                assertThat(indexes.getString("index_name")).isEqualTo("uq_sr_schedule_user_slot_active");
                assertThat(indexes.getString("columns_csv"))
                        .isEqualTo("schedule_id,user_id,slot_id_uq,slot_date_uq,active_uq");
                assertThat(indexes.next()).isFalse();
            }
        }

        Flyway latest = Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").load();
        assertThat(latest.migrate().success).isTrue();
        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             Statement statement = connection.createStatement()) {
            for (String table : CHILD_TABLES) {
                // deleted_at 以外の全列（生成列・version・更新日時も含む）を移行前後で照合する。
                assertThat(snapshotBusinessRows(statement, table)).isEqualTo(before.get(table));
                try (ResultSet rows = statement.executeQuery(
                        "SELECT id, deleted_at FROM " + table + " ORDER BY id")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getLong("id")).isEqualTo(900001L);
                    assertThat(rows.getString("deleted_at")).isEqualTo(deletedParentTimestamp);
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getLong("id")).isEqualTo(900002L);
                    assertThat(rows.getString("deleted_at")).isNull();
                    assertThat(rows.next()).isFalse();
                }
            }
            try (ResultSet row = statement.executeQuery("""
                    SELECT a.status, a.assigned_by, a.note, s.assigned_user_ids, r.preference, r.note
                    FROM shift_assignments a JOIN shift_slots s ON s.id = a.slot_id
                    JOIN shift_requests r ON r.slot_id = s.id WHERE a.id = 900001
                    """)) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString(1)).isEqualTo("CONFIRMED");
                assertThat(row.getLong(2)).isEqualTo(900003L);
                assertThat(row.getString(3)).isEqualTo("保持割当");
                assertThat(row.getString(4)).isEqualTo("[900001]");
                assertThat(row.getString(5)).isEqualTo("PREFERRED");
                assertThat(row.getString(6)).isEqualTo("保持希望");
            }
        }
        assertThat(latest.migrate().migrationsExecuted).isZero();
    }

    /** テーブル名は本クラスの固定リストのみ。日時も含め、SQL値のまま業務列を比較する。 */
    private static List<List<String>> snapshotBusinessRows(Statement statement, String table) throws Exception {
        List<List<String>> snapshot = new ArrayList<>();
        try (ResultSet rows = statement.executeQuery("SELECT * FROM " + table + " ORDER BY id")) {
            while (rows.next()) {
                List<String> values = new ArrayList<>();
                for (int column = 1; column <= rows.getMetaData().getColumnCount(); column++) {
                    String columnName = rows.getMetaData().getColumnName(column);
                    if (!List.of("deleted_at", "delete_reason", "active_uq").contains(columnName)) {
                        values.add(rows.getString(column));
                    }
                }
                snapshot.add(values);
            }
        }
        return snapshot;
    }
}
