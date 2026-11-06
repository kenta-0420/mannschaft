package com.mannschaft.app.shiftbudget;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CMP-261021-0718 の公開世代と取消履歴のDDL契約を実MySQL/Flywayで検査する。
 *
 * <p>旧schemaに現在のEntityを保存できないため、専用containerのJDBC fixtureを使う。
 * テスト自身はDDLを作らず、全量migrationを旧最終versionから追加適用する経路と空DBからの経路を通す。
 * 業務Serviceの取消・再公開やトランザクション境界は別の受入テストが担当する。</p>
 */
@EnabledIf("com.mannschaft.app.shiftbudget.ShiftBudgetPublicationMigrationIT#isDockerAvailable")
class ShiftBudgetPublicationMigrationIT {
    private static final String LEGACY_VERSION = "256.20261103144645";
    private static final String PUBLICATION_VERSION = "257.20261105223000";
    private static final String PUBLICATION_MIGRATION =
            "/db/migration/V257.20261105223000__add_shift_budget_publication_generation.sql";
    private static final List<String> TABLES = List.of("shift_schedules", "shift_budget_consumptions");
    private static final String TARGET_TABLES = "('shift_schedules', 'shift_budget_consumptions')";
    private static final String UNIQUE_INDEX = "uq_sbc_slot_user_status";

    static boolean isDockerAvailable() {
        return DockerClientFactory.instance().isDockerAvailable();
    }

    @Test
    void 既存全列を保持して世代0を追加し取消履歴だけを複数保存できる() throws Exception {
        try (MySQLContainer<?> mysql = mysql("shift_publication_existing")) {
            mysql.start();
            migrate(mysql, LEGACY_VERSION);
            Map<String, List<Map<String, String>>> beforeRows = new LinkedHashMap<>();
            Map<String, List<List<String>>> beforeSchema;
            try (Connection connection = connect(mysql); Statement sql = connection.createStatement()) {
                insertLegacyFixture(sql);
                for (String table : TABLES) {
                    beforeRows.put(table, snapshotRows(sql, table));
                }
                beforeSchema = snapshotUnchangedSchema(sql);
            }

            migrate(mysql, publicationTarget());

            try (Connection connection = connect(mysql); Statement sql = connection.createStatement()) {
                // 未実装時は新列の存在assertionでredにし、存在しない列へのSELECTエラーで代替しない。
                assertPublicationSchema(sql);
                assertThat(snapshotUnchangedSchema(sql)).isEqualTo(beforeSchema);
                for (String table : TABLES) {
                    assertThat(snapshotRows(sql, table)).as(table + "の旧全列・日時・理由を保全する")
                            .isEqualTo(beforeRows.get(table));
                    assertThat(query(sql, "SELECT DISTINCT publication_generation FROM " + table))
                            .containsExactly(List.of("0"));
                }
                assertActiveStatusValues(sql);
                assertHistoryAndUniqueness(sql);
            }
            assertThat(flyway(mysql, publicationTarget()).migrate().migrationsExecuted).isZero();
        }
    }

    @Test
    void 空DBへの全量適用でも同じ世代列と生成列と一意索引を構築する() throws Exception {
        try (MySQLContainer<?> mysql = mysql("shift_publication_empty")) {
            mysql.start();

            migrate(mysql, publicationTarget());

            try (Connection connection = connect(mysql); Statement sql = connection.createStatement()) {
                assertPublicationSchema(sql);
            }
        }
    }

    private static MySQLContainer<?> mysql(String database) {
        return new MySQLContainer<>("mysql:8.0").withDatabaseName(database)
                .withUsername("test").withPassword("test")
                .withTmpFs(Map.of("/var/lib/mysql", "rw"))
                .withCommand("--log_bin_trust_function_creators=1");
    }

    private static Flyway flyway(MySQLContainer<?> mysql, String target) {
        return Flyway.configure().dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .locations("classpath:db/migration").target(target).outOfOrder(false).load();
    }

    private static void migrate(MySQLContainer<?> mysql, String target) {
        Flyway migration = flyway(mysql, target);
        assertThat(migration.migrate().success).isTrue();
        assertThat(migration.info().current().getVersion().getVersion()).isEqualTo(target);
    }

    /** 未実装redだけ旧版で停止し、新DDL導入後は必ず予約versionまで実適用する。 */
    private static String publicationTarget() {
        return ShiftBudgetPublicationMigrationIT.class.getResource(PUBLICATION_MIGRATION) == null
                ? LEGACY_VERSION : PUBLICATION_VERSION;
    }

    private static Connection connect(MySQLContainer<?> mysql) throws SQLException {
        return DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
    }

    /** クロスdomain FK撤廃後の論理ID参照を使い、消化の同一domain親だけを用意する。 */
    private static void insertLegacyFixture(Statement sql) throws SQLException {
        sql.executeUpdate("""
                INSERT INTO shift_schedules
                  (id,team_id,title,period_type,start_date,end_date,status,request_deadline,note,
                   created_by,published_at,published_by,is_reminder_sent,is_low_submission_alerted,
                   last_auto_transition_at,version,created_at,updated_at,deleted_at,
                   is_reminder_sent_48h,linked_project_id)
                VALUES (900001,900001,'公開済み旧行','WEEKLY','2026-01-01','2026-01-07','PUBLISHED',
                        '2025-12-30 01:02:03','保持する公開メモ',900002,'2025-12-31 02:03:04',900003,
                        1,1,'2025-12-31 03:04:05',7,'2025-12-01 04:05:06','2025-12-31 05:06:07',
                        '2026-02-03 04:05:06',1,900004),
                       (900002,900002,'下書き旧行','WEEKLY','2026-02-01','2026-02-07','DRAFT',
                        NULL,NULL,NULL,NULL,NULL,0,0,NULL,0,'2025-12-02 04:05:06',
                        '2025-12-02 04:05:06',NULL,0,NULL)
                """);
        sql.executeUpdate("""
                INSERT INTO shift_budget_allocations
                  (id,organization_id,team_id,fiscal_year_id,budget_category_id,period_start,
                   period_end,allocated_amount,consumed_amount,confirmed_amount,created_by)
                VALUES (900001,900001,900001,900001,900001,'2026-01-01','2026-01-31',100000,6000,2000,900002)
                """);
        sql.executeUpdate("""
                INSERT INTO shift_budget_consumptions
                  (id,allocation_id,shift_id,slot_id,user_id,hourly_rate_snapshot,hours,amount,currency,
                   status,recorded_at,confirmed_at,cancelled_at,cancel_reason,created_at,updated_at,deleted_at)
                VALUES (900001,900001,900001,900001,900001,1234.50,1.25,1543.13,'JPY','PLANNED',
                        '2026-01-01 01:02:03',NULL,NULL,NULL,'2026-01-01 02:03:04','2026-01-02 03:04:05',NULL),
                       (900002,900001,900001,900001,900001,1500.00,2.00,3000.00,'JPY','CONFIRMED',
                        '2026-01-02 01:02:03','2026-01-03 02:03:04',NULL,NULL,
                        '2026-01-02 02:03:04','2026-01-03 03:04:05',NULL),
                       (900003,900001,900001,900001,900001,1800.00,0.50,900.00,'JPY','CANCELLED',
                        '2026-01-03 01:02:03',NULL,'2026-01-04 02:03:04','SHIFT_DELETED',
                        '2026-01-03 02:03:04','2026-01-04 03:04:05',NULL),
                       (900004,900001,900001,900001,900001,2000.00,3.00,6000.00,'JPY','PLANNED',
                        '2026-01-04 01:02:03',NULL,NULL,NULL,
                        '2026-01-04 02:03:04','2026-01-05 03:04:05','2026-02-03 04:05:06'),
                       (900005,900001,900001,900001,900001,2100.00,0.00,0.00,'JPY','CONFIRMED',
                        '2026-01-05 01:02:03','2026-01-06 02:03:04',NULL,NULL,
                        '2026-01-05 02:03:04','2026-01-06 03:04:05','2026-02-03 04:05:06'),
                       (900006,900001,900001,900001,900001,2200.00,1.00,2200.00,'JPY','CANCELLED',
                        '2026-01-06 01:02:03','2026-01-07 01:02:03','2026-01-08 02:03:04','POST_CLOSE_REVERSE',
                        '2026-01-06 02:03:04','2026-01-08 03:04:05','2026-02-03 04:05:06')
                """);
    }

    private static void assertPublicationSchema(Statement sql) throws SQLException {
        for (String table : TABLES) {
            assertThat(query(sql, """
                    SELECT COLUMN_TYPE,IS_NULLABLE,COLUMN_DEFAULT FROM information_schema.COLUMNS
                    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='%s' AND COLUMN_NAME='publication_generation'
                    """.formatted(table))).as(table + ".publication_generationはBIGINT NOT NULL DEFAULT 0")
                    .containsExactly(List.of("bigint", "NO", "0"));
        }
        try (ResultSet column = sql.executeQuery("""
                SELECT COLUMN_TYPE,IS_NULLABLE,EXTRA,GENERATION_EXPRESSION,COLLATION_NAME
                FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
                  AND TABLE_NAME='shift_budget_consumptions' AND COLUMN_NAME='active_status'
                """)) {
            assertThat(column.next()).as("active_status生成列が存在する").isTrue();
            assertThat(column.getString("COLUMN_TYPE")).isEqualTo("varchar(20)");
            assertThat(column.getString("IS_NULLABLE")).isEqualTo("YES");
            assertThat(column.getString("EXTRA")).contains("STORED GENERATED");
            assertThat(normalizeExpression(column.getString("GENERATION_EXPRESSION")))
                    .isEqualTo("casewhenstatus='cancelled'thennullelsestatusend");
            assertThat(column.getString("COLLATION_NAME")).isEqualTo("utf8mb4_0900_ai_ci");
            assertThat(column.next()).isFalse();
        }
        try (ResultSet column = sql.executeQuery("""
                SELECT DATA_TYPE,IS_NULLABLE,EXTRA,GENERATION_EXPRESSION FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='shift_budget_consumptions'
                  AND COLUMN_NAME='deleted_at_uq'
                """)) {
            assertThat(column.next()).isTrue();
            assertThat(column.getString("DATA_TYPE")).isEqualTo("datetime");
            assertThat(column.getString("IS_NULLABLE")).isEqualTo("NO");
            assertThat(column.getString("EXTRA")).contains("STORED GENERATED");
            assertThat(normalizeExpression(column.getString("GENERATION_EXPRESSION")))
                    .isEqualTo("coalescedeleted_at,'9999-12-3100:00:00'");
            assertThat(column.next()).isFalse();
        }
        assertThat(query(sql, """
                SELECT NON_UNIQUE,GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX)
                FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
                  AND TABLE_NAME='shift_budget_consumptions' AND INDEX_NAME='uq_sbc_slot_user_status'
                GROUP BY INDEX_NAME,NON_UNIQUE
                """)).containsExactly(List.of("0", "slot_id,user_id,active_status,deleted_at_uq"));
        assertThat(query(sql, "SELECT TABLE_COLLATION FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN " + TARGET_TABLES + " ORDER BY TABLE_NAME"))
                .containsExactly(List.of("utf8mb4_0900_ai_ci"), List.of("utf8mb4_0900_ai_ci"));
    }

    private static String normalizeExpression(String expression) {
        return expression.toLowerCase(Locale.ROOT).replace("_utf8mb4", "")
                .replace("\\'", "'").replace("`", "").replaceAll("[\\s()]", "");
    }

    private static void assertActiveStatusValues(Statement sql) throws SQLException {
        try (ResultSet rows = sql.executeQuery("SELECT status,active_status,deleted_at,deleted_at_uq "
                + "FROM shift_budget_consumptions ORDER BY id")) {
            int count = 0;
            while (rows.next()) {
                count++;
                assertThat(rows.getString("active_status")).isEqualTo(
                        "CANCELLED".equals(rows.getString("status")) ? null : rows.getString("status"));
                assertThat(rows.getString("deleted_at_uq")).isEqualTo(rows.getString("deleted_at") == null
                        ? "9999-12-31 00:00:00" : rows.getString("deleted_at"));
            }
            assertThat(count).isEqualTo(6);
        }
    }

    private static void assertHistoryAndUniqueness(Statement sql) throws SQLException {
        // 同じslot/userのPLANNEDとCONFIRMEDは元から共存できる。相互排他は新設しない。
        assertDuplicate(sql, 900011, "PLANNED", null);
        assertDuplicate(sql, 900012, "CONFIRMED", null);
        insertConsumption(sql, 900013, "CANCELLED", null);
        insertConsumption(sql, 900014, "CANCELLED", null);
        insertConsumption(sql, 900015, "CANCELLED", "2026-02-03 04:05:06");
        assertDuplicate(sql, 900016, "PLANNED", "2026-02-03 04:05:06");
        assertDuplicate(sql, 900017, "CONFIRMED", "2026-02-03 04:05:06");
        insertConsumption(sql, 900018, "PLANNED", "2026-02-04 04:05:06");
        insertConsumption(sql, 900019, "CONFIRMED", "2026-02-04 04:05:06");
        assertThat(query(sql, "SELECT COUNT(*) FROM shift_budget_consumptions WHERE status='CANCELLED'"))
                .containsExactly(List.of("5"));
        assertThat(query(sql, "SELECT DISTINCT publication_generation FROM shift_budget_consumptions"))
                .containsExactly(List.of("0"));
        assertThat(query(sql, "SELECT COUNT(*) FROM shift_budget_consumptions WHERE status='CANCELLED' "
                + "AND active_status IS NOT NULL")).containsExactly(List.of("0"));
    }

    private static void assertDuplicate(Statement sql, long id, String status, String deletedAt) {
        assertThatThrownBy(() -> insertConsumption(sql, id, status, deletedAt))
                .isInstanceOfSatisfying(SQLException.class, error -> {
                    assertThat(error.getErrorCode()).isEqualTo(1062);
                    assertThat(error.getSQLState()).isEqualTo("23000");
                    assertThat(error.getMessage()).contains(UNIQUE_INDEX);
                });
    }

    private static void insertConsumption(Statement sql, long id, String status, String deletedAt)
            throws SQLException {
        try (var insert = sql.getConnection().prepareStatement("""
                INSERT INTO shift_budget_consumptions
                  (id,allocation_id,shift_id,slot_id,user_id,hourly_rate_snapshot,hours,amount,status,deleted_at)
                VALUES (?,900001,900001,900001,900001,1000,1,1000,?,?)
                """)) {
            insert.setLong(1, id);
            insert.setString(2, status);
            insert.setString(3, deletedAt);
            assertThat(insert.executeUpdate()).isEqualTo(1);
        }
    }

    /** 固定テーブルの全旧列を列名付きで保存し、新列の位置によらずNULL・日時もそのまま照合する。 */
    private static List<Map<String, String>> snapshotRows(Statement sql, String table) throws SQLException {
        List<Map<String, String>> result = new ArrayList<>();
        try (ResultSet rows = sql.executeQuery("SELECT * FROM " + table + " ORDER BY id")) {
            while (rows.next()) {
                Map<String, String> values = new LinkedHashMap<>();
                for (int i = 1; i <= rows.getMetaData().getColumnCount(); i++) {
                    String column = rows.getMetaData().getColumnName(i);
                    if (!List.of("publication_generation", "active_status").contains(column)) {
                        values.put(column, rows.getString(i));
                    }
                }
                result.add(values);
            }
        }
        return result;
    }

    private static Map<String, List<List<String>>> snapshotUnchangedSchema(Statement sql) throws SQLException {
        Map<String, List<List<String>>> result = new LinkedHashMap<>();
        result.put("旧列", query(sql, """
                SELECT TABLE_NAME,COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,COLUMN_DEFAULT,EXTRA,
                       GENERATION_EXPRESSION,COLLATION_NAME
                FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
                  AND TABLE_NAME IN %s AND COLUMN_NAME NOT IN ('publication_generation','active_status')
                ORDER BY TABLE_NAME,COLUMN_NAME
                """.formatted(TARGET_TABLES)));
        result.put("他の索引", query(sql, """
                SELECT TABLE_NAME,INDEX_NAME,NON_UNIQUE,SEQ_IN_INDEX,COLUMN_NAME,EXPRESSION,
                       SUB_PART,COLLATION,INDEX_TYPE,IS_VISIBLE
                FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE()
                  AND TABLE_NAME IN %s AND INDEX_NAME <> 'uq_sbc_slot_user_status'
                ORDER BY TABLE_NAME,INDEX_NAME,SEQ_IN_INDEX
                """.formatted(TARGET_TABLES)));
        result.put("CHECK", query(sql, """
                SELECT t.TABLE_NAME,t.CONSTRAINT_NAME,c.CHECK_CLAUSE,t.ENFORCED
                FROM information_schema.TABLE_CONSTRAINTS t JOIN information_schema.CHECK_CONSTRAINTS c
                  ON c.CONSTRAINT_SCHEMA=t.CONSTRAINT_SCHEMA AND c.CONSTRAINT_NAME=t.CONSTRAINT_NAME
                WHERE t.CONSTRAINT_SCHEMA=DATABASE() AND t.TABLE_NAME IN %s
                ORDER BY t.TABLE_NAME,t.CONSTRAINT_NAME
                """.formatted(TARGET_TABLES)));
        result.put("FK", query(sql, """
                SELECT TABLE_NAME,CONSTRAINT_NAME,REFERENCED_TABLE_NAME,UPDATE_RULE,DELETE_RULE
                FROM information_schema.REFERENTIAL_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE()
                ORDER BY TABLE_NAME,CONSTRAINT_NAME
                """));
        result.put("テーブル", query(sql, """
                SELECT TABLE_NAME,TABLE_TYPE,ENGINE,TABLE_COLLATION FROM information_schema.TABLES
                WHERE TABLE_SCHEMA=DATABASE() ORDER BY TABLE_NAME
                """));
        return result;
    }

    private static List<List<String>> query(Statement sql, String query) throws SQLException {
        List<List<String>> result = new ArrayList<>();
        try (ResultSet rows = sql.executeQuery(query)) {
            while (rows.next()) {
                List<String> values = new ArrayList<>();
                for (int i = 1; i <= rows.getMetaData().getColumnCount(); i++) {
                    values.add(rows.getString(i));
                }
                result.add(values);
            }
        }
        return result;
    }
}
