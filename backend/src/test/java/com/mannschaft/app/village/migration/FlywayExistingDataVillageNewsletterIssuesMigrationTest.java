package com.mannschaft.app.village.migration;

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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>F17.1 ②-1（DB）既存データ番人テスト</b>:
 * 設計書 {@code docs/features/F17.1_village_newsletter_content_model.md}
 * §4.2 / §4.3 / §4.5 / §4.7 / §12 ②-1 の移行を、<b>実 MySQL</b>（Testcontainers）で検証する。
 *
 * <h2>なぜモック不可なのか</h2>
 * <p>検証対象は<b>移行 SQL そのもの</b>（新テーブルの DDL・集計日/配信日の Expand＋全行バックフィル＋
 * NOT NULL 化・FK CASCADE・send_logs の issue_id 追加）で、いずれも実 RDBMS のセマンティクスでしか
 * 再現しない。memory {@code feedback_adapter_mock_ut_false_green_downstream_enum} のとおり、
 * モック UT は移行 SQL の欠陥を偽 green で通す。</p>
 *
 * <h2>本テストが守る「時限爆弾」（設計書 §4 / 先例 V153 §5.4）</h2>
 * <p>{@code village_newsletters} へ集計日/配信日を追加し NOT NULL 化する。既存行のバックフィルを
 * {@code deleted_at IS NULL} で絞ると、<b>論理削除済みの設定行</b>の {@code aggregate_day}/{@code dispatch_day}
 * が NULL のまま残り、直後の {@code MODIFY ... NOT NULL} が確実に失敗する。DB は {@code deleted_at} を
 * 知らないため NOT NULL 制約は論理削除済みの行にも適用されるためである。
 * {@link #論理削除済みの設定行も集計日配信日がバックフィルされている()} がこの罠を機械的に検出する。</p>
 *
 * <p>方針は {@code FlywayExistingDataVillageRecruitCategoriesMigrationTest}（V153）を踏襲し、Spring
 * コンテキストを起動せず Testcontainers の実 MySQL 8.0 に {@link Flyway} を Java API で直接実行する。
 * Docker 未起動環境では {@code @EnabledIf} によりスキップされる。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.village.migration."
        + "FlywayExistingDataVillageNewsletterIssuesMigrationTest#isDockerAvailable")
@DisplayName("Flyway 既存データ 村ニュースレター号モデル移行（V154）番人テスト")
class FlywayExistingDataVillageNewsletterIssuesMigrationTest {

    /**
     * V154（本移行）の直前バージョン。ここまで適用してから、旧スキーマ（集計日/配信日カラムが無い）の
     * village_newsletters 行をシードする。
     */
    private static final String PRE_V154_TARGET = "153.20260715213228";

    /** 生きた村。WEEKLY / MONTHLY の設定を各1件持つ。 */
    private static final UUID VILLAGE_LIVE = UUID.randomUUID();
    /** 生きた村だが、設定行そのものが論理削除済み（MONTHLY）。時限爆弾の主対象。 */
    private static final UUID VILLAGE_SOFTDEL_SETTING = UUID.randomUUID();
    /** 論理削除済みの村。WEEKLY 設定を持つ（CASCADE は物理削除でしか発火しないため行は生きている）。 */
    private static final UUID VILLAGE_DELETED = UUID.randomUUID();
    /** CASCADE 検証専用の村（設定は持たない）。 */
    private static final UUID VILLAGE_CASCADE = UUID.randomUUID();

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_village_newsletter_issue")
            .withUsername("test")
            .withPassword("test")
            // memory project_testcontainers_mysql_tmpfs_fix: tmpfs 無しは JDBC タイムアウトで落ちる
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
    void startContainerAndMigrate() throws Exception {
        MYSQL.start();

        // given: V154 の直前まで適用 ＝ 号テーブル群が無く、village_newsletters に集計日/配信日列も無い状態
        Flyway pre = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .target(MigrationVersion.fromVersion(PRE_V154_TARGET))
                .load();
        MigrateResult preResult = pre.migrate();
        assertThat(preResult.success).as("V154 直前までの適用が成功すること").isTrue();

        try (Connection conn = openConn()) {
            // sanity: 旧スキーマの証明
            assertThat(tableExists(conn, "village_newsletter_issues"))
                    .as("V154 適用前は village_newsletter_issues が存在しないこと").isFalse();
            assertThat(columnExists(conn, "village_newsletters", "aggregate_day"))
                    .as("V154 適用前は aggregate_day 列が存在しないこと").isFalse();
            assertThat(columnExists(conn, "village_newsletter_send_logs", "issue_id"))
                    .as("V154 適用前は send_logs.issue_id が存在しないこと").isFalse();

            insertVillage(conn, VILLAGE_LIVE, "vn-live", "VN 生存村", false);
            insertVillage(conn, VILLAGE_SOFTDEL_SETTING, "vn-softdel", "VN 設定論理削除村", false);
            insertVillage(conn, VILLAGE_DELETED, "vn-del", "VN 論理削除村", true);
            insertVillage(conn, VILLAGE_CASCADE, "vn-cascade", "VN CASCADE 検証村", false);

            // 旧スキーマ（集計日/配信日なし）の設定行をシード
            insertNewsletter(conn, VILLAGE_LIVE, "WEEKLY", false);
            insertNewsletter(conn, VILLAGE_LIVE, "MONTHLY", false);
            // 【時限爆弾の主対象】設定行そのものが論理削除済み。バックフィルが deleted_at で
            // 絞られていれば aggregate_day/dispatch_day が NULL のまま残り NOT NULL 化が失敗する。
            insertNewsletter(conn, VILLAGE_SOFTDEL_SETTING, "MONTHLY", true);
            // 論理削除済みの村が持つ設定行（村の CASCADE は物理削除でしか発火しないため生きている）
            insertNewsletter(conn, VILLAGE_DELETED, "WEEKLY", false);
        }

        // when: 残りのマイグレーション（V154 含む）を適用する
        Flyway rest = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .load();
        MigrateResult restResult = rest.migrate();
        assertThat(restResult.success)
                .as("V154 を含む残りのマイグレーションが成功すること").isTrue();
    }

    @AfterAll
    void stopContainer() {
        MYSQL.stop();
    }

    // ==================================================================
    // 集計日/配信日の ALTER ADD ＋ 全行バックフィル（時限爆弾）
    // ==================================================================

    @Test
    @DisplayName("集計日/配信日カラムが追加され NOT NULL 化されている")
    void 集計日配信日カラムが追加されNOTNULL化されている() throws Exception {
        try (Connection conn = openConn()) {
            assertThat(columnExists(conn, "village_newsletters", "aggregate_day")).isTrue();
            assertThat(columnExists(conn, "village_newsletters", "dispatch_day")).isTrue();
            assertThat(columnExists(conn, "village_newsletters", "dispatch_hour")).isTrue();
            assertThat(isNullable(conn, "village_newsletters", "aggregate_day"))
                    .as("aggregate_day は NOT NULL 化されていること").isFalse();
            assertThat(isNullable(conn, "village_newsletters", "dispatch_day"))
                    .as("dispatch_day は NOT NULL 化されていること").isFalse();
            assertThat(isNullable(conn, "village_newsletters", "dispatch_hour"))
                    .as("dispatch_hour は NOT NULL であること").isFalse();
        }
    }

    @Test
    @DisplayName("【時限爆弾の番人】全設定行（論理削除済みを含む）の集計日/配信日がNULLでない")
    void 論理削除済みの設定行も集計日配信日がバックフィルされている() throws Exception {
        try (Connection conn = openConn()) {
            assertThat(countScalar(conn,
                    "SELECT COUNT(*) FROM village_newsletters "
                            + "WHERE aggregate_day IS NULL OR dispatch_day IS NULL"))
                    .as("1件でも NULL が残ると MODIFY NOT NULL が失敗する。deleted_at で絞らず全行を検査")
                    .isZero();
            // シードした4行すべてが健在（既存データ非破壊）
            assertThat(countScalar(conn, "SELECT COUNT(*) FROM village_newsletters"))
                    .as("シードした設定行が消えていないこと").isEqualTo(4);
        }
    }

    @Test
    @DisplayName("バックフィル値が既存挙動を保存している（WEEKLY=月1/金5、MONTHLY=月末0/月末0、hour=18）")
    void バックフィル値が既存挙動を保存している() throws Exception {
        try (Connection conn = openConn()) {
            // WEEKLY: 集計=月曜(1)/配信=金曜(5)
            assertThat(dayOf(conn, VILLAGE_LIVE, "WEEKLY", "aggregate_day")).isEqualTo(1);
            assertThat(dayOf(conn, VILLAGE_LIVE, "WEEKLY", "dispatch_day")).isEqualTo(5);
            assertThat(dayOf(conn, VILLAGE_LIVE, "WEEKLY", "dispatch_hour")).isEqualTo(18);

            // MONTHLY: 集計=月末(0)/配信=月末(0)
            assertThat(dayOf(conn, VILLAGE_LIVE, "MONTHLY", "aggregate_day")).isEqualTo(0);
            assertThat(dayOf(conn, VILLAGE_LIVE, "MONTHLY", "dispatch_day")).isEqualTo(0);

            // 論理削除済みの MONTHLY 設定も同じく (0,0,18) が入る（時限爆弾が塞がれている証拠）
            assertThat(dayOf(conn, VILLAGE_SOFTDEL_SETTING, "MONTHLY", "aggregate_day")).isEqualTo(0);
            assertThat(dayOf(conn, VILLAGE_SOFTDEL_SETTING, "MONTHLY", "dispatch_day")).isEqualTo(0);
            assertThat(dayOf(conn, VILLAGE_SOFTDEL_SETTING, "MONTHLY", "dispatch_hour")).isEqualTo(18);

            // 論理削除済みの村が持つ WEEKLY 設定も (1,5)
            assertThat(dayOf(conn, VILLAGE_DELETED, "WEEKLY", "aggregate_day")).isEqualTo(1);
            assertThat(dayOf(conn, VILLAGE_DELETED, "WEEKLY", "dispatch_day")).isEqualTo(5);
        }
    }

    @Test
    @DisplayName("値域 CHECK が効いている（dispatch_hour=24 は拒否される）")
    void 値域CHECKが効いている() throws Exception {
        try (Connection conn = openConn();
             Statement st = conn.createStatement()) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    st.executeUpdate("UPDATE village_newsletters SET dispatch_hour = 24 "
                            + "WHERE village_id = " + bin(VILLAGE_LIVE) + " AND frequency = 'WEEKLY'"))
                    .as("chk_vn_dispatch_hour が 0-23 の範囲外を拒否すること")
                    .isInstanceOf(java.sql.SQLException.class);
        }
    }

    // ==================================================================
    // 号テーブル・タグ・中間表の DDL とスキーマ
    // ==================================================================

    @Test
    @DisplayName("号テーブル群が UUIDv7(BINARY16) PK・論理削除カラム付きで作成されている")
    void 号テーブル群が作成されている() throws Exception {
        try (Connection conn = openConn()) {
            assertThat(tableExists(conn, "village_newsletter_issues")).isTrue();
            assertThat(tableExists(conn, "village_newsletter_tags")).isTrue();
            assertThat(tableExists(conn, "village_newsletter_issue_tags")).isTrue();

            // 原則6: PK は BINARY(16)
            assertThat(columnType(conn, "village_newsletter_issues", "id")).isEqualTo("binary");
            assertThat(columnType(conn, "village_newsletter_tags", "id")).isEqualTo("binary");

            // 原則3: 論理削除カラム
            assertThat(columnExists(conn, "village_newsletter_issues", "deleted_at")).isTrue();
            assertThat(columnExists(conn, "village_newsletter_tags", "deleted_at")).isTrue();

            // 改ざん不可 snapshot 列（設計書 §4.2）
            assertThat(columnExists(conn, "village_newsletter_issues", "digest_post_count")).isTrue();
            assertThat(columnExists(conn, "village_newsletter_issues", "digest_topic_3_count")).isTrue();
            // コメント欄はダイジェストと別カラム
            assertThat(columnExists(conn, "village_newsletter_issues", "headman_comment")).isTrue();
        }
    }

    @Test
    @DisplayName("send_logs に issue_id が NULL 許容で追加されている（後方互換）")
    void sendLogsにissueIdが追加されている() throws Exception {
        try (Connection conn = openConn()) {
            assertThat(columnExists(conn, "village_newsletter_send_logs", "issue_id")).isTrue();
            assertThat(isNullable(conn, "village_newsletter_send_logs", "issue_id"))
                    .as("号モデル導入前の既存ログは NULL のため NULL 許容").isTrue();
            assertThat(columnType(conn, "village_newsletter_send_logs", "issue_id")).isEqualTo("binary");
        }
    }

    // ==================================================================
    // FK CASCADE（同一ドメイン・原則2）
    // ==================================================================

    @Test
    @DisplayName("村を物理削除すると号もタグも CASCADE で消える（同一ドメイン FK）")
    void 村の物理削除で号とタグがCASCADE削除される() throws Exception {
        try (Connection conn = openConn();
             Statement st = conn.createStatement()) {
            UUID issueId = UUID.randomUUID();
            UUID tagId = UUID.randomUUID();
            insertIssue(conn, issueId, VILLAGE_CASCADE);
            insertTag(conn, tagId, VILLAGE_CASCADE);
            insertIssueTag(conn, issueId, tagId);

            assertThat(countScalar(conn, "SELECT COUNT(*) FROM village_newsletter_issues "
                    + "WHERE village_id = " + bin(VILLAGE_CASCADE))).isEqualTo(1);

            st.executeUpdate("DELETE FROM villages WHERE id = " + bin(VILLAGE_CASCADE));

            assertThat(countScalar(conn, "SELECT COUNT(*) FROM village_newsletter_issues "
                    + "WHERE village_id = " + bin(VILLAGE_CASCADE)))
                    .as("fk_vni_village の ON DELETE CASCADE で号が消えること").isZero();
            assertThat(countScalar(conn, "SELECT COUNT(*) FROM village_newsletter_tags "
                    + "WHERE village_id = " + bin(VILLAGE_CASCADE)))
                    .as("fk_vnt_village の ON DELETE CASCADE でタグが消えること").isZero();
            // 中間表も号/タグの CASCADE で消える
            assertThat(countScalar(conn, "SELECT COUNT(*) FROM village_newsletter_issue_tags "
                    + "WHERE issue_id = " + bin(issueId)))
                    .as("号の CASCADE で中間表の紐付けも消えること").isZero();
        }
    }

    @Test
    @DisplayName("号を削除すると中間表の紐付けが CASCADE で消える（タグマスタは残る）")
    void 号の削除で中間表がCASCADE削除される() throws Exception {
        try (Connection conn = openConn();
             Statement st = conn.createStatement()) {
            UUID issueId = UUID.randomUUID();
            UUID tagId = UUID.randomUUID();
            insertIssue(conn, issueId, VILLAGE_LIVE);
            insertTag(conn, tagId, VILLAGE_LIVE);
            insertIssueTag(conn, issueId, tagId);

            st.executeUpdate("DELETE FROM village_newsletter_issues WHERE id = " + bin(issueId));

            assertThat(countScalar(conn, "SELECT COUNT(*) FROM village_newsletter_issue_tags "
                    + "WHERE issue_id = " + bin(issueId)))
                    .as("fk_vnit_issue の CASCADE で紐付けが消えること").isZero();
            assertThat(countScalar(conn, "SELECT COUNT(*) FROM village_newsletter_tags "
                    + "WHERE id = " + bin(tagId)))
                    .as("タグマスタ自体は消えないこと").isEqualTo(1);
        }
    }

    @Test
    @DisplayName("同一村×頻度×期間の号は UNIQUE で1件に制限される（集計バッチ冪等）")
    void 同一村頻度期間の号はUNIQUEで弾かれる() throws Exception {
        try (Connection conn = openConn()) {
            UUID first = UUID.randomUUID();
            insertIssue(conn, first, VILLAGE_LIVE, "WEEKLY", "2026-06-01 00:00:00.000000");

            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    insertIssue(conn, UUID.randomUUID(), VILLAGE_LIVE, "WEEKLY", "2026-06-01 00:00:00.000000"))
                    .as("uk_vni_village_period が同一村×頻度×期間の二重号を拒否すること")
                    .isInstanceOf(java.sql.SQLException.class);
        }
    }

    // ==================================================================
    // ヘルパー
    // ==================================================================

    private static Connection openConn() throws Exception {
        return DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    private static String bin(UUID id) {
        return "UNHEX('" + id.toString().replace("-", "") + "')";
    }

    private static void insertVillage(Connection conn, UUID id, String slug, String name, boolean softDeleted)
            throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO villages (id, slug, name, type, join_policy, visibility, "
                        + "created_at, updated_at, deleted_at) "
                        + "VALUES (UNHEX(?), ?, ?, 'COMMUNITY', 'FREE', 'PUBLIC', NOW(6), NOW(6), "
                        + (softDeleted ? "NOW(6)" : "NULL") + ")")) {
            ps.setString(1, id.toString().replace("-", ""));
            ps.setString(2, slug);
            ps.setString(3, name);
            ps.executeUpdate();
        }
    }

    /** 旧スキーマ（集計日/配信日カラムなし）の village_newsletters へ 1 行 INSERT する。 */
    private static void insertNewsletter(Connection conn, UUID villageId, String frequency, boolean softDeleted)
            throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO village_newsletters (id, village_id, frequency, is_enabled, "
                        + "created_at, updated_at, deleted_at, version) "
                        + "VALUES (UNHEX(?), UNHEX(?), ?, TRUE, NOW(6), NOW(6), "
                        + (softDeleted ? "NOW(6)" : "NULL") + ", 0)")) {
            ps.setString(1, UUID.randomUUID().toString().replace("-", ""));
            ps.setString(2, villageId.toString().replace("-", ""));
            ps.setString(3, frequency);
            ps.executeUpdate();
        }
    }

    private static void insertIssue(Connection conn, UUID id, UUID villageId) throws Exception {
        insertIssue(conn, id, villageId, "WEEKLY", "2026-05-01 00:00:00.000000");
    }

    /** 移行後スキーマの村ニュースレター号を 1 行 INSERT する。 */
    private static void insertIssue(Connection conn, UUID id, UUID villageId, String frequency, String periodStart)
            throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO village_newsletter_issues "
                        + "(id, village_id, frequency, issue_type, status, title, visibility, "
                        + " period_start, period_end, created_at, updated_at, version) "
                        + "VALUES (UNHEX(?), UNHEX(?), ?, 'REGULAR', 'FROZEN', ?, 'VILLAGE_MEMBERS', "
                        + " ?, ?, NOW(6), NOW(6), 0)")) {
            ps.setString(1, id.toString().replace("-", ""));
            ps.setString(2, villageId.toString().replace("-", ""));
            ps.setString(3, frequency);
            ps.setString(4, "テスト号");
            ps.setString(5, periodStart);
            ps.setString(6, periodStart);
            ps.executeUpdate();
        }
    }

    private static void insertTag(Connection conn, UUID id, UUID villageId) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO village_newsletter_tags (id, village_id, name, color, sort_order, "
                        + "created_at, updated_at, version) "
                        + "VALUES (UNHEX(?), UNHEX(?), ?, '#6B7280', 0, NOW(6), NOW(6), 0)")) {
            ps.setString(1, id.toString().replace("-", ""));
            ps.setString(2, villageId.toString().replace("-", ""));
            ps.setString(3, "お知らせ");
            ps.executeUpdate();
        }
    }

    private static void insertIssueTag(Connection conn, UUID issueId, UUID tagId) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO village_newsletter_issue_tags (id, issue_id, tag_id, created_at) "
                        + "VALUES (UNHEX(?), UNHEX(?), UNHEX(?), NOW(6))")) {
            ps.setString(1, UUID.randomUUID().toString().replace("-", ""));
            ps.setString(2, issueId.toString().replace("-", ""));
            ps.setString(3, tagId.toString().replace("-", ""));
            ps.executeUpdate();
        }
    }

    private static int dayOf(Connection conn, UUID villageId, String frequency, String column) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT " + column + " FROM village_newsletters "
                             + "WHERE village_id = " + bin(villageId)
                             + " AND frequency = '" + frequency + "'")) {
            assertThat(rs.next()).as(frequency + " の設定行が存在すること").isTrue();
            return rs.getInt(1);
        }
    }

    private static long countScalar(Connection conn, String sql) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static boolean tableExists(Connection conn, String table) throws Exception {
        return countScalar(conn,
                "SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '" + table + "'") > 0;
    }

    private static boolean columnExists(Connection conn, String table, String column) throws Exception {
        return countScalar(conn,
                "SELECT COUNT(*) FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '" + table + "' "
                        + "AND COLUMN_NAME = '" + column + "'") > 0;
    }

    private static boolean isNullable(Connection conn, String table, String column) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT IS_NULLABLE FROM information_schema.COLUMNS "
                             + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '" + table + "' "
                             + "AND COLUMN_NAME = '" + column + "'")) {
            assertThat(rs.next()).as(table + "." + column + " が存在すること").isTrue();
            return "YES".equalsIgnoreCase(rs.getString(1));
        }
    }

    /** information_schema.COLUMNS.DATA_TYPE（例: binary / varchar / int）。 */
    private static String columnType(Connection conn, String table, String column) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT DATA_TYPE FROM information_schema.COLUMNS "
                             + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '" + table + "' "
                             + "AND COLUMN_NAME = '" + column + "'")) {
            assertThat(rs.next()).as(table + "." + column + " が存在すること").isTrue();
            return rs.getString(1);
        }
    }
}
