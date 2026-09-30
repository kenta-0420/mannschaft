package com.mannschaft.app.common.migration;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.dialect.MySQLDialect;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 凍結台帳 {@code KNOWN_UNPAID_DRIFT} 22 列の返済 migration（V230 の 3 本・CMP-260924-0010）を
 * <b>既存データのある DB</b> で検証するための仕掛け。検証メソッド本体は
 * {@link FlywayFromScratchMigrationTest} にあり、本クラスはテストクラスではない（コンテナを持たない）。
 *
 * <h2>なぜ Flyway の Callback なのか</h2>
 * <p>Flyway 実スキーマを要する番人は {@link FlywayFromScratchMigrationTest} に相乗りさせ、
 * コンテナ起動と全 migration の走査を 1 回に集約する方針である（{@code backend/.claudecode.md}）。
 * 一方、返済 migration の危険（既存行の埋め戻し・途中状態からの再実行・既存値の保護）は
 * <b>空の DB では見えない</b>。そこで from-scratch 適用の途中に割り込む:</p>
 * <ol>
 *   <li>{@link Event#BEFORE_EACH_MIGRATE} で対象が U1（最初の返済 migration）のとき、
 *       「返済前から在る行」をシードし、一部の列を番兵値つきで先に足した「途中状態」を作る。</li>
 *   <li>{@link Event#AFTER_EACH_MIGRATE} で対象が U3（最後の返済 migration）のとき、
 *       埋め戻し（AC-4）・番兵の保護（AC-5b）・既存回覧行（AC-7c）を検査し、
 *       V230 の 3 本だけを同じスキーマへ再実行して定義と値が変わらないこと（AC-5a）を検査する。</li>
 *   <li>検査後、シード行を削除する。シード行は外部キー検査を切って入れた（親行の無い）行なので、
 *       残すと後発 migration（将来の FK 追加など）を無関係に落としうるため。</li>
 * </ol>
 * <p>検査結果は AC ごとに保持し、{@link FlywayFromScratchMigrationTest} の各テストが再送出する
 * （Callback 内で例外を投げると migration 全体が止まり、順序適用の番人まで巻き込むため）。
 * Callback は Flyway と<b>同じ接続</b>を使う。別接続にすると、U3 の未コミットの行ロックや
 * メタデータロックと衝突して待ち続けうるため。</p>
 */
final class UnpaidDriftRepaymentFixture implements Callback {

    /** U1 回覧受信者のスキップ 3 列。返済 migration 3 本のうち最初のもの。 */
    static final String U1_VERSION = "230.20260929233203";
    /** U2 大会エントリーメンバーの列。 */
    static final String U2_VERSION = "230.20260929233204";
    /** U3 BaseEntity 系 15 テーブルの created_at / updated_at。最後のもの。 */
    static final String U3_VERSION = "230.20260929233205";

    /** シード行の元日時（DEFAULT CURRENT_TIMESTAMP と区別できる過去の固定値）。 */
    static final String SEED_CREATED_AT = "2020-01-02 03:04:05";
    static final String SEED_RECORDED_AT = "2020-02-03 04:05:06";
    static final String SEED_VOTED_AT = "2020-03-04 05:06:07";
    /** 「途中状態」で既に在った列に入れる番兵値。再実行で上書きされてはならない。 */
    static final String SENTINEL_AT = "2001-01-01 00:00:00";
    static final String SENTINEL_SKIP_REASON = "SENTINEL";

    /** 返済前から在る回覧受信者行の document_id（番兵なし / 番兵あり）。 */
    private static final long SEED_DOC_PLAIN = 900_001L;
    private static final long SEED_DOC_SENTINEL = 900_002L;
    private static final String SEED_TEM_ID = "seed-tem";
    private static final String SEED_TETM_ID = "seed-tetm";

    /** updated_at だけが欠落していた（created_at から埋め戻す）テーブル。parking は番兵用に別扱い。 */
    static final List<String> UPDATED_AT_BACKFILL_TABLES = List.of(
            "ad_conversions",
            "analytics_alert_history",
            "attendance_transition_alerts",
            "budget_transaction_attachments",
            "chart_body_marks",
            "chart_photos",
            "committee_distribution_logs",
            "job_check_ins",
            "line_message_logs",
            "onboarding_step_completions",
            "webhook_event_subscriptions");

    /** 今回足す 22 列（テーブル.列）。 */
    static final List<String> REPAID_COLUMNS = List.of(
            "circulation_recipients.skip_reason",
            "circulation_recipients.skipped_by",
            "circulation_recipients.skipped_at",
            "tournament_entry_members.member_number",
            "tournament_entry_template_members.created_at",
            "tournament_entry_template_members.updated_at",
            "ad_conversions.updated_at",
            "analytics_alert_history.updated_at",
            "attendance_transition_alerts.updated_at",
            "budget_transaction_attachments.updated_at",
            "chart_body_marks.updated_at",
            "chart_photos.updated_at",
            "committee_distribution_logs.updated_at",
            "daily_attendance_records.created_at",
            "job_check_ins.updated_at",
            "line_message_logs.updated_at",
            "onboarding_step_completions.updated_at",
            "parking_applications.updated_at",
            "period_attendance_records.created_at",
            "proxy_votes.created_at",
            "proxy_votes.updated_at",
            "webhook_event_subscriptions.updated_at");

    /** 検査の実施状況（from-scratch 適用 1 回ぶん。コンテナはクラスで 1 つなので static で足りる）。 */
    private static volatile boolean seeded;
    private static volatile boolean verified;
    /** AC ごとの検査失敗（無ければ登録されない）。 */
    private static final Map<String, Throwable> FAILURES = new LinkedHashMap<>();

    @Override
    public boolean supports(Event event, Context context) {
        return event == Event.BEFORE_EACH_MIGRATE || event == Event.AFTER_EACH_MIGRATE;
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        return true;
    }

    @Override
    public String getCallbackName() {
        return "UnpaidDriftRepaymentFixture";
    }

    @Override
    public void handle(Event event, Context context) {
        if (context.getMigrationInfo() == null || context.getMigrationInfo().getVersion() == null) {
            return;
        }
        MigrationVersion version = context.getMigrationInfo().getVersion();
        Connection conn = context.getConnection();
        if (event == Event.BEFORE_EACH_MIGRATE && version.equals(MigrationVersion.fromVersion(U1_VERSION))) {
            run(conn, "seed", () -> {
                for (String key : REPAID_COLUMNS) {
                    String[] tc = key.split("\\.");
                    assertThat(columnExists(conn, tc[0], tc[1])).as("返済前は %s が存在しないこと", key).isFalse();
                }
                seedExistingRows(conn);
                makePartiallyAppliedState(conn);
                seeded = true;
            });
        } else if (event == Event.AFTER_EACH_MIGRATE && version.equals(MigrationVersion.fromVersion(U3_VERSION))) {
            if (seeded) {
                run(conn, "AC-4", () -> assertBackfilled(conn));
                run(conn, "AC-5b", () -> assertSentinelsKept(conn));
                run(conn, "AC-7c", () -> assertPreexistingRecipientUntouched(conn));
                run(conn, "AC-5a", () -> rerunRepaymentMigrations(conn));
            }
            run(conn, "cleanup", () -> deleteSeedRows(conn));
            verified = seeded;
        }
    }

    /** 外部キー検査を切って検査を 1 つ実行し、失敗は AC ごとに記録する（migration 自体は止めない）。 */
    private static void run(Connection conn, String ac, CheckedRunnable body) {
        try (Statement st = conn.createStatement()) {
            st.execute("SET FOREIGN_KEY_CHECKS = 0");
            try {
                body.run();
            } finally {
                st.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        } catch (Throwable t) {
            FAILURES.put(ac, t);
        }
    }

    /** 指定 AC の検査が実施され、失敗していないことを確かめる（失敗していれば元の例外を投げ直す）。 */
    static void assertPassed(String ac) throws Throwable {
        assertThat(FAILURES.get("seed")).as("既存行のシード・途中状態の作成に失敗していないこと").isNull();
        assertThat(seeded && verified)
                .as("V230 の適用前後で検査が実際に走ったこと（Callback が一度も発火しなければ偽 green になる）")
                .isTrue();
        Throwable failure = FAILURES.get(ac);
        if (failure != null) {
            throw failure;
        }
        assertThat(FAILURES.get("cleanup")).as("シード行の削除に失敗していないこと").isNull();
    }

    @FunctionalInterface
    private interface CheckedRunnable {
        void run() throws Exception;
    }

    // ==================================================================
    // 既存データの作り込み
    // ==================================================================

    private static void seedExistingRows(Connection conn) throws SQLException {
        for (String table : UPDATED_AT_BACKFILL_TABLES) {
            Map<String, String> explicit = new HashMap<>();
            explicit.put("created_at", quote(SEED_CREATED_AT));
            if ("onboarding_step_completions".equals(table)) {
                explicit.put("completion_type", quote("MANUAL")); // CHECK 制約 chk_osc_completion_type
            }
            seedOneRow(conn, table, explicit);
        }
        seedOneRow(conn, "parking_applications", Map.of("created_at", quote(SEED_CREATED_AT)));
        seedOneRow(conn, "daily_attendance_records",
                Map.of("recorded_at", quote(SEED_RECORDED_AT), "updated_at", quote(SEED_RECORDED_AT)));
        seedOneRow(conn, "period_attendance_records",
                Map.of("recorded_at", quote(SEED_RECORDED_AT), "updated_at", quote(SEED_RECORDED_AT)));
        seedOneRow(conn, "proxy_votes", Map.of("voted_at", quote(SEED_VOTED_AT)));
        seedOneRow(conn, "tournament_entry_members", Map.of("id", quote(SEED_TEM_ID)));
        seedOneRow(conn, "tournament_entry_template_members", Map.of("id", quote(SEED_TETM_ID)));
        seedOneRow(conn, "circulation_recipients", Map.of("document_id", String.valueOf(SEED_DOC_PLAIN), "user_id", "1"));
        seedOneRow(conn, "circulation_recipients",
                Map.of("document_id", String.valueOf(SEED_DOC_SENTINEL), "user_id", "1"));
    }

    /**
     * 返済 migration が途中まで当たった状態を作る（MySQL の DDL は非トランザクションのため、
     * 失敗した migration が一部の列だけを足して止まることは現実に起きる）。
     * 先に足した列には番兵値を入れ、再実行で上書きされないことを AC-5b で確かめる。
     */
    private static void makePartiallyAppliedState(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("ALTER TABLE circulation_recipients ADD COLUMN skip_reason VARCHAR(255) NULL");
            st.executeUpdate("UPDATE circulation_recipients SET skip_reason = '" + SENTINEL_SKIP_REASON
                    + "', updated_at = updated_at WHERE document_id = " + SEED_DOC_SENTINEL);

            st.execute("ALTER TABLE parking_applications ADD COLUMN updated_at DATETIME NOT NULL "
                    + "DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP");
            st.executeUpdate("UPDATE parking_applications SET updated_at = '" + SENTINEL_AT
                    + "' WHERE created_at = '" + SEED_CREATED_AT + "'");

            st.execute("ALTER TABLE period_attendance_records ADD COLUMN created_at DATETIME NOT NULL "
                    + "DEFAULT CURRENT_TIMESTAMP");
            st.executeUpdate("UPDATE period_attendance_records SET created_at = '" + SENTINEL_AT
                    + "', updated_at = updated_at WHERE recorded_at = '" + SEED_RECORDED_AT + "'");
        }
        assertThat(queryLong(conn, "SELECT COUNT(*) FROM period_attendance_records WHERE updated_at = '"
                + SEED_RECORDED_AT + "'")).as("番兵投入で既存 updated_at を壊していないこと").isEqualTo(1L);
    }

    private static void deleteSeedRows(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            for (String table : UPDATED_AT_BACKFILL_TABLES) {
                st.executeUpdate("DELETE FROM " + table + " WHERE created_at = '" + SEED_CREATED_AT + "'");
            }
            st.executeUpdate("DELETE FROM parking_applications WHERE created_at = '" + SEED_CREATED_AT + "'");
            st.executeUpdate("DELETE FROM daily_attendance_records WHERE recorded_at = '" + SEED_RECORDED_AT + "'");
            st.executeUpdate("DELETE FROM period_attendance_records WHERE recorded_at = '" + SEED_RECORDED_AT + "'");
            st.executeUpdate("DELETE FROM proxy_votes WHERE voted_at = '" + SEED_VOTED_AT + "'");
            st.executeUpdate("DELETE FROM tournament_entry_members WHERE id = '" + SEED_TEM_ID + "'");
            st.executeUpdate("DELETE FROM tournament_entry_template_members WHERE id = '" + SEED_TETM_ID + "'");
            st.executeUpdate("DELETE FROM circulation_recipients WHERE document_id IN ("
                    + SEED_DOC_PLAIN + ", " + SEED_DOC_SENTINEL + ")");
        }
    }

    // ==================================================================
    // V230 適用直後の検査
    // ==================================================================

    /** AC-4: 既存行の新列が元の日時列から埋め戻されている。 */
    private static void assertBackfilled(Connection conn) throws SQLException {
        for (String table : UPDATED_AT_BACKFILL_TABLES) {
            assertThat(queryLong(conn, "SELECT COUNT(*) FROM " + table
                    + " WHERE created_at = '" + SEED_CREATED_AT + "'"))
                    .as("%s の既存行が残っていること", table).isEqualTo(1L);
            assertThat(queryLong(conn, "SELECT COUNT(*) FROM " + table
                    + " WHERE created_at = '" + SEED_CREATED_AT + "' AND updated_at = created_at"))
                    .as("%s の既存行の updated_at が created_at で埋め戻されること", table).isEqualTo(1L);
        }
        assertThat(queryLong(conn, "SELECT COUNT(*) FROM daily_attendance_records "
                + "WHERE recorded_at = '" + SEED_RECORDED_AT + "' AND created_at = recorded_at"))
                .as("daily_attendance_records の既存行の created_at が recorded_at で埋め戻されること").isEqualTo(1L);
        assertThat(queryLong(conn, "SELECT COUNT(*) FROM proxy_votes "
                + "WHERE voted_at = '" + SEED_VOTED_AT + "' AND created_at = voted_at AND updated_at = voted_at"))
                .as("proxy_votes の既存行の created_at / updated_at が voted_at で埋め戻されること").isEqualTo(1L);
        assertThat(queryLong(conn, "SELECT COUNT(*) FROM tournament_entry_template_members WHERE id = '"
                + SEED_TETM_ID + "' AND created_at IS NOT NULL AND updated_at IS NOT NULL"))
                .as("tournament_entry_template_members の既存行は DEFAULT で日時が埋まること").isEqualTo(1L);
        assertThat(queryLong(conn, "SELECT COUNT(*) FROM tournament_entry_members WHERE id = '"
                + SEED_TEM_ID + "' AND member_number IS NULL"))
                .as("tournament_entry_members の既存行の member_number は NULL").isEqualTo(1L);
    }

    /** AC-5b: 途中状態で既に在った列の番兵値が上書きされず、既存 updated_at も壊れていない。 */
    private static void assertSentinelsKept(Connection conn) throws SQLException {
        assertThat(queryString(conn, "SELECT DATE_FORMAT(updated_at, '%Y-%m-%d %H:%i:%s') "
                + "FROM parking_applications WHERE created_at = '" + SEED_CREATED_AT + "'"))
                .as("先に在った parking_applications.updated_at の番兵値が残ること").isEqualTo(SENTINEL_AT);
        assertThat(queryString(conn, "SELECT DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s') "
                + "FROM period_attendance_records WHERE recorded_at = '" + SEED_RECORDED_AT + "'"))
                .as("先に在った period_attendance_records.created_at の番兵値が残ること").isEqualTo(SENTINEL_AT);
        assertThat(queryString(conn, "SELECT skip_reason FROM circulation_recipients WHERE document_id = "
                + SEED_DOC_SENTINEL)).as("先に在った circulation_recipients.skip_reason の番兵値が残ること")
                .isEqualTo(SENTINEL_SKIP_REASON);
        // 埋め戻し UPDATE が ON UPDATE CURRENT_TIMESTAMP を誘発して既存の updated_at を壊していないこと
        assertThat(queryLong(conn, "SELECT COUNT(*) FROM period_attendance_records WHERE updated_at = '"
                + SEED_RECORDED_AT + "'")).as("period_attendance_records の既存 updated_at が保たれること").isEqualTo(1L);
        assertThat(queryLong(conn, "SELECT COUNT(*) FROM daily_attendance_records WHERE updated_at = '"
                + SEED_RECORDED_AT + "'"))
                .as("daily_attendance_records の既存 updated_at が埋め戻しで現在時刻に上書きされないこと").isEqualTo(1L);
    }

    /** AC-7c: 返済前から在る回覧受信者行の skip 系 3 列は NULL のまま。 */
    private static void assertPreexistingRecipientUntouched(Connection conn) throws SQLException {
        assertThat(queryLong(conn, "SELECT COUNT(*) FROM circulation_recipients WHERE document_id = "
                + SEED_DOC_PLAIN + " AND skip_reason IS NULL AND skipped_by IS NULL AND skipped_at IS NULL"))
                .as("circulation_recipients の既存行の skip 系 3 列は NULL").isEqualTo(1L);
    }

    /** AC-5a: 全列が在る状態から V230 の 3 本だけを再実行しても成功し、定義と値が変わらない。 */
    private static void rerunRepaymentMigrations(Connection conn) throws Exception {
        for (String key : REPAID_COLUMNS) {
            String[] tc = key.split("\\.");
            assertThat(columnExists(conn, tc[0], tc[1])).as("%s が存在すること", key).isTrue();
        }
        Map<String, ColumnDef> before = snapshotDefinitions(conn);
        try (Statement st = conn.createStatement()) {
            for (String version : List.of(U1_VERSION, U2_VERSION, U3_VERSION)) {
                for (String sql : splitStatements(readMigration(version))) {
                    st.execute(sql);
                }
            }
        }
        assertThat(snapshotDefinitions(conn)).as("再実行で列定義が変わらないこと").isEqualTo(before);
        assertBackfilled(conn);
        assertSentinelsKept(conn);
    }

    // ==================================================================
    // AC-3: 列定義と Entity の整合（最終スキーマに対して呼ぶ）
    // ==================================================================

    /** 返済 22 列の型・長さ・NULL 可否を Entity のフィールド定義と突き合わせ、違反を返す。 */
    static List<String> columnDefinitionViolations(Connection conn) throws Exception {
        Map<String, Class<?>> entityByTable = scanEntitiesByTable();
        List<String> violations = new ArrayList<>();
        for (String key : REPAID_COLUMNS) {
            String[] tc = key.split("\\.");
            String table = tc[0];
            String column = tc[1];
            Class<?> entity = entityByTable.get(table);
            if (entity == null) {
                violations.add(key + " … @Table(name=\"" + table + "\") の Entity が無い");
                continue;
            }
            Field field = findField(entity, snakeToCamel(column));
            if (field == null) {
                violations.add(key + " … " + entity.getSimpleName() + " に対応フィールドが無い");
                continue;
            }
            ColumnDef actual = columnDef(conn, table, column);
            if (actual == null) {
                violations.add(key + " … 列が存在しない");
                continue;
            }
            Column ann = field.getAnnotation(Column.class);
            Class<?> type = field.getType();
            if (type == String.class) {
                int expectedLength = ann != null ? ann.length() : 255;
                if (!"varchar".equals(actual.dataType()) || actual.charLength() == null
                        || actual.charLength() != expectedLength) {
                    violations.add(key + " … Entity は VARCHAR(" + expectedLength + ") だが DB は " + actual.columnType());
                }
            } else if (type == Long.class || type == long.class) {
                // 本リポジトリの ID 列は BIGINT UNSIGNED で統一（参照先 users.id 等と揃える）
                if (!"bigint unsigned".equals(actual.columnType())) {
                    violations.add(key + " … Entity は Long だが DB は " + actual.columnType());
                }
            } else if (type == LocalDateTime.class) {
                if (!"datetime".equals(actual.dataType())) {
                    violations.add(key + " … Entity は LocalDateTime だが DB は " + actual.columnType());
                }
            } else {
                violations.add(key + " … 想定外の Java 型 " + type.getName());
            }

            boolean entityNotNull = ann != null && !ann.nullable();
            boolean isTimestamp = "created_at".equals(column) || "updated_at".equals(column);
            if (entityNotNull || isTimestamp) {
                // created_at / updated_at は Entity の @PrePersist / @PreUpdate が必ず書き込むため NOT NULL。
                // Entity を経由しない INSERT でも落ちないよう、DB 側は非 NULL の既定値を持つこと。
                if (actual.nullable()) {
                    violations.add(key + " … NOT NULL であるべきだが NULL 可");
                }
                if (isTimestamp && actual.columnDefault() == null) {
                    violations.add(key + " … NOT NULL の日時列に既定値が無い");
                }
            } else if (!actual.nullable()) {
                violations.add(key + " … Entity は NULL 可だが DB は NOT NULL");
            }
            if ("updated_at".equals(column)
                    && (actual.extra() == null || !actual.extra().toLowerCase().contains("on update current_timestamp"))) {
                violations.add(key + " … updated_at に ON UPDATE CURRENT_TIMESTAMP が無い（既存慣行と不一致）");
            }
        }
        return violations;
    }

    // ==================================================================
    // Hibernate（Spring を起動しない）
    // ==================================================================

    /**
     * 指定 Entity だけを載せた SessionFactory を Flyway 実スキーマに向けて作る。
     * 命名戦略は Spring Boot 既定と同一。親テーブルの用意を省くため、この接続では外部キー検査を切る。
     */
    static SessionFactory buildSessionFactory(String jdbcUrl, String user, String password, Class<?> entity) {
        String url = jdbcUrl + (jdbcUrl.contains("?") ? "&" : "?") + "sessionVariables=FOREIGN_KEY_CHECKS=0";
        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySetting(AvailableSettings.DIALECT, MySQLDialect.class.getName())
                .applySetting(AvailableSettings.JAKARTA_JDBC_DRIVER, "com.mysql.cj.jdbc.Driver")
                .applySetting(AvailableSettings.JAKARTA_JDBC_URL, url)
                .applySetting(AvailableSettings.JAKARTA_JDBC_USER, user)
                .applySetting(AvailableSettings.JAKARTA_JDBC_PASSWORD, password)
                .applySetting(AvailableSettings.HBM2DDL_AUTO, "none")
                .build();
        try {
            return new MetadataSources(registry)
                    .addAnnotatedClass(BaseEntity.class)
                    .addAnnotatedClass(entity)
                    .getMetadataBuilder()
                    .applyPhysicalNamingStrategy(new CamelCaseToUnderscoresNamingStrategy())
                    .applyImplicitNamingStrategy(new SpringImplicitNamingStrategy())
                    .build()
                    .buildSessionFactory();
        } catch (RuntimeException e) {
            StandardServiceRegistryBuilder.destroy(registry);
            throw e;
        }
    }

    // ==================================================================
    // 補助
    // ==================================================================

    /**
     * NOT NULL かつ既定値の無い列だけを型に応じたダミー値で埋めて 1 行 INSERT する
     * （呼び出し時点の実スキーマを information_schema から読むので、後発 migration の列追加にも追従する）。
     */
    private static void seedOneRow(Connection conn, String table, Map<String, String> explicit) throws SQLException {
        Map<String, String> values = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COLUMN_NAME, DATA_TYPE, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, EXTRA "
                        + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? "
                        + "ORDER BY ORDINAL_POSITION")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString("COLUMN_NAME");
                    String extra = rs.getString("EXTRA") == null ? "" : rs.getString("EXTRA").toLowerCase();
                    if (explicit.containsKey(name)) {
                        values.put(name, explicit.get(name));
                        continue;
                    }
                    if (extra.contains("auto_increment") || extra.contains("generated")) {
                        continue;
                    }
                    if ("YES".equals(rs.getString("IS_NULLABLE")) || rs.getString("COLUMN_DEFAULT") != null) {
                        continue;
                    }
                    values.put(name, dummyLiteral(rs.getString("DATA_TYPE"), rs.getString("COLUMN_TYPE")));
                }
            }
        }
        assertThat(values.keySet()).as("%s の列が読めること", table).isNotEmpty();
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("INSERT INTO " + table + " (" + String.join(", ", values.keySet())
                    + ") VALUES (" + String.join(", ", values.values()) + ")");
        }
    }

    private static String dummyLiteral(String dataType, String columnType) {
        switch (dataType.toLowerCase()) {
            case "tinyint", "smallint", "mediumint", "int", "bigint", "decimal", "float", "double", "bit":
                return "1";
            case "char", "varchar", "tinytext", "text", "mediumtext", "longtext":
                return "'x'";
            case "binary", "varbinary", "tinyblob", "blob", "mediumblob", "longblob":
                return "0x01";
            case "date":
                return "'2020-01-01'";
            case "datetime", "timestamp":
                return "'2020-01-01 00:00:00'";
            case "time":
                return "'00:00:00'";
            case "year":
                return "2020";
            case "json":
                return "'[]'";
            case "enum", "set":
                // enum('A','B') → 'A'
                int start = columnType.indexOf('\'');
                int end = columnType.indexOf('\'', start + 1);
                return columnType.substring(start, end + 1);
            default:
                throw new IllegalStateException("シード未対応の型: " + dataType);
        }
    }

    private static String readMigration(String version) throws Exception {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/migration/V" + version + "__*.sql");
        assertThat(resources).as("V%s の migration がちょうど 1 本あること", version).hasSize(1);
        return new String(resources[0].getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    /**
     * migration の SQL を文単位に分割する。返済 migration は文字列リテラル内に {@code ;} を含まない
     * 書き方に統一しているため、行頭 {@code --} のコメント行を除いて {@code ;} で分割すれば足りる。
     */
    private static List<String> splitStatements(String script) {
        StringBuilder body = new StringBuilder();
        for (String line : script.split("\\R")) {
            if (!line.trim().startsWith("--")) {
                body.append(line).append('\n');
            }
        }
        List<String> statements = new ArrayList<>();
        for (String part : body.toString().split(";")) {
            if (!part.isBlank()) {
                statements.add(part.trim());
            }
        }
        return statements;
    }

    private static boolean columnExists(Connection conn, String table, String column) throws SQLException {
        return columnDef(conn, table, column) != null;
    }

    private record ColumnDef(String dataType, String columnType, Long charLength, boolean nullable,
                             String columnDefault, String extra) {
    }

    private static ColumnDef columnDef(Connection conn, String table, String column) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT DATA_TYPE, COLUMN_TYPE, CHARACTER_MAXIMUM_LENGTH, IS_NULLABLE, COLUMN_DEFAULT, EXTRA "
                        + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                        + "AND TABLE_NAME = ? AND COLUMN_NAME = ?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                long len = rs.getLong("CHARACTER_MAXIMUM_LENGTH");
                return new ColumnDef(
                        rs.getString("DATA_TYPE").toLowerCase(),
                        rs.getString("COLUMN_TYPE").toLowerCase(),
                        rs.wasNull() ? null : len,
                        "YES".equals(rs.getString("IS_NULLABLE")),
                        rs.getString("COLUMN_DEFAULT"),
                        rs.getString("EXTRA"));
            }
        }
    }

    private static Map<String, ColumnDef> snapshotDefinitions(Connection conn) throws SQLException {
        Map<String, ColumnDef> snapshot = new LinkedHashMap<>();
        for (String key : REPAID_COLUMNS) {
            String[] tc = key.split("\\.");
            snapshot.put(key, columnDef(conn, tc[0], tc[1]));
        }
        return snapshot;
    }

    static long queryLong(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertThat(rs.next()).as("結果行があること: %s", sql).isTrue();
            return rs.getLong(1);
        }
    }

    static String queryString(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertThat(rs.next()).as("結果行があること: %s", sql).isTrue();
            return rs.getString(1);
        }
    }

    private static String quote(String value) {
        return "'" + value + "'";
    }

    private static String snakeToCamel(String snake) {
        StringBuilder sb = new StringBuilder();
        boolean upper = false;
        for (char c : snake.toCharArray()) {
            if (c == '_') {
                upper = true;
            } else {
                sb.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return sb.toString();
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // 親クラス（BaseEntity 等）を探す
            }
        }
        return null;
    }

    /** 本番ソースセットの @Entity を走査し、@Table の物理名 → Entity クラスの対応を作る。 */
    private static Map<String, Class<?>> scanEntitiesByTable() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false) {
                    @Override
                    protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
                        return true;
                    }
                };
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        Map<String, Class<?>> byTable = new HashMap<>();
        for (BeanDefinition definition : scanner.findCandidateComponents("com.mannschaft.app")) {
            Class<?> clazz = Class.forName(definition.getBeanClassName());
            Table table = clazz.getAnnotation(Table.class);
            if (table != null && !table.name().isEmpty()) {
                byTable.put(table.name().toLowerCase(), clazz);
            }
        }
        return byTable;
    }
}
