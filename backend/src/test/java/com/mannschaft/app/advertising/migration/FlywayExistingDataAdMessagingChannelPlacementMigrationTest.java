package com.mannschaft.app.advertising.migration;

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
 * <b>F09.19.1 既存データ番人テスト（V144.003）</b>:
 * {@code ad_messaging_campaign_channels} に既存 BANNER チャネル行がある MySQL に対し、
 * placement 列追加マイグレーション（実ファイル
 * {@code V144.20260707124157__add_placement_to_ad_messaging_campaign_channels.sql}。
 * minor はタイムスタンプ命名規約 §18 = FlywayTimestampNamingGuardTest 準拠）を含む全マイグレーションが
 * クラッシュせず適用でき、<b>既存 BANNER 行が UPDATE で 'DASHBOARD_TILE' に backfill されてから
 * CHECK 制約（channel_type='BANNER' → placement 非 NULL）が追加される</b>ことを検証する
 * （正本 F09.19 §5.2 V144.003 / feedback_flyway_existing_data_check_drop:
 * CHECK 追加は必ず「既存データ是正 → 制約追加」の順）。
 *
 * <p>{@code FlywayFromScratchMigrationTest}（空 DB）では BANNER 行が 0 件のため
 * 「既存行が CHECK 違反でマイグレーション失敗する」退行を見逃す。本テストは
 * <b>V143.001（本書執筆時点の origin/main 最大）まで適用 → placement 列がまだ無い状態で
 * BANNER チャネル行をシード → 残りのマイグレーション（V144.20260707124157 含む）を適用</b>
 * という既存データ経路を再現する。</p>
 *
 * <p>方針は {@code FlywayExistingDataActivityStatusMigrationTest} を踏襲し、Spring コンテキストを
 * 起動せず Testcontainers の実 MySQL 8.0 に {@link Flyway} を Java API で直接実行する。
 * <b>試練段階では V144.003 が未作成のため placement 列が存在せず red になる</b>（実装が無いための失敗）。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.advertising.migration.FlywayExistingDataAdMessagingChannelPlacementMigrationTest#isDockerAvailable")
@DisplayName("Flyway 既存データ ad_messaging_campaign_channels placement 移行（V144.003）番人テスト")
class FlywayExistingDataAdMessagingChannelPlacementMigrationTest {

    /**
     * placement 列追加マイグレーションの直前バージョン（本書執筆時点の origin/main 最大 = V143.001）。
     * ここまで適用してから placement 列の無い既存 BANNER 行をシードする。
     * V144.x が採番替えになった場合もこの pre-target は「新列追加より前」でありさえすれば有効。
     */
    private static final String PRE_PLACEMENT_TARGET = "143.001";

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_amcc_placement")
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
    @DisplayName("ac_ddl: 既存 BANNER チャネル行が placement 移行後に DASHBOARD_TILE で backfill され CHECK 制約が付く")
    void 既存BANNER行がplacement移行後にbackfillされCHECK制約が付く() throws Exception {
        // given: V143.001（placement 列追加前）まで適用
        Flyway pre = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .target(MigrationVersion.fromVersion(PRE_PLACEMENT_TARGET))
                .load();
        MigrateResult preResult = pre.migrate();
        assertThat(preResult.success).as("V" + PRE_PLACEMENT_TARGET + " までの適用が成功すること").isTrue();

        try (Connection conn = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             Statement st = conn.createStatement()) {

            // sanity: この時点では placement 列が存在しない（旧スキーマの証明）
            assertThat(columnExists(conn, "ad_messaging_campaign_channels", "placement"))
                    .as("V" + PRE_PLACEMENT_TARGET + " 時点では placement 列が存在しないこと").isFalse();

            // 既存 BANNER チャネル行（+ 比較用 EMAIL 行）をシード。
            // 親テーブル（ad_messaging_campaigns → advertiser_accounts 等）の NOT NULL 列を全て組むのは
            // brittle なため、FK チェックをセッション限定で無効化して対象テーブルのみ最小 INSERT する
            // （SharedFileLinkFlywayColumnIT / ProxyInputConsentS3KeyFlywaySchemaTest と同じアプローチ）。
            st.execute("SET FOREIGN_KEY_CHECKS = 0");
            st.executeUpdate(
                    "INSERT INTO ad_messaging_campaign_channels "
                            + "(id, campaign_id, channel_type, locale, body_markdown, banner_creative_id, "
                            + " created_at, updated_at) "
                            + "VALUES (UNHEX(REPLACE(UUID(),'-','')), UNHEX(REPLACE(UUID(),'-','')), "
                            + "'BANNER', 'ja', 'banner body', 1, NOW(), NOW())");
            st.executeUpdate(
                    "INSERT INTO ad_messaging_campaign_channels "
                            + "(id, campaign_id, channel_type, locale, body_markdown, "
                            + " created_at, updated_at) "
                            + "VALUES (UNHEX(REPLACE(UUID(),'-','')), UNHEX(REPLACE(UUID(),'-','')), "
                            + "'EMAIL', 'ja', 'email body', NOW(), NOW())");
            st.execute("SET FOREIGN_KEY_CHECKS = 1");
        }

        // when: 残りのマイグレーション（placement 列追加 = V144.20260707124157 を含む）を適用
        Flyway rest = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .load();
        MigrateResult restResult = rest.migrate();

        // then: 既存 BANNER 行があっても「UPDATE → CHECK 追加」の順で成功する
        assertThat(restResult.success)
                .as("既存 BANNER 行が存在しても placement 移行を含む残りの適用が成功すること").isTrue();

        try (Connection conn = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {

            assertThat(columnExists(conn, "ad_messaging_campaign_channels", "placement"))
                    .as("placement 列が追加されていること（V144.20260707124157）").isTrue();

            // 既存 BANNER 行が DASHBOARD_TILE で backfill されている
            assertThat(scalarString(conn,
                    "SELECT placement FROM ad_messaging_campaign_channels WHERE channel_type = 'BANNER'"))
                    .as("既存 BANNER 行の placement が 'DASHBOARD_TILE' に backfill されること")
                    .isEqualTo("DASHBOARD_TILE");

            // 非 BANNER 行は placement NULL のまま（CHECK は BANNER のみ非 NULL を強制）
            assertThat(scalarString(conn,
                    "SELECT placement FROM ad_messaging_campaign_channels WHERE channel_type = 'EMAIL'"))
                    .as("非 BANNER 行の placement は NULL のままであること")
                    .isNull();

            // CHECK 制約 chk_amcc_banner_placement が存在する
            assertThat(checkConstraintExists(conn, "chk_amcc_banner_placement"))
                    .as("CHECK 制約 chk_amcc_banner_placement が追加されていること").isTrue();
        }
    }

    private static boolean columnExists(Connection conn, String table, String column) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM information_schema.COLUMNS "
                             + "WHERE TABLE_SCHEMA = DATABASE() "
                             + "AND TABLE_NAME = '" + table + "' "
                             + "AND COLUMN_NAME = '" + column + "'")) {
            rs.next();
            return rs.getLong(1) > 0;
        }
    }

    private static boolean checkConstraintExists(Connection conn, String constraintName) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS "
                             + "WHERE CONSTRAINT_SCHEMA = DATABASE() "
                             + "AND CONSTRAINT_TYPE = 'CHECK' "
                             + "AND CONSTRAINT_NAME = '" + constraintName + "'")) {
            rs.next();
            return rs.getLong(1) > 0;
        }
    }

    /** 単一行・単一列の文字列値（行が無ければテスト失敗、値が NULL なら null）。 */
    private static String scalarString(Connection conn, String sql) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            assertThat(rs.next()).as("クエリが 1 行返すこと: " + sql).isTrue();
            return rs.getString(1);
        }
    }
}
