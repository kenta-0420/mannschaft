package com.mannschaft.app.payment.migration;

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

/**
 * <b>Issue #2657 番人テスト（V177.20260807132224）</b>: {@code payment_items.type} ENUM に
 * {@code TERM} を追加するマイグレーションが、既存の ANNUAL_FEE/MONTHLY_FEE/ITEM 行を持つ MySQL に対して
 * クラッシュせず適用でき、かつ<b>既存行の値・件数を一切変更せず</b>、適用後は TERM 型の新規行を
 * 挿入できるようになることを検証する。
 *
 * <p>事象の根治対象: {@code V80.20260610194300__alter_payment_items_add_term_columns.sql} が
 * {@code term_starts_on}/{@code term_ends_on} 列は追加したが ENUM 値の追加を怠っていたため、
 * {@link com.mannschaft.app.payment.PaymentItemType#TERM}（Java 側は5値）で項目を作成しようとすると
 * 必ず {@code Data truncated for column 'type'} で失敗していた（DB 側 ENUM は4値のまま）。</p>
 *
 * <p>方針は {@code FlywayExistingDataAdMessagingChannelPlacementMigrationTest} を踏襲し、
 * Spring コンテキストを起動せず Testcontainers の実 MySQL 8.0 に {@link Flyway} を Java API で
 * 直接実行する。<b>V177 未適用の時点で ANNUAL_FEE/MONTHLY_FEE/ITEM の既存行をシード</b>してから
 * 残りのマイグレーションを適用し、既存データの分布が保たれたまま TERM が使えるようになることを示す。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.payment.migration.FlywayExistingDataPaymentItemsTermEnumMigrationTest#isDockerAvailable")
@DisplayName("Flyway 既存データ payment_items.type TERM 追加移行（V177.20260807132224）番人テスト")
class FlywayExistingDataPaymentItemsTermEnumMigrationTest {

    /**
     * TERM 追加マイグレーションの直前バージョン（origin/main 最大 = V176.20260805232840）。
     * ここまで適用してから TERM 列挙値の無い既存行（ANNUAL_FEE/MONTHLY_FEE/ITEM）をシードする。
     */
    private static final String PRE_TERM_TARGET = "176.20260805232840";

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_payment_items_term")
            .withUsername("test")
            .withPassword("test")
            .withTmpFs(java.util.Map.of("/var/lib/mysql", "rw"))
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
    @DisplayName("ac_ddl: 既存 ANNUAL_FEE/MONTHLY_FEE/ITEM 行の分布を保ったまま TERM 型が挿入可能になる")
    void 既存行の分布を保ったままTERM型が挿入可能になる() throws Exception {
        // given: V176.20260805232840（TERM 追加前）まで適用
        Flyway pre = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .target(MigrationVersion.fromVersion(PRE_TERM_TARGET))
                .load();
        MigrateResult preResult = pre.migrate();
        assertThat(preResult.success).as("V" + PRE_TERM_TARGET + " までの適用が成功すること").isTrue();

        try (Connection conn = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             Statement st = conn.createStatement()) {

            // sanity: この時点では ENUM に TERM が無い（旧スキーマの証明）
            assertThat(enumContainsTerm(conn)).as("移行前は ENUM に TERM が含まれないこと").isFalse();

            // 既存 ANNUAL_FEE 2件・MONTHLY_FEE 1件・ITEM 1件をシード（team_id/organization_id は
            // クロスドメイン FK 禁止のため実在チェックなし・chk_pi_scope を満たす値のみ設定）。
            st.executeUpdate(
                    "INSERT INTO payment_items (team_id, organization_id, name, type, amount, currency) "
                            + "VALUES (1, NULL, '年会費A', 'ANNUAL_FEE', 5000.00, 'JPY')");
            st.executeUpdate(
                    "INSERT INTO payment_items (team_id, organization_id, name, type, amount, currency) "
                            + "VALUES (2, NULL, '年会費B', 'ANNUAL_FEE', 6000.00, 'JPY')");
            st.executeUpdate(
                    "INSERT INTO payment_items (team_id, organization_id, name, type, amount, currency) "
                            + "VALUES (3, NULL, '月会費', 'MONTHLY_FEE', 1000.00, 'JPY')");
            st.executeUpdate(
                    "INSERT INTO payment_items (team_id, organization_id, name, type, amount, currency) "
                            + "VALUES (NULL, 1, '物品', 'ITEM', 2000.00, 'JPY')");
        }

        long countBefore = countRows();

        // when: 残りのマイグレーション（TERM 追加 = V177.20260807132224 を含む）を適用
        Flyway rest = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .load();
        MigrateResult restResult = rest.migrate();

        // then: 既存4行があっても TERM 追加を含む残りの適用が成功する
        assertThat(restResult.success)
                .as("既存 payment_items 行が存在しても TERM 追加移行を含む残りの適用が成功すること").isTrue();

        try (Connection conn = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {

            // ENUM に TERM が追加されていること
            assertThat(enumContainsTerm(conn)).as("TERM 追加移行後は ENUM に TERM が含まれること").isTrue();

            // 既存4行の件数・分布が一切変わっていないこと
            assertThat(countRows()).as("既存行数が変化しないこと").isEqualTo(countBefore).isEqualTo(4L);
            assertThat(countByType(conn, "ANNUAL_FEE")).isEqualTo(2L);
            assertThat(countByType(conn, "MONTHLY_FEE")).isEqualTo(1L);
            assertThat(countByType(conn, "ITEM")).isEqualTo(1L);

            // TERM 型の新規行が挿入できる（本Issueの核心：以前は Data truncated で必ず失敗していた）
            try (Statement st = conn.createStatement()) {
                st.executeUpdate(
                        "INSERT INTO payment_items "
                                + "(team_id, organization_id, name, type, amount, currency, term_starts_on, term_ends_on) "
                                + "VALUES (1, NULL, '期別会費', 'TERM', 3000.00, 'JPY', '2026-04-01', '2026-09-30')");
            }
            assertThat(countByType(conn, "TERM")).as("TERM 型の新規行が永続化できること").isEqualTo(1L);
        }
    }

    private static long countRows() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM payment_items")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static long countByType(Connection conn, String type) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM payment_items WHERE type = '" + type + "'")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** ENUM 定義文字列に 'TERM' が含まれるかを information_schema から確認する。 */
    private static boolean enumContainsTerm(Connection conn) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COLUMN_TYPE FROM information_schema.COLUMNS "
                             + "WHERE TABLE_SCHEMA = DATABASE() "
                             + "AND TABLE_NAME = 'payment_items' AND COLUMN_NAME = 'type'")) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1).contains("'TERM'");
        }
    }
}
