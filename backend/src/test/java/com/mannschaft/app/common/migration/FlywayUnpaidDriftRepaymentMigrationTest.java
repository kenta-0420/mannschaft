package com.mannschaft.app.common.migration;

import com.mannschaft.app.circulation.RecipientStatus;
import com.mannschaft.app.circulation.entity.CirculationRecipientEntity;
import com.mannschaft.app.committee.entity.CommitteeDistributionLogEntity;
import com.mannschaft.app.committee.entity.ConfirmationMode;
import com.mannschaft.app.committee.entity.DistributionScope;
import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.output.MigrateResult;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.dialect.MySQLDialect;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>凍結台帳 {@code KNOWN_UNPAID_DRIFT} 22 列の返済 migration（V228 の 3 本）の番人テスト</b>
 * （CMP-260924-0010）。
 *
 * <h2>なぜ別クラスが要るのか</h2>
 * <p>{@link FlywayFromScratchMigrationTest} は<b>空の DB</b> に全 migration を当て、列の<b>存在</b>だけを見る。
 * 返済 migration の本当の危険は次の 3 点で、いずれも空 DB・存在照合では原理的に見えない:</p>
 * <ol>
 *   <li><b>既存行の埋め戻し</b> — 本番・dev には既に行がある。新列が DEFAULT の「migration 実行時刻」で
 *       埋まると、{@code updated_at} が {@code created_at} より新しい嘘の値になる（AC-4）。</li>
 *   <li><b>冪等性と既存値の保護</b> — 途中まで列が足された環境で再実行しても完遂し、
 *       既に在った列の値を上書きしないこと（AC-5a / AC-5b）。</li>
 *   <li><b>Entity との整合</b> — 型・長さ・NULL 可否が Entity と揃い、実際に ORM で読み書きできること
 *       （AC-3 / AC-6 / AC-7 / AC-8）。</li>
 * </ol>
 *
 * <p>通常の IT（{@code application-test.yml}）は {@code ddl-auto=create} + Flyway 無効のため
 * Entity からスキーマが作られ、上記はいずれも検証できない。本クラスは Testcontainers の実 MySQL 8.0 に
 * <b>返済 migration の直前まで適用 → 既存行をシード → 一部の列を先に足した「途中状態」を作る → 残りを適用</b>
 * という経路を再現し、その Flyway 実スキーマ上で JDBC と（Spring を起動しない）Hibernate で検証する。</p>
 *
 * <p>Docker 未起動環境では {@code @EnabledIf} によりスキップされる。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.common.migration.FlywayUnpaidDriftRepaymentMigrationTest#isDockerAvailable")
@DisplayName("凍結台帳22列の返済 migration（V228）番人テスト")
class FlywayUnpaidDriftRepaymentMigrationTest {

    /** U1 回覧受信者のスキップ 3 列。返済 migration 3 本のうち最初のもの。 */
    private static final String U1_VERSION = "228.20260929072008";
    /** U2 大会エントリーメンバーの列。 */
    private static final String U2_VERSION = "228.20260929072009";
    /** U3 BaseEntity 系 15 テーブルの created_at / updated_at。 */
    private static final String U3_VERSION = "228.20260929072010";

    /** シード行の元日時（DEFAULT CURRENT_TIMESTAMP と区別できる過去の固定値）。 */
    private static final String SEED_CREATED_AT = "2020-01-02 03:04:05";
    private static final String SEED_RECORDED_AT = "2020-02-03 04:05:06";
    private static final String SEED_VOTED_AT = "2020-03-04 05:06:07";
    /** 「途中状態」で既に在った列に入れる番兵値。再実行で上書きされてはならない。 */
    private static final String SENTINEL_AT = "2001-01-01 00:00:00";
    private static final String SENTINEL_SKIP_REASON = "SENTINEL";

    /** updated_at だけが欠落していた（created_at から埋め戻す）テーブル。parking は番兵用に別扱い。 */
    private static final List<String> UPDATED_AT_BACKFILL_TABLES = List.of(
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
    private static final List<String> REPAID_COLUMNS = List.of(
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

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_unpaid_drift")
            .withUsername("test")
            .withPassword("test")
            .withTmpFs(Map.of("/var/lib/mysql", "rw"))
            .withCommand("--log_bin_trust_function_creators=1");

    /** シードした回覧受信者（既存行・番兵なし）の id。AC-7c で Entity から読む。 */
    private long preexistingRecipientId;

    public static boolean isDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 既存データ経路を再現する: 返済 migration の直前まで適用 → 既存行をシード →
     * 一部の列を先に足した「途中状態」を作る → 残り（返済 migration）を適用する。
     */
    @BeforeAll
    void migrateWithExistingData() throws Exception {
        MYSQL.start();

        Flyway pre = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .load();
        MigrationVersion preTarget = versionJustBefore(pre, MigrationVersion.fromVersion(U1_VERSION));
        Flyway preTo = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .target(preTarget)
                .load();
        assertThat(preTo.migrate().success).as("返済 migration の直前（%s）まで適用できること", preTarget).isTrue();

        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute("SET FOREIGN_KEY_CHECKS = 0");

            // sanity: この時点では 22 列がすべて存在しない（＝返済前の旧スキーマであることの担保）
            for (String key : REPAID_COLUMNS) {
                String[] tc = key.split("\\.");
                assertThat(columnExists(conn, tc[0], tc[1])).as("返済前は %s が存在しないこと", key).isFalse();
            }

            seedExistingRows(conn);
            makePartiallyAppliedState(conn, st);
        }

        Flyway rest = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .load();
        MigrateResult result = rest.migrate();
        assertThat(result.success).as("既存行・途中状態のある DB へ返済 migration が適用できること").isTrue();
    }

    @AfterAll
    void stopContainer() {
        MYSQL.stop();
    }

    // ------------------------------------------------------------------
    // AC-3: 型・長さ・NULL 可否が Entity マッピングと整合する
    // ------------------------------------------------------------------

    @Test
    @DisplayName("AC-3: 返済22列の型・長さ・NULL可否がEntityマッピングと整合する")
    void 返済列の型と長さとNULL可否がEntityと整合する() throws Exception {
        Map<String, Class<?>> entityByTable = scanEntitiesByTable();
        List<String> violations = new ArrayList<>();
        try (Connection conn = connect()) {
            for (String key : REPAID_COLUMNS) {
                String[] tc = key.split("\\.");
                String table = tc[0];
                String column = tc[1];
                Class<?> entity = entityByTable.get(table);
                assertThat(entity).as("%s を @Table に持つ Entity が存在すること", table).isNotNull();
                Field field = findField(entity, snakeToCamel(column));
                assertThat(field).as("%s に %s に対応するフィールドがあること", entity.getSimpleName(), column).isNotNull();

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
        }
        assertThat(violations).as("返済 22 列の定義違反").isEmpty();
    }

    // ------------------------------------------------------------------
    // AC-4: 既存行の埋め戻し
    // ------------------------------------------------------------------

    @Test
    @DisplayName("AC-4: 既存行の updated_at は created_at から・出欠の created_at は recorded_at から・proxy_votes は voted_at から埋め戻される")
    void 既存行の新列が元の日時列から埋め戻される() throws Exception {
        try (Connection conn = connect()) {
            assertBackfilled(conn);
        }
    }

    // ------------------------------------------------------------------
    // AC-5: 冪等性・既存値の保護
    // ------------------------------------------------------------------

    @Test
    @DisplayName("AC-5a: 途中まで列が足された状態から完遂し、返済 migration を再実行しても成功して最終定義が正しい")
    void 途中状態からも再実行でも完遂し最終列定義が正しい() throws Exception {
        // given: @BeforeAll で skip_reason / parking.updated_at / period.created_at を先に足した途中状態から完遂済み
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            for (String key : REPAID_COLUMNS) {
                String[] tc = key.split("\\.");
                assertThat(columnExists(conn, tc[0], tc[1])).as("%s が存在すること", key).isTrue();
            }
            Map<String, ColumnDef> before = snapshotDefinitions(conn);

            // when: 返済 migration 3 本を同じ DB へもう一度流す（全列が既に在る状態からの再実行）
            for (String version : List.of(U1_VERSION, U2_VERSION, U3_VERSION)) {
                for (String sql : splitStatements(readMigration(version))) {
                    st.execute(sql);
                }
            }

            // then: 列定義も値も変わらない
            assertThat(snapshotDefinitions(conn)).as("再実行で列定義が変わらないこと").isEqualTo(before);
            assertBackfilled(conn);
        }
    }

    @Test
    @DisplayName("AC-5b: 既に在った列に入れた番兵値は返済 migration で上書きされない")
    void 既に在った列の番兵値が上書きされない() throws Exception {
        try (Connection conn = connect()) {
            assertThat(queryString(conn,
                    "SELECT DATE_FORMAT(updated_at, '%Y-%m-%d %H:%i:%s') FROM parking_applications"))
                    .as("先に在った parking_applications.updated_at の番兵値が残ること").isEqualTo(SENTINEL_AT);
            assertThat(queryString(conn,
                    "SELECT DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s') FROM period_attendance_records"))
                    .as("先に在った period_attendance_records.created_at の番兵値が残ること").isEqualTo(SENTINEL_AT);
            assertThat(queryString(conn,
                    "SELECT skip_reason FROM circulation_recipients WHERE document_id = 902"))
                    .as("先に在った circulation_recipients.skip_reason の番兵値が残ること").isEqualTo(SENTINEL_SKIP_REASON);
            // 埋め戻し UPDATE が ON UPDATE CURRENT_TIMESTAMP を誘発して既存の updated_at を壊していないこと
            assertThat(queryLong(conn,
                    "SELECT COUNT(*) FROM period_attendance_records WHERE updated_at = '" + SEED_RECORDED_AT + "'"))
                    .as("period_attendance_records の既存 updated_at が保たれること").isEqualTo(1L);
            assertThat(queryLong(conn,
                    "SELECT COUNT(*) FROM daily_attendance_records WHERE updated_at = '" + SEED_RECORDED_AT + "'"))
                    .as("daily_attendance_records の既存 updated_at が埋め戻しで現在時刻に上書きされないこと").isEqualTo(1L);
        }
    }

    // ------------------------------------------------------------------
    // AC-6: committee_distribution_logs を Entity で保存・取得・更新できる
    // ------------------------------------------------------------------

    @Test
    @DisplayName("AC-6: committee_distribution_logs を Entity で保存→取得でき、更新で updated_at が進む")
    void 委員会伝達ログをEntityで保存取得更新できる() throws Exception {
        // 構造: updated_at を書き込み不可にしていた回避策（@AttributeOverride）が撤去されていること
        assertThat(CommitteeDistributionLogEntity.class.getAnnotation(AttributeOverrides.class))
                .as("CommitteeDistributionLogEntity に @AttributeOverrides が残っていないこと").isNull();
        assertThat(CommitteeDistributionLogEntity.class.getAnnotation(AttributeOverride.class))
                .as("CommitteeDistributionLogEntity に @AttributeOverride が残っていないこと").isNull();

        try (SessionFactory sf = buildSessionFactory(CommitteeDistributionLogEntity.class)) {
            Long id;
            try (Session s = sf.openSession()) {
                s.beginTransaction();
                CommitteeDistributionLogEntity log = CommitteeDistributionLogEntity.builder()
                        .committeeId(1L)
                        .contentType("CUSTOM_MESSAGE")
                        .customTitle("返済検証")
                        .targetScope(DistributionScope.COMMITTEE_ONLY)
                        .announcementEnabled(false)
                        .confirmationMode(ConfirmationMode.NONE)
                        .createdBy(1L)
                        .build();
                s.persist(log);
                s.getTransaction().commit();
                id = log.getId();
            }
            assertThat(id).isNotNull();

            LocalDateTime firstUpdatedAt;
            try (Session s = sf.openSession()) {
                CommitteeDistributionLogEntity found = s.find(CommitteeDistributionLogEntity.class, id);
                assertThat(found).as("findById で取得できること").isNotNull();
                assertThat(found.getUpdatedAt()).as("updated_at が読めること").isNotNull();
                firstUpdatedAt = found.getUpdatedAt();
            }
            try (Connection conn = connect()) {
                assertThat(queryLong(conn,
                        "SELECT COUNT(*) FROM committee_distribution_logs WHERE id = " + id
                                + " AND updated_at = created_at"))
                        .as("保存時に Entity が updated_at を created_at と同じ値で書き込むこと").isEqualTo(1L);
            }

            Thread.sleep(1_500); // DATETIME は秒精度のため 1 秒以上空ける

            try (Session s = sf.openSession()) {
                s.beginTransaction();
                CommitteeDistributionLogEntity found = s.find(CommitteeDistributionLogEntity.class, id);
                found.applyGeneratedIds("[1]", null);
                s.getTransaction().commit();
            }
            try (Session s = sf.openSession()) {
                CommitteeDistributionLogEntity found = s.find(CommitteeDistributionLogEntity.class, id);
                assertThat(found.getAnnouncementFeedIds()).isEqualTo("[1]");
                assertThat(found.getUpdatedAt()).as("更新で updated_at が進むこと").isAfter(firstUpdatedAt);
            }
        }
    }

    // ------------------------------------------------------------------
    // AC-7: 回覧受信者のスキップ列
    // ------------------------------------------------------------------

    @Test
    @DisplayName("AC-7a: 本人スキップは skipped_at だけを保存し skip_reason / skipped_by は null")
    void 本人スキップはskipped_atのみ保存される() throws Exception {
        try (SessionFactory sf = buildSessionFactory(CirculationRecipientEntity.class)) {
            Long id = persistRecipient(sf, 701L);
            try (Session s = sf.openSession()) {
                s.beginTransaction();
                s.find(CirculationRecipientEntity.class, id).skip();
                s.getTransaction().commit();
            }
            try (Session s = sf.openSession()) {
                CirculationRecipientEntity r = s.find(CirculationRecipientEntity.class, id);
                assertThat(r.getStatus()).isEqualTo(RecipientStatus.SKIPPED);
                assertThat(r.getSkippedAt()).as("skipped_at が保存されること").isNotNull();
                assertThat(r.getSkipReason()).as("本人スキップは skip_reason が null").isNull();
                assertThat(r.getSkippedBy()).as("本人スキップは skipped_by が null").isNull();
            }
        }
    }

    @Test
    @DisplayName("AC-7b: 管理者スキップは skip_reason / skipped_by / skipped_at の3列すべてを保存する")
    void 管理者スキップは3列すべて保存される() throws Exception {
        try (SessionFactory sf = buildSessionFactory(CirculationRecipientEntity.class)) {
            Long id = persistRecipient(sf, 702L);
            try (Session s = sf.openSession()) {
                s.beginTransaction();
                s.find(CirculationRecipientEntity.class, id).adminSkip(1L, "長期不在のため");
                s.getTransaction().commit();
            }
            try (Session s = sf.openSession()) {
                CirculationRecipientEntity r = s.find(CirculationRecipientEntity.class, id);
                assertThat(r.getStatus()).isEqualTo(RecipientStatus.SKIPPED);
                assertThat(r.getSkipReason()).isEqualTo("長期不在のため");
                assertThat(r.getSkippedBy()).isEqualTo(1L);
                assertThat(r.getSkippedAt()).isNotNull();
            }
        }
    }

    @Test
    @DisplayName("AC-7c: migration 前から在る回覧受信者行を Entity で読むと skip 系3列は null のまま")
    void 既存の回覧受信者行はskip系3列がnullのまま読める() throws Exception {
        try (SessionFactory sf = buildSessionFactory(CirculationRecipientEntity.class);
             Session s = sf.openSession()) {
            CirculationRecipientEntity r = s.find(CirculationRecipientEntity.class, preexistingRecipientId);
            assertThat(r).as("既存行を Entity で読めること").isNotNull();
            assertThat(r.getSkipReason()).isNull();
            assertThat(r.getSkippedBy()).isNull();
            assertThat(r.getSkippedAt()).isNull();
            assertThat(r.getCreatedAt()).isNotNull();
            assertThat(r.getUpdatedAt()).isNotNull();
        }
    }

    // ------------------------------------------------------------------
    // AC-8: 返済対象テーブルが Flyway スキーマ上で INSERT / SELECT できる
    // ------------------------------------------------------------------

    @Test
    @DisplayName("AC-8: proxy_votes / daily_attendance_records / tournament_entry_members / tournament_entry_template_members に Entity と同じ列で INSERT・SELECT できる")
    void 返済対象テーブルにEntityと同じ列でINSERTとSELECTができる() throws Exception {
        String ts = "2024-05-06 07:08:09";
        try (Connection conn = connect(); Statement st = conn.createStatement()) {
            st.execute("SET FOREIGN_KEY_CHECKS = 0");

            st.executeUpdate("INSERT INTO proxy_votes (motion_id, user_id, vote_type, is_proxy_vote, delegation_id, "
                    + "voted_at, created_at, updated_at) VALUES (8801, 1, 'APPROVE', 0, NULL, "
                    + "'" + ts + "', '" + ts + "', '" + ts + "')");
            assertThat(queryLong(conn, "SELECT COUNT(*) FROM proxy_votes WHERE motion_id = 8801 "
                    + "AND created_at = '" + ts + "' AND updated_at = '" + ts + "'")).isEqualTo(1L);

            st.executeUpdate("INSERT INTO daily_attendance_records (team_id, student_user_id, attendance_date, status, "
                    + "recorded_by, recorded_at, created_at, updated_at) VALUES (8802, 1, '2024-05-06', 'ATTENDING', 1, "
                    + "'" + ts + "', '" + ts + "', '" + ts + "')");
            assertThat(queryLong(conn, "SELECT COUNT(*) FROM daily_attendance_records WHERE team_id = 8802 "
                    + "AND created_at = '" + ts + "' AND updated_at = '" + ts + "'")).isEqualTo(1L);

            String memberNumber = "M".repeat(50); // Entity の length=50 いっぱい
            st.executeUpdate("INSERT INTO tournament_entry_members (id, participant_id, user_id, member_number, "
                    + "sort_order, created_at, updated_at) VALUES ('ac8-tem', 8803, 1, '" + memberNumber + "', 0, "
                    + "'" + ts + "', '" + ts + "')");
            assertThat(queryString(conn, "SELECT member_number FROM tournament_entry_members WHERE id = 'ac8-tem'"))
                    .isEqualTo(memberNumber);

            st.executeUpdate("INSERT INTO tournament_entry_template_members (id, template_id, user_id, sort_order, "
                    + "created_at, updated_at) VALUES ('ac8-tetm', 'ac8-template', 1, 0, '" + ts + "', '" + ts + "')");
            assertThat(queryLong(conn, "SELECT COUNT(*) FROM tournament_entry_template_members WHERE id = 'ac8-tetm' "
                    + "AND created_at = '" + ts + "' AND updated_at = '" + ts + "'")).isEqualTo(1L);

            // 日時を省略した INSERT でも DEFAULT で埋まる（Entity を経由しない経路の保険）
            st.executeUpdate("INSERT INTO tournament_entry_template_members (id, template_id, user_id, sort_order) "
                    + "VALUES ('ac8-tetm-default', 'ac8-template', 2, 0)");
            assertThat(queryLong(conn, "SELECT COUNT(*) FROM tournament_entry_template_members "
                    + "WHERE id = 'ac8-tetm-default' AND created_at IS NOT NULL AND updated_at IS NOT NULL"))
                    .isEqualTo(1L);
        }
    }

    // ==================================================================
    // 既存データの作り込み
    // ==================================================================

    /** 返済対象の全テーブルに「migration 前から在る行」を 1 行ずつ入れる。 */
    private void seedExistingRows(Connection conn) throws SQLException {
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
        seedOneRow(conn, "tournament_entry_members", Map.of("id", quote("seed-tem")));
        seedOneRow(conn, "tournament_entry_template_members", Map.of("id", quote("seed-tetm")));

        // 回覧受信者: 番兵なしの既存行（AC-7c）と、番兵を入れる既存行（AC-5b）
        seedOneRow(conn, "circulation_recipients", Map.of("document_id", "901", "user_id", "1"));
        preexistingRecipientId = queryLong(conn, "SELECT id FROM circulation_recipients WHERE document_id = 901");
        seedOneRow(conn, "circulation_recipients", Map.of("document_id", "902", "user_id", "1"));
    }

    /**
     * 返済 migration が途中まで当たった状態を作る（MySQL の DDL は非トランザクションのため、
     * 失敗した migration が一部の列だけを足して止まることは現実に起きる）。
     * 先に足した列には番兵値を入れ、再実行で上書きされないことを AC-5b で確かめる。
     */
    private static void makePartiallyAppliedState(Connection conn, Statement st) throws SQLException {
        st.execute("ALTER TABLE circulation_recipients ADD COLUMN skip_reason VARCHAR(255) NULL");
        st.executeUpdate("UPDATE circulation_recipients SET skip_reason = '" + SENTINEL_SKIP_REASON
                + "', updated_at = updated_at WHERE document_id = 902");

        st.execute("ALTER TABLE parking_applications ADD COLUMN updated_at DATETIME NOT NULL "
                + "DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP");
        st.executeUpdate("UPDATE parking_applications SET updated_at = '" + SENTINEL_AT + "'");

        st.execute("ALTER TABLE period_attendance_records ADD COLUMN created_at DATETIME NOT NULL "
                + "DEFAULT CURRENT_TIMESTAMP");
        st.executeUpdate("UPDATE period_attendance_records SET created_at = '" + SENTINEL_AT
                + "', updated_at = updated_at");

        assertThat(queryLong(conn, "SELECT COUNT(*) FROM period_attendance_records WHERE updated_at = '"
                + SEED_RECORDED_AT + "'")).as("番兵投入で既存 updated_at を壊していないこと").isEqualTo(1L);
    }

    /** AC-4 の埋め戻し結果（AC-5a の再実行後にも同じであること）を検証する。 */
    private static void assertBackfilled(Connection conn) throws SQLException {
        for (String table : UPDATED_AT_BACKFILL_TABLES) {
            // 他テスト（AC-6 等）が同じテーブルへ行を足しうるため、シード行（過去の固定日時）に絞る
            assertThat(queryLong(conn, "SELECT COUNT(*) FROM " + table
                    + " WHERE created_at = '" + SEED_CREATED_AT + "'"))
                    .as("%s の既存行が残っていること", table).isEqualTo(1L);
            assertThat(queryLong(conn, "SELECT COUNT(*) FROM " + table
                    + " WHERE created_at = '" + SEED_CREATED_AT + "' AND updated_at = created_at"))
                    .as("%s の既存行の updated_at が created_at で埋め戻されること", table).isEqualTo(1L);
        }
        assertThat(queryLong(conn, "SELECT COUNT(*) FROM daily_attendance_records WHERE team_id <> 8802 "
                + "AND recorded_at = '" + SEED_RECORDED_AT + "' AND created_at = recorded_at"))
                .as("daily_attendance_records の既存行の created_at が recorded_at で埋め戻されること").isEqualTo(1L);
        assertThat(queryLong(conn, "SELECT COUNT(*) FROM proxy_votes WHERE motion_id <> 8801 "
                + "AND voted_at = '" + SEED_VOTED_AT + "' AND created_at = voted_at AND updated_at = voted_at"))
                .as("proxy_votes の既存行の created_at / updated_at が voted_at で埋め戻されること").isEqualTo(1L);
        assertThat(queryLong(conn, "SELECT COUNT(*) FROM tournament_entry_template_members WHERE id = 'seed-tetm' "
                + "AND created_at IS NOT NULL AND updated_at IS NOT NULL"))
                .as("tournament_entry_template_members の既存行は DEFAULT で日時が埋まること").isEqualTo(1L);
        assertThat(queryLong(conn, "SELECT COUNT(*) FROM tournament_entry_members WHERE id = 'seed-tem' "
                + "AND member_number IS NULL"))
                .as("tournament_entry_members の既存行の member_number は NULL").isEqualTo(1L);
        assertThat(queryLong(conn, "SELECT COUNT(*) FROM circulation_recipients WHERE document_id = 901 "
                + "AND skip_reason IS NULL AND skipped_by IS NULL AND skipped_at IS NULL"))
                .as("circulation_recipients の既存行の skip 系 3 列は NULL").isEqualTo(1L);
    }

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

    // ==================================================================
    // 補助
    // ==================================================================

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    /** 指定バージョンより小さい最大の versioned migration を返す（採番が動いても追従する）。 */
    private static MigrationVersion versionJustBefore(Flyway flyway, MigrationVersion boundary) {
        return Arrays.stream(flyway.info().all())
                .map(MigrationInfo::getVersion)
                .filter(v -> v != null && v.compareTo(boundary) < 0)
                .max(Comparator.naturalOrder())
                .orElseThrow(() -> new IllegalStateException(boundary + " より前の migration が無い"));
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

    private static long queryLong(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertThat(rs.next()).as("結果行があること: %s", sql).isTrue();
            return rs.getLong(1);
        }
    }

    private static String queryString(Connection conn, String sql) throws SQLException {
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

    /**
     * Spring を起動せず、指定 Entity だけを載せた Hibernate の SessionFactory を Flyway 実スキーマに向けて作る。
     * 命名戦略は Spring Boot 既定と同一（{@link FlywayFromScratchMigrationTest} と同じ）。
     * 親テーブル（委員会・回覧文書等）の用意を省くため、この接続では外部キー検査を切る。
     */
    private static SessionFactory buildSessionFactory(Class<?> entity) {
        String url = MYSQL.getJdbcUrl();
        url += (url.contains("?") ? "&" : "?") + "sessionVariables=FOREIGN_KEY_CHECKS=0";
        StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySetting(AvailableSettings.DIALECT, MySQLDialect.class.getName())
                .applySetting(AvailableSettings.JAKARTA_JDBC_DRIVER, "com.mysql.cj.jdbc.Driver")
                .applySetting(AvailableSettings.JAKARTA_JDBC_URL, url)
                .applySetting(AvailableSettings.JAKARTA_JDBC_USER, MYSQL.getUsername())
                .applySetting(AvailableSettings.JAKARTA_JDBC_PASSWORD, MYSQL.getPassword())
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

    private static Long persistRecipient(SessionFactory sf, long documentId) {
        try (Session s = sf.openSession()) {
            s.beginTransaction();
            CirculationRecipientEntity recipient = CirculationRecipientEntity.builder()
                    .documentId(documentId)
                    .userId(1L)
                    .build();
            s.persist(recipient);
            s.getTransaction().commit();
            return recipient.getId();
        }
    }
}
