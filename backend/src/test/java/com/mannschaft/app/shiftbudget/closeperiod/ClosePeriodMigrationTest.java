package com.mannschaft.app.shiftbudget.closeperiod;

import com.mannschaft.app.config.FlywayRepairConfig;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CMP-260909-2152 PR-α の migration（α の major 系。採番は実装時の origin/main 最大 major+1）と事前検査を、既存行のある実 DB（Flyway・MySQL 8.0）で検査する。
 *
 * <p>試練118（AC41）・127（AC50 索引）・129b（AC52 旧締め取り込み・V209・異常旧仕訳）・134a（AC59α β 表なし）・
 * 135（AC60）・146（AC75）。</p>
 *
 * <p>統合テストのスキーマは Hibernate 生成で Flyway を通らないため、本試験は Spring を起動せず専用コンテナに
 * V260 まで適用 → 既存行を投入 → 本番と同じ {@link FlywayRepairConfig#flywayMigrationStrategy()}（事前検査 → repair → migrate）で
 * α を適用する（金型: FlywayExistingDataRestrictOtherTableCrossDomainFkMigrationTest）。</p>
 *
 * <p>実装前の red 理由: α の migration が無く work_date・新表が存在しない（SQL エラー／「α の migration が無い」）、
 * 事前検査が無く異常データでも起動が止まらない。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIf("com.mannschaft.app.shiftbudget.closeperiod.ClosePeriodMigrationTest#isDockerAvailable")
@DisplayName("CMP-260909-2152 α migration と事前検査（Flyway 実DB）")
class ClosePeriodMigrationTest {

    /**
     * α の migration 群の識別子。α の major は実装時の origin/main 最大 major+1 で採番するため固定しない
     * （並行 PR の着地順で V261／V262／V263 … と変わる）。<b>締め期間表を作る migration の説明に
     * {@code shift_budget_close_periods} を含めること</b>を契約とし、その major を α の major とみなす
     * （α の全ファイルは同じ major。β は別 major）。
     */
    private static final String ALPHA_MARKER = "shift_budget_close_periods";

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("cmp2152_alpha")
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
    void start() {
        MYSQL.start();
    }

    @AfterAll
    void stop() {
        MYSQL.stop();
    }

    // ───────────────────────── DB・Flyway ─────────────────────────

    private String url(String db) {
        return "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306) + "/" + db
                + "?allowPublicKeyRetrieval=true&useSSL=false&connectionTimeZone=UTC";
    }

    private Connection conn(String db) throws SQLException {
        return DriverManager.getConnection(url(db), "root", MYSQL.getPassword());
    }

    private void createDb(String db) throws SQLException {
        try (Connection c = DriverManager.getConnection(url(""), "root", MYSQL.getPassword());
             Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE " + db + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
    }

    private Flyway flyway(String db, String target) {
        var cfg = Flyway.configure().dataSource(url(db), "root", MYSQL.getPassword())
                .locations("classpath:db/migration");
        if (target != null) {
            cfg = cfg.target(MigrationVersion.fromVersion(target));
        }
        return cfg.load();
    }

    private static String major(MigrationInfo m) {
        String v = m.getVersion().getVersion();
        int dot = v.indexOf('.');
        return dot < 0 ? v : v.substring(0, dot);
    }

    /** α の major（締め期間表を作る migration の major）。まだ無ければ空。 */
    private java.util.Optional<String> alphaMajor(String db) {
        return Arrays.stream(flyway(db, null).info().all())
                .filter(m -> m.getVersion() != null
                        && m.getDescription().replace(' ', '_').toLowerCase().contains(ALPHA_MARKER))
                .map(ClosePeriodMigrationTest::major)
                .findFirst();
    }

    /** α の直前（α の major より小さい版）の最大版。α がまだ無ければ全体の最大版。 */
    private String preAlphaTarget(String db) {
        java.util.Optional<String> alpha = alphaMajor(db);
        return Arrays.stream(flyway(db, null).info().all())
                .filter(m -> m.getVersion() != null)
                .filter(m -> alpha.isEmpty() || Long.parseLong(major(m)) < Long.parseLong(alpha.get()))
                .map(MigrationInfo::getVersion)
                .max(MigrationVersion::compareTo)
                .orElseThrow(() -> new IllegalStateException("migration が無い"))
                .getVersion();
    }

    private List<MigrationInfo> alphaMigrations(String db) {
        java.util.Optional<String> alpha = alphaMajor(db);
        assertThat(alpha).as("α の migration（説明に %s を含む締め期間表の作成）が存在すること", ALPHA_MARKER)
                .isPresent();
        List<MigrationInfo> list = Arrays.stream(flyway(db, null).info().all())
                .filter(m -> m.getVersion() != null && major(m).equals(alpha.get()))
                .toList();
        assertThat(list).isNotEmpty();
        return list;
    }

    /** 本番と同じ起動経路（事前検査 → repair → migrate）。 */
    private void startUp(String db) {
        new FlywayRepairConfig().flywayMigrationStrategy().migrate(flyway(db, null));
    }

    private long appliedAlpha(Connection c, String db) throws SQLException {
        return scalar(c, "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1 AND version LIKE '"
                + alphaMajor(db).orElseThrow() + ".%'");
    }

    // ───────────────────────── 投入 ─────────────────────────

    /** 既存行のある「移行元」DB を作る。 */
    private final class Seed {
        long org;
        long team;
        long user;
        long fy;
        long cat;
        long schedule;
        long a1;
        long a2;
        long a3;
        long a4;
        long j1;
        long j3live;
        long j3deleted;
        long j4deleted;
        long c1;
        long c2;
        long c3;
        long c4;
        long c5;
        long slot1;
        long slot2;
        long slot3;
        long slot4;
        long slot5;

        Seed into(Connection c) throws SQLException {
            exec(c, "SET FOREIGN_KEY_CHECKS = 0");
            user = insert(c, "INSERT INTO users (email, last_name, first_name, display_name, status, created_at, updated_at) "
                    + "VALUES (CONCAT('m2152-', UUID(), '@example.com'), '移行', '太郎', '移行太郎', 'ACTIVE', NOW(), NOW())");
            org = insert(c, "INSERT INTO organizations (name, org_type, slug, created_at, updated_at) "
                    + "VALUES ('2152移行', 'OTHER', CONCAT('m-', LEFT(REPLACE(UUID(),'-',''),10)), NOW(), NOW())");
            team = insert(c, "INSERT INTO teams (name, slug, visibility, created_at, updated_at) "
                    + "VALUES ('2152移行チーム', CONCAT('m-', LEFT(REPLACE(UUID(),'-',''),10)), 'PUBLIC', NOW(), NOW())");
            fy = insert(c, "INSERT INTO budget_fiscal_years (scope_type, scope_id, name, start_date, end_date, created_by) "
                    + "VALUES ('ORGANIZATION', " + org + ", CONCAT('2152年度', UUID()), '2026-04-01', '2027-03-31', " + user + ")");
            cat = insert(c, "INSERT INTO budget_categories (fiscal_year_id, name, category_type) VALUES (" + fy
                    + ", '2152費目', 'EXPENSE')");
            schedule = insert(c, "INSERT INTO shift_schedules (team_id, title, start_date, end_date) VALUES (" + team
                    + ", '2152移行シフト', '2026-04-01', '2026-07-31')");
            a1 = allocation(c, "2026-04-01", "2026-04-30");
            a2 = allocation(c, "2026-05-01", "2026-05-31");
            a3 = allocation(c, "2026-06-01", "2026-06-30");
            a4 = allocation(c, "2026-07-01", "2026-07-31");
            slot1 = slot(c, "2026-04-10");
            slot2 = slot(c, "2026-05-20");
            slot3 = slot(c, "2026-05-21");
            slot4 = slot(c, "2026-05-22");
            slot5 = slot(c, "2026-06-03");
            // 旧締め済み（CONFIRMED）
            c1 = consumption(c, a1, slot1, "CONFIRMED", "2026-04-10 09:00:00", null);
            // 記録日（6/2）と勤務日（5/20）が別期間 → slot_date を採用
            c2 = consumption(c, a2, slot2, "PLANNED", "2026-06-02 09:00:00", null);
            // CANCELLED・論理削除も埋める
            c3 = consumption(c, a2, slot3, "CANCELLED", "2026-05-01 09:00:00", null);
            c4 = consumption(c, a2, slot4, "PLANNED", "2026-05-01 09:00:00", "2026-05-02 00:00:00");
            // 移行前に枠の日付が変更済み → 現在の日付（6/3）
            c5 = consumption(c, a3, slot5, "CONFIRMED", "2026-06-01 09:00:00", null);
            // 旧月次仕訳: A1 生存、A3 生存＋削除済み（V209 共存）、A4 削除済みのみ
            j1 = journal(c, a1, "2026-04-30", 9600, null);
            j3live = journal(c, a3, "2026-06-30", 9600, null);
            j3deleted = journal(c, a3, "2026-06-30", 9600, "2026-09-11 21:39:23");
            j4deleted = journal(c, a4, "2026-07-31", 0, "2026-09-11 21:39:23");
            exec(c, "SET FOREIGN_KEY_CHECKS = 1");
            return this;
        }

        private long allocation(Connection c, String start, String end) throws SQLException {
            return insert(c, "INSERT INTO shift_budget_allocations (organization_id, team_id, fiscal_year_id, budget_category_id, "
                    + "period_start, period_end, allocated_amount, consumed_amount, confirmed_amount, created_by) VALUES ("
                    + org + ", " + team + ", " + fy + ", " + cat + ", '" + start + "', '" + end + "', 100000, 0, 0, " + user + ")");
        }

        private long slot(Connection c, String date) throws SQLException {
            return insert(c, "INSERT INTO shift_slots (schedule_id, slot_date, start_time, end_time) VALUES (" + schedule
                    + ", '" + date + "', '09:00:00', '17:00:00')");
        }

        private long consumption(Connection c, long allocation, long slot, String status, String recordedAt,
                                 String deletedAt) throws SQLException {
            return insert(c, "INSERT INTO shift_budget_consumptions (allocation_id, shift_id, slot_id, user_id, "
                    + "hourly_rate_snapshot, hours, amount, status, recorded_at, deleted_at) VALUES (" + allocation + ", "
                    + schedule + ", " + slot + ", " + user + ", 1200.00, 8.00, 9600.00, '" + status + "', '" + recordedAt + "', "
                    + (deletedAt == null ? "NULL" : "'" + deletedAt + "'") + ")");
        }

        private long journal(Connection c, long allocation, String date, int amount, String deletedAt) throws SQLException {
            return insert(c, "INSERT INTO budget_transactions (fiscal_year_id, category_id, scope_type, scope_id, "
                    + "transaction_type, amount, transaction_date, title, is_auto_recorded, source_type, source_id, "
                    + "approval_status, recorded_by, deleted_at) VALUES (" + fy + ", " + cat + ", 'TEAM', " + team
                    + ", 'EXPENSE', " + amount + ", '" + date + "', '旧月次集計', 1, 'SHIFT_BUDGET_MONTHLY', " + allocation
                    + ", 'APPROVED', " + user + ", " + (deletedAt == null ? "NULL" : "'" + deletedAt + "'") + ")");
        }
    }

    // ───────────────────────── 試練 ─────────────────────────

    @Test
    @Order(1)
    @DisplayName("試練135/118/129b/127/134a/146c AC60・41・52・50・59α・75 既存行のある移行元へ α を（途中段階で一度止めてから）適用 → work_date 復元・旧締め取り込み・原仕訳不変・索引・β 表なし")
    void 試練135_既存行のあるDBへのα適用() throws Exception {
        String db = "m2152_normal";
        createDb(db);
        flyway(db, preAlphaTarget(db)).migrate();
        Seed seed;
        List<Map<String, Object>> journalsBefore;
        try (Connection c = conn(db)) {
            seed = new Seed().into(c);
            journalsBefore = rows(c, "SELECT id, amount, transaction_date, deleted_at, title FROM budget_transactions "
                    + "WHERE source_type = 'SHIFT_BUDGET_MONTHLY' ORDER BY id");
        }

        // 試練146c: α を途中段階まで適用して止め（中間ファイル障害の模擬）、本番の起動経路で残りを適用して収束
        List<MigrationInfo> alpha = alphaMigrations(db);
        assertThat(alpha.size()).as("α は複数ファイル（1ファイル1DDL）").isGreaterThanOrEqualTo(2);
        flyway(db, alpha.get(alpha.size() - 2).getVersion().getVersion()).migrate();
        startUp(db);

        try (Connection c = conn(db)) {
            assertThat(scalar(c, "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0")).isZero();
            assertThat(appliedAlpha(c, db)).isEqualTo(alpha.size());

            // 試練118 AC41: 全状態の行を slot_date で埋める（recorded_at は使わない）、NOT NULL 化
            assertThat(workDate(c, seed.c1)).isEqualTo("2026-04-10");
            assertThat(workDate(c, seed.c2)).as("記録日 6/2 ではなく勤務日").isEqualTo("2026-05-20");
            assertThat(workDate(c, seed.c3)).isEqualTo("2026-05-21");
            assertThat(workDate(c, seed.c4)).isEqualTo("2026-05-22");
            assertThat(workDate(c, seed.c5)).as("移行前に変更済みの枠は現在の日付").isEqualTo("2026-06-03");
            assertThat(text(c, "SELECT IS_NULLABLE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                    + "AND TABLE_NAME = 'shift_budget_consumptions' AND COLUMN_NAME = 'work_date'")).isEqualTo("NO");
            assertThat(text(c, "SELECT IS_NULLABLE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                    + "AND TABLE_NAME = 'shift_budget_consumptions' AND COLUMN_NAME = 'close_event_id'")).isEqualTo("YES");

            // 試練129b AC52: 生存旧仕訳のみ取り込み（暦月の期間行・CLOSED/UNKNOWN/LEGACY_IMPORT・POSTED 判定）
            assertThat(rows(c, "SELECT period_key, period_start, period_end, closing_day FROM shift_budget_close_periods "
                    + "WHERE organization_id = " + seed.org + " ORDER BY period_key"))
                    .extracting(r -> r.get("period_key") + " " + r.get("period_start") + "〜" + r.get("period_end")
                            + " d=" + r.get("closing_day"))
                    .containsExactly("2026-04 2026-04-01〜2026-04-30 d=0", "2026-06 2026-06-01〜2026-06-30 d=0");
            List<Map<String, Object>> e1 = events(c, seed.a1);
            assertThat(e1).hasSize(1);
            assertThat(e1.get(0).get("state")).isEqualTo("CLOSED");
            assertThat(e1.get(0).get("actor_kind")).isEqualTo("UNKNOWN");
            assertThat(e1.get(0).get("origin")).isEqualTo("LEGACY_IMPORT");
            assertThat(e1.get(0).get("journal_date").toString()).isEqualTo("2026-04-30");
            assertThat(((Number) e1.get(0).get("journal_transaction_id")).longValue()).isEqualTo(seed.j1);
            assertThat(((Number) e1.get(0).get("line_count")).intValue()).isZero();
            assertThat(events(c, seed.a3)).as("V209: 生存と削除済みが共存 → 生存1件のみ").hasSize(1);
            assertThat(((Number) events(c, seed.a3).get(0).get("journal_transaction_id")).longValue())
                    .isEqualTo(seed.j3live);
            assertThat(events(c, seed.a4)).as("削除済みのみの月はイベントを作らない（通常締め可）").isEmpty();
            assertThat(events(c, seed.a2)).isEmpty();
            assertThat(rows(c, "SELECT outcome, journal_transaction_id FROM budget_shift_close_postings WHERE allocation_id IN ("
                    + seed.a1 + "," + seed.a3 + ") ORDER BY journal_transaction_id"))
                    .extracting(r -> r.get("outcome") + ":" + r.get("journal_transaction_id"))
                    .containsExactly("POSTED:" + seed.j1, "POSTED:" + seed.j3live);
            assertThat(scalar(c, "SELECT COUNT(*) FROM shift_budget_consumptions WHERE close_event_id IS NOT NULL"))
                    .as("旧行に印は付けない").isZero();

            // AC60: 原仕訳の金額・日付・件数（削除済み含む）は不変
            assertThat(rows(c, "SELECT id, amount, transaction_date, deleted_at, title FROM budget_transactions "
                    + "WHERE source_type = 'SHIFT_BUDGET_MONTHLY' ORDER BY id")).isEqualTo(journalsBefore);

            // 試練127 AC50: 範囲検索の索引・一意性
            assertThat(indexes(c, "shift_budget_consumptions")).contains("allocation_id,status,work_date");
            assertThat(uniqueIndexes(c, "shift_budget_close_periods")).contains(
                    "organization_id,period_key", "organization_id,period_start", "organization_id,period_end");
            assertThat(uniqueIndexes(c, "shift_budget_monthly_close_events")).contains("allocation_id,active_month",
                    "allocation_id,active_journal_date");
            assertThat(indexes(c, "shift_budget_monthly_close_events")).contains("allocation_id,close_period_id");
            assertThat(uniqueIndexes(c, "shift_budget_record_deferrals")).contains("shift_id,pending_key");
            assertThat(indexes(c, "shift_budget_record_deferrals")).contains("close_event_id,state", "state,created_at");
            assertThat(uniqueIndexes(c, "shift_budget_closing_day_changes")).contains("setting_id,revision");
            assertThat(indexes(c, "shift_budget_closing_day_changes")).contains("organization_id,effective_period_key");
            assertThat(uniqueIndexes(c, "shift_budget_closing_settings")).contains("organization_id");
            assertThat(uniqueIndexes(c, "budget_shift_close_postings")).contains("close_event_id");
            for (String t : List.of("shift_budget_closing_settings", "shift_budget_closing_day_changes",
                    "shift_budget_close_periods", "shift_budget_monthly_close_events", "shift_budget_record_deferrals",
                    "budget_shift_close_postings")) {
                assertThat(text(c, "SELECT TABLE_COLLATION FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() "
                        + "AND TABLE_NAME = '" + t + "'")).as("%s の照合順序", t).isEqualTo("utf8mb4_0900_ai_ci");
            }
            assertThat(fk(c, "shift_budget_consumptions", "close_event_id"))
                    .isEqualTo("shift_budget_monthly_close_events:RESTRICT");
            assertThat(fk(c, "shift_budget_monthly_close_events", "close_period_id"))
                    .isEqualTo("shift_budget_close_periods:RESTRICT");

            // 試練134a AC59α: β の表は α に含まれない
            for (String t : List.of("budget_monthly_close_records", "budget_monthly_close_corrections",
                    "budget_correction_command_outcomes", "shift_budget_work_claims")) {
                assertThat(scalar(c, "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() "
                        + "AND TABLE_NAME = '" + t + "'")).as("β 表 %s は α に無い", t).isZero();
            }
        }
    }

    @Test
    @Order(2)
    @DisplayName("試練118c/129b/135/146b AC41・52・60・75 移行元に異常（枠なし消化行・月末以外／同月2件／割当なしの生存旧仕訳）→ 事前検査で起動停止、異常行 ID を示し、スキーマ・データ・履歴とも不変")
    void 試練146b_異常データは事前検査で起動停止() throws Exception {
        String db = "m2152_anomaly";
        createDb(db);
        flyway(db, preAlphaTarget(db)).migrate();
        Seed seed;
        long orphan;
        long notMonthEnd;
        long dup1;
        long dup2;
        long noAllocation;
        Map<String, Long> before;
        try (Connection c = conn(db)) {
            seed = new Seed().into(c);
            exec(c, "SET FOREIGN_KEY_CHECKS = 0");
            orphan = seed.consumption(c, seed.a2, 99_000_001L, "PLANNED", "2026-05-01 09:00:00", null);
            long a5 = seed.allocation(c, "2026-08-01", "2026-08-31");
            notMonthEnd = seed.journal(c, a5, "2026-08-15", 100, null);
            long a6 = seed.allocation(c, "2026-09-01", "2026-09-30");
            dup1 = seed.journal(c, a6, "2026-09-30", 100, null);
            dup2 = seed.journal(c, a6, "2026-09-30", 100, null);
            noAllocation = seed.journal(c, 99_000_002L, "2026-10-31", 100, null);
            exec(c, "SET FOREIGN_KEY_CHECKS = 1");
            before = fingerprint(c);
        }

        assertThatThrownBy(() -> startUp(db))
                .as("事前検査で起動停止")
                .hasMessageContaining(String.valueOf(orphan))
                .hasMessageContaining(String.valueOf(notMonthEnd))
                .hasMessageContaining(String.valueOf(dup2))
                .hasMessageContaining(String.valueOf(noAllocation));

        try (Connection c = conn(db)) {
            assertThat(fingerprint(c)).as("スキーマ・データ・履歴とも不変").isEqualTo(before);
        }

        // 試練146d: 事前検査を通らない直実行（CLI 相当）は migration 内防御で失敗する。データ修正後、本番の起動経路で
        // 失敗履歴は自動 repair され、残りが再適用されて収束する
        assertThatThrownBy(() -> flyway(db, null).migrate()).as("migration 内防御").isInstanceOf(Exception.class);
        try (Connection c = conn(db)) {
            exec(c, "SET FOREIGN_KEY_CHECKS = 0");
            exec(c, "DELETE FROM shift_budget_consumptions WHERE id = " + orphan);
            exec(c, "DELETE FROM budget_transactions WHERE id IN (" + notMonthEnd + "," + dup2 + "," + noAllocation + ")");
            exec(c, "SET FOREIGN_KEY_CHECKS = 1");
        }
        startUp(db);
        try (Connection c = conn(db)) {
            assertThat(scalar(c, "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0")).isZero();
            assertThat(appliedAlpha(c, db)).isEqualTo(alphaMigrations(db).size());
            assertThat(workDate(c, seed.c2)).isEqualTo("2026-05-20");
            assertThat(scalar(c, "SELECT COUNT(*) FROM shift_budget_monthly_close_events WHERE journal_transaction_id = "
                    + dup1)).isEqualTo(1);
        }
    }

    @Test
    @Order(3)
    @DisplayName("試練146a AC75 空 DB は事前検査を省略して全 migration（α 含む）を適用し起動する")
    void 試練146a_空DBは検査省略で起動() throws Exception {
        String db = "m2152_empty";
        createDb(db);
        startUp(db);
        try (Connection c = conn(db)) {
            assertThat(scalar(c, "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0")).isZero();
            assertThat(appliedAlpha(c, db)).isEqualTo(alphaMigrations(db).size());
        }
    }

    @Test
    @Order(4)
    @DisplayName("試練146e AC75 work_date 列の追加まで適用して停止した DB（NULL は正常な中間状態）から再起動 → 事前検査を通り、残りの migration が適用され最終状態に収束する")
    void 試練146e_列追加まで適用で停止したDBから再起動() throws Exception {
        String db = "m2152_halfway";
        createDb(db);
        flyway(db, preAlphaTarget(db)).migrate();
        String addColumnVersion = alphaMigrations(db).stream()
                .filter(m -> m.getDescription().toLowerCase().replace('_', ' ').contains("add work date"))
                .map(m -> m.getVersion().getVersion())
                .findFirst().orElseThrow(() -> new AssertionError("work_date 列を追加する α の migration が無い"));
        flyway(db, addColumnVersion).migrate();
        Seed seed;
        try (Connection c = conn(db)) {
            assertThat(scalar(c, "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                    + "AND TABLE_NAME = 'shift_budget_consumptions' AND COLUMN_NAME = 'work_date'")).isEqualTo(1);
            seed = new Seed().into(c);
            assertThat(scalar(c, "SELECT COUNT(*) FROM shift_budget_consumptions WHERE work_date IS NULL"))
                    .as("backfill 前は NULL").isPositive();
        }

        startUp(db);

        try (Connection c = conn(db)) {
            assertThat(scalar(c, "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0")).isZero();
            assertThat(appliedAlpha(c, db)).isEqualTo(alphaMigrations(db).size());
            assertThat(workDate(c, seed.c2)).isEqualTo("2026-05-20");
            assertThat(scalar(c, "SELECT COUNT(*) FROM shift_budget_consumptions WHERE work_date IS NULL")).isZero();
        }
    }

    // ───────────────────────── ヘルパー ─────────────────────────

    private List<Map<String, Object>> events(Connection c, long allocationId) throws SQLException {
        return rows(c, "SELECT state, actor_kind, origin, journal_date, journal_transaction_id, line_count, planned_total "
                + "FROM shift_budget_monthly_close_events WHERE allocation_id = " + allocationId);
    }

    private String workDate(Connection c, long consumptionId) throws SQLException {
        return text(c, "SELECT work_date FROM shift_budget_consumptions WHERE id = " + consumptionId);
    }

    /** スキーマ（列・表）・データ（主要表の件数と合計）・履歴の指紋。 */
    private Map<String, Long> fingerprint(Connection c) throws SQLException {
        Map<String, Long> f = new LinkedHashMap<>();
        f.put("tables", scalar(c, "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE()"));
        f.put("columns", scalar(c, "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()"));
        f.put("indexes", scalar(c, "SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE()"));
        f.put("history", scalar(c, "SELECT COUNT(*) FROM flyway_schema_history"));
        f.put("history_ok", scalar(c, "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1"));
        f.put("consumptions", scalar(c, "SELECT COUNT(*) FROM shift_budget_consumptions"));
        f.put("consumption_amount", scalar(c, "SELECT COALESCE(SUM(amount), 0) FROM shift_budget_consumptions"));
        f.put("journals", scalar(c, "SELECT COUNT(*) FROM budget_transactions"));
        f.put("journal_amount", scalar(c, "SELECT COALESCE(SUM(amount), 0) FROM budget_transactions"));
        f.put("journal_deleted", scalar(c, "SELECT COUNT(*) FROM budget_transactions WHERE deleted_at IS NOT NULL"));
        return f;
    }

    private List<String> indexes(Connection c, String table) throws SQLException {
        return indexColumns(c, table, false);
    }

    private List<String> uniqueIndexes(Connection c, String table) throws SQLException {
        return indexColumns(c, table, true);
    }

    private List<String> indexColumns(Connection c, String table, boolean uniqueOnly) throws SQLException {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> r : rows(c, "SELECT INDEX_NAME, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS cols, "
                + "MIN(NON_UNIQUE) AS nu FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() "
                + "AND TABLE_NAME = '" + table + "' GROUP BY INDEX_NAME")) {
            if (!uniqueOnly || ((Number) r.get("nu")).intValue() == 0) {
                out.add((String) r.get("cols"));
            }
        }
        return out;
    }

    private String fk(Connection c, String table, String column) throws SQLException {
        List<Map<String, Object>> r = rows(c, "SELECT k.REFERENCED_TABLE_NAME AS t, rc.DELETE_RULE AS d FROM "
                + "information_schema.KEY_COLUMN_USAGE k JOIN information_schema.REFERENTIAL_CONSTRAINTS rc "
                + "ON rc.CONSTRAINT_SCHEMA = k.CONSTRAINT_SCHEMA AND rc.CONSTRAINT_NAME = k.CONSTRAINT_NAME "
                + "WHERE k.TABLE_SCHEMA = DATABASE() AND k.TABLE_NAME = '" + table + "' AND k.COLUMN_NAME = '" + column + "'");
        return r.isEmpty() ? "（FK なし）" : r.get(0).get("t") + ":" + r.get(0).get("d");
    }

    private static void exec(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static long insert(Connection c, String sql) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static long scalar(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String text(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static List<Map<String, Object>> rows(Connection c, String sql) throws SQLException {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= n; i++) {
                    row.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
                }
                out.add(row);
            }
        }
        return out;
    }
}
