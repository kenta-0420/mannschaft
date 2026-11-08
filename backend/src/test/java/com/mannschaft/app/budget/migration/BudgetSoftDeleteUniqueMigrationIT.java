package com.mannschaft.app.budget.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 論理削除後の同名再作成と旧履歴保持を正本DDL・実MySQL/Flywayで検査する(AC13)。
 * 全量migrationを重複再生せず、今回の正本2DDLと新migrationだけを隔離locationsへコピーする。
 * 歴史スキーマの制約検査なので、現在のEntityでなく専用container内のJDBC fixtureを使う。
 */
@EnabledIf("com.mannschaft.app.budget.migration.BudgetSoftDeleteUniqueMigrationIT#isDockerAvailable")
class BudgetSoftDeleteUniqueMigrationIT {
    private static final String YEAR_INSERT = "INSERT INTO budget_fiscal_years (id,scope_type,scope_id,name,start_date,end_date,created_by) VALUES (11,'TEAM',42,'年度','2026-01-01','2026-12-31',42)";
    private static final String CHILD_INSERT = "INSERT INTO budget_categories (id,fiscal_year_id,parent_id,name,category_type) VALUES (32,20,30,'子費目','EXPENSE')";
    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("budget_unique_migration").withUsername("test").withPassword("test")
            .withTmpFs(Map.of("/var/lib/mysql", "rw"));
    @TempDir Path migrations;

    @BeforeAll static void start() { MYSQL.start(); }
    @AfterAll static void stop() { MYSQL.stop(); }
    public static boolean isDockerAvailable() { return DockerClientFactory.instance().isDockerAvailable(); }

    @Test
    void 削除済み年度と子費目の同名再作成を許可し生存一意性と旧業務値を保持する() throws Exception {
        copyMigration("V11.020__create_budget_fiscal_years.sql");
        copyMigration("V11.021__create_budget_categories.sql");
        try (Connection connection = connect(); Statement sql = connection.createStatement()) {
            // 対象DDLのFK前提だけを専用DBへ用意する。共有singleton試験DBにはDDLを加えない。
            sql.execute("CREATE TABLE users (id BIGINT UNSIGNED PRIMARY KEY)");
            sql.execute("INSERT INTO users(id) VALUES (42)");
        }
        flyway().migrate();
        List<List<String>> beforeYears;
        List<List<String>> beforeCategories;
        try (Connection connection = connect(); Statement sql = connection.createStatement()) {
            sql.executeUpdate("""
                    INSERT INTO budget_fiscal_years
                    (id,scope_type,scope_id,name,start_date,end_date,created_by,deleted_at)
                    VALUES (10,'TEAM',42,'年度','2026-01-01','2026-12-31',42,'2026-02-03 04:05:06'),
                           (20,'TEAM',42,'有効年度','2027-01-01','2027-12-31',42,NULL)
                    """);
            sql.executeUpdate("""
                    INSERT INTO budget_categories (id,fiscal_year_id,parent_id,name,category_type,deleted_at)
                    VALUES (30,20,NULL,'親費目','EXPENSE',NULL),
                           (31,20,30,'子費目','EXPENSE','2026-02-03 04:05:06')
                    """);
            beforeYears = snapshot(sql, "budget_fiscal_years", "10,20");
            beforeCategories = snapshot(sql, "budget_categories", "30,31");
            assertThatThrownBy(() -> sql.executeUpdate(YEAR_INSERT)).isInstanceOf(SQLException.class).hasMessageContaining("Duplicate entry");
            assertThatThrownBy(() -> sql.executeUpdate(CHILD_INSERT)).isInstanceOf(SQLException.class).hasMessageContaining("Duplicate entry");
        }

        // migration未実装のredではコピー0件でも、下の実INSERT期待assertionで制約違反を検出する。
        // ファイル欠落・コンパイル失敗を受入条件redとして扱わない。
        try (var files = Files.list(Path.of("src/main/resources/db/migration"))) {
            for (Path migration : files.filter(p -> p.getFileName().toString().endsWith("__budget_soft_delete_active_unique.sql")).toList()) {
                Files.copy(migration, migrations.resolve(migration.getFileName()));
            }
        }
        assertThat(flyway().migrate().success).isTrue();
        try (Connection connection = connect(); Statement sql = connection.createStatement()) {
            org.assertj.core.api.SoftAssertions softly = new org.assertj.core.api.SoftAssertions();
            softly.assertThatCode(() -> sql.executeUpdate(YEAR_INSERT)).as("削除済み年度と同名同期間で新IDを作成する").doesNotThrowAnyException();
            softly.assertThatCode(() -> sql.executeUpdate(CHILD_INSERT)).as("同じ有効親の下に削除済み子と同名で新IDを作成する").doesNotThrowAnyException();
            softly.assertAll();
            assertThat(snapshot(sql, "budget_fiscal_years", "10,20")).isEqualTo(beforeYears);
            assertThat(snapshot(sql, "budget_categories", "30,31")).isEqualTo(beforeCategories);
            assertThatThrownBy(() -> sql.executeUpdate(YEAR_INSERT.replace("(11,", "(12,")))
                    .isInstanceOf(SQLException.class).hasMessageContaining("Duplicate entry");
            assertThatThrownBy(() -> sql.executeUpdate(CHILD_INSERT.replace("(32,", "(33,")))
                    .isInstanceOf(SQLException.class).hasMessageContaining("Duplicate entry");
            // 親NULLを含む旧UNIQUEのセマンティクスは強化しない。
            sql.executeUpdate("INSERT INTO budget_categories(id,fiscal_year_id,name,category_type) VALUES (34,20,'同名root','EXPENSE'),(35,20,'同名root','EXPENSE')");
        }
        assertThat(flyway().migrate().migrationsExecuted).isZero();
    }

    private void copyMigration(String name) throws Exception {
        try (var source = getClass().getResourceAsStream("/db/migration/" + name)) {
            if (source == null) throw new IllegalStateException("正本DDLがありません: " + name);
            Files.copy(source, migrations.resolve(name));
        }
    }
    private Flyway flyway() {
        return Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("filesystem:" + migrations).baselineOnMigrate(true).baselineVersion("0").load();
    }
    private Connection connect() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }
    /** テーブル名・ID集合は本クラスの固定値のみ。生成列以外の全業務列を比較する。 */
    private List<List<String>> snapshot(Statement sql, String table, String ids) throws SQLException {
        List<List<String>> result = new ArrayList<>();
        try (ResultSet rows = sql.executeQuery("SELECT * FROM " + table + " WHERE id IN (" + ids + ") ORDER BY id")) {
            while (rows.next()) {
                List<String> columns = new ArrayList<>();
                for (int i = 1; i <= rows.getMetaData().getColumnCount(); i++) {
                    if (!"active_unique_key".equals(rows.getMetaData().getColumnName(i))) columns.add(rows.getString(i));
                }
                result.add(columns);
            }
        }
        return result;
    }
}