package com.mannschaft.app.common.migration;

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
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>クロスドメインFK撤廃 最終局面 5-D（Phase 5-D・キャンペーン完結）の番人テスト。</b>
 *
 * <p>V118.001 で「proxy_input_records（proxy ドメイン）を ON DELETE RESTRICT で参照する最後のクロスドメインFK 2件」を撤廃only する。
 * この 2 件の撤廃をもって baseline は空（FK 0 件）となり、クロスドメイン FK が全廃される（キャンペーン完結・158 → 0 到達）。</p>
 * <ul>
 *   <li>schedule_attendances / fk_sa_proxy_record（proxy_input_record_id → proxy_input_records）</li>
 *   <li>survey_responses     / fk_sr_proxy_record（proxy_input_record_id → proxy_input_records）</li>
 * </ul>
 *
 * <p>本 PR-5d の特殊性は「参照先テーブル proxy_input_records が実際に物理削除される運用がある」点
 * （proxy=保持期限ジョブ/退会purge）。それでも参照元の外部キー列 proxy_input_record_id は
 * F14.1（代理入力・非デジタル住民対応）で追加された write-only / 不活性な監査列（getter ベースの逆引き finder / JPQL / JOIN は 0 件）であり、
 * 孤児化しても漏洩/NPE/誤集計が発生しないため撤廃only（孤児保持）が安全。本テストはその不変条件を厳密に検証する:</p>
 * <ol>
 *   <li>V118.001 の直前（origin/main 最大 = V117.001）まで適用 → 参照先 proxy_input_records 行 ＋ 参照元行
 *       （proxy_input_record_id に参照先 id をセット）をシード。</li>
 *   <li>残り（V118.001 含む）を適用しても既存データを壊さず成功する。</li>
 *   <li>V118.001 で対象2FKが撤廃される。</li>
 *   <li><b>参照先 proxy_input_records 行を（テスト内で）物理 DELETE しても RESTRICT が撤廃済みゆえブロックされず、
 *       参照元の proxy_input_record_id 列が孤児値を保持し続ける</b>（＝RESTRICT 撤廃only の肝・退会 purge 貫通の核心）。</li>
 * </ol>
 *
 * <p>方針: Spring を起動せず Testcontainers の実 MySQL 8.0 に {@link Flyway} を Java API で直接実行する。
 * Docker 未起動環境では {@code @EnabledIf} でスキップ（骨抜きにしない・根治原則）。
 * 物理削除する proxy_input_records は、本テストでは「他テーブルから ON DELETE RESTRICT で被参照される余計な子行」を
 * 一切シードしない（撤廃対象2FKの参照元のみをシード）。これにより proxy_input_records の物理 DELETE は阻まれず成立する。</p>
 *
 * <p>※ baseline が空になる最終局面のため、残存対照（他の生存 FK の sanity）は検証しない
 * （pre-state sanity は撤廃対象2FK 自身が V117.001 時点で実在することのみを確認する）。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.common.migration.FlywayExistingDataProxyRestrictFkMigrationTest#isDockerAvailable")
@DisplayName("Flyway 既存データ proxy_input_records 参照 RESTRICT FK撤廃（V118.001・キャンペーン完結）番人テスト")
class FlywayExistingDataProxyRestrictFkMigrationTest {

    /** V118.001 の直前の版（origin/main 最大 = V117.001）。ここまで適用して参照元/先をシードする。 */
    private static final String PRE_V118_001_TARGET = "117.001";

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_phase5d_proxy_fk")
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
    void startContainer() {
        MYSQL.start();
    }

    @AfterAll
    void stopContainer() {
        MYSQL.stop();
    }

    private Connection conn() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    private void migrateToPreTarget() {
        Flyway pre = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .target(MigrationVersion.fromVersion(PRE_V118_001_TARGET))
                .load();
        MigrateResult preResult = pre.migrate();
        assertThat(preResult.success).as("V117.001 までの適用が成功すること").isTrue();
    }

    private void migrateRemaining() {
        Flyway rest = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .load();
        MigrateResult restResult = rest.migrate();
        assertThat(restResult.success).as("V118.001 を含む残りのマイグレーションが成功すること").isTrue();
    }

    /**
     * 1回の pre→seed→migrate サイクルで2件すべてを検証する。
     *
     * <p>注意: 同一DBを共有するため複数 @Test に分けると、2本目以降は既に V118.001 まで適用済みとなり
     * 「FK実在 sanity（pre-state）」が成立しなくなる。よって1メソッドに集約し、
     * V117.001 時点での全2FK実在 → 2件ぶんのシード → 残り適用 → 全2FK撤廃 + 2件の孤児保持
     * を一気通貫で検証する。</p>
     */
    @Test
    @DisplayName("V117.001で全2FK実在_V118.001適用で全2FK撤廃_proxy_input_records物理削除でも参照元の外部キー列が孤児値を保持")
    void 既存データを持つDBでV118_001がproxy_RESTRICT_FK2件を撤廃onlyで安全に適用される() throws Exception {
        migrateToPreTarget();

        // 参照先（target）id
        final long proxyRecordForSchedule;
        final long proxyRecordForSurvey;

        // 参照元（referencing source）id ＋ 撤廃対象 FK 列にセットする値
        final long scheduleAttendanceId; // schedule_attendances.proxy_input_record_id = proxyRecordForSchedule
        final long surveyResponseId;     // survey_responses.proxy_input_record_id = proxyRecordForSurvey

        try (Connection c = conn()) {
            // ── given: V117.001 時点では対象2FKが全て実在すること（pre-state sanity）──
            assertThat(foreignKeyExists(c, "schedule_attendances", "fk_sa_proxy_record"))
                    .as("V117.001 時点では fk_sa_proxy_record が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "survey_responses", "fk_sr_proxy_record"))
                    .as("V117.001 時点では fk_sr_proxy_record が実在すること").isTrue();

            // ── 共通の親 ──
            long subjectUserId = insertUser(c, "p5d-subject@example.com");
            long proxyUserId = insertUser(c, "p5d-proxy@example.com");
            long organizationId = insertOrganization(c, "p5d-org-001");
            long teamId = insertTeam(c, "p5d-team-001");

            // ── proxy_input_records（参照先）を2本シード（各参照元ごとに独立検証するため別行）──
            // proxy_input_records は consent(RESTRICT)/subject_user(FK)/proxy_user(FK) と多数の NOT NULL 列を持つ。
            // UNIQUE uq_pir_idempotent(consent_id, target_entity_type, target_entity_id) を避けるため target を分ける。
            long consentId = insertProxyInputConsent(c, subjectUserId, proxyUserId, organizationId);
            proxyRecordForSchedule = insertProxyInputRecord(c, consentId, subjectUserId, proxyUserId, "SCHEDULE_ATTENDANCE", 2001L);
            proxyRecordForSurvey = insertProxyInputRecord(c, consentId, subjectUserId, proxyUserId, "SURVEY_RESPONSE", 2002L);

            // 1. schedule_attendances.proxy_input_record_id → proxy_input_records
            //    schedule_attendances は schedule_id(CASCADE)/user_id(NOT NULL・FK は Phase1-A V62.004 で撤廃済) NOT NULL ＋ uq_sa_schedule_user。
            long scheduleId = insertSchedule(c, teamId, subjectUserId);
            scheduleAttendanceId = insertScheduleAttendance(c, scheduleId, subjectUserId, proxyRecordForSchedule);

            // 2. survey_responses.proxy_input_record_id → proxy_input_records
            //    survey_responses は survey_id(CASCADE)/question_id(CASCADE)/user_id(NOT NULL・FK は Phase1-A V62.011 で撤廃済) NOT NULL。
            long surveyId = insertSurvey(c, teamId, subjectUserId);
            long surveyQuestionId = insertSurveyQuestion(c, surveyId);
            surveyResponseId = insertSurveyResponse(c, surveyId, surveyQuestionId, subjectUserId, proxyRecordForSurvey);
        }

        // ── when: 残り（V118.001 含む）を適用 ──
        migrateRemaining();

        try (Connection c = conn()) {
            // ── then-1: 対象2FKが全て撤廃された（baseline が空になる＝クロスドメインFK全廃）──
            assertThat(foreignKeyExists(c, "schedule_attendances", "fk_sa_proxy_record"))
                    .as("V118.001 で fk_sa_proxy_record が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "survey_responses", "fk_sr_proxy_record"))
                    .as("V118.001 で fk_sr_proxy_record が撤廃されること").isFalse();

            // ── then-2（中核）: proxy_input_records 行を物理削除しても、参照元の外部キー列が孤児値を保持 ──

            // 1. proxy_input_record 物理削除 → schedule_attendances.proxy_input_record_id 孤児保持
            deleteRow(c, "proxy_input_records", proxyRecordForSchedule);
            assertThat(rowExists(c, "proxy_input_records", proxyRecordForSchedule))
                    .as("参照先 proxy_input_record(schedule) が物理削除されたこと（RESTRICT 撤廃済ゆえブロックされない）").isFalse();
            assertThat(longColumn(c, "schedule_attendances", "proxy_input_record_id", scheduleAttendanceId))
                    .as("schedule_attendances.proxy_input_record_id が孤児値を保持すること")
                    .isEqualTo(proxyRecordForSchedule);

            // 2. proxy_input_record 物理削除 → survey_responses.proxy_input_record_id 孤児保持
            deleteRow(c, "proxy_input_records", proxyRecordForSurvey);
            assertThat(rowExists(c, "proxy_input_records", proxyRecordForSurvey))
                    .as("参照先 proxy_input_record(survey) が物理削除されたこと（RESTRICT 撤廃済ゆえブロックされない）").isFalse();
            assertThat(longColumn(c, "survey_responses", "proxy_input_record_id", surveyResponseId))
                    .as("survey_responses.proxy_input_record_id が孤児値を保持すること")
                    .isEqualTo(proxyRecordForSurvey);
        }
    }

    // ── 共通 seed helpers ──────────────────────────────────────

    private long insertUser(Connection c, String email) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO users
                    (email, last_name, first_name, display_name, status, created_at, updated_at)
                VALUES (?, '最終', '局面五D', '最終局面五D', 'ACTIVE', NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, email);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** organizations 行を挿入。slug は NOT NULL + UNIQUE / org_type は 'OTHER'。 */
    private long insertOrganization(Connection c, String slug) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO organizations
                    (name, org_type, slug, created_at, updated_at)
                VALUES ('最終局面五D監査組織', 'OTHER', ?, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, slug);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** teams 行を挿入。name NOT NULL / slug NOT NULL+UNIQUE（3〜30英数ハイフン）/ chk_teams_visibility。 */
    private long insertTeam(Connection c, String slug) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO teams
                    (name, slug, visibility, created_at, updated_at)
                VALUES ('最終局面五Dチーム', ?, 'PUBLIC', NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, slug);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    // ── proxy_input_consents / proxy_input_records ──

    /**
     * proxy_input_consents 行を挿入（proxy_input_records.proxy_input_consent_id RESTRICT の親）。
     * subject_user_id/proxy_user_id/organization_id/consent_method/effective_from/effective_until NOT NULL。
     */
    private long insertProxyInputConsent(Connection c, long subjectUserId, long proxyUserId, long organizationId)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO proxy_input_consents
                    (subject_user_id, proxy_user_id, organization_id, consent_method,
                     effective_from, effective_until, created_at, updated_at)
                VALUES (?, ?, ?, 'PAPER_SIGNED', CURDATE(), CURDATE() + INTERVAL 1 YEAR, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, subjectUserId);
            ps.setLong(2, proxyUserId);
            ps.setLong(3, organizationId);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /**
     * proxy_input_records 行を挿入（参照先）。
     * NOT NULL を全て充足: proxy_input_consent_id(RESTRICT)/subject_user_id(FK)/proxy_user_id(FK)/
     * feature_scope/target_entity_type/target_entity_id/input_source/original_storage_location。
     * STORED 生成列（retention_expires_at）は INSERT に含めない。
     * UNIQUE uq_pir_idempotent(consent_id, target_entity_type, target_entity_id) を避けるため target_entity_type を分ける。
     */
    private long insertProxyInputRecord(Connection c, long consentId, long subjectUserId, long proxyUserId,
                                        String targetEntityType, long targetEntityId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO proxy_input_records
                    (proxy_input_consent_id, subject_user_id, proxy_user_id, feature_scope,
                     target_entity_type, target_entity_id, input_source, original_storage_location, created_at)
                VALUES (?, ?, ?, 'SCHEDULE_ATTENDANCE', ?, ?, 'PAPER_FORM', '最終局面五D倉庫A-1', NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, consentId);
            ps.setLong(2, subjectUserId);
            ps.setLong(3, proxyUserId);
            ps.setString(4, targetEntityType);
            ps.setLong(5, targetEntityId);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    // ── 1. schedules / schedule_attendances ──

    /**
     * schedules 行を挿入（schedule_attendances.schedule_id CASCADE の親）。
     * title/start_at NOT NULL ＋ chk_schedule_scope（team_id XOR organization_id XOR user_id・本テストは team_id 採用）。
     */
    private long insertSchedule(Connection c, long teamId, long createdBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO schedules
                    (team_id, title, start_at, created_by, created_at, updated_at)
                VALUES (?, '最終局面五D予定', NOW(), ?, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, teamId);
            ps.setLong(2, createdBy);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /**
     * schedule_attendances 行を挿入。schedule_id(CASCADE)/user_id(NOT NULL・FK は V62.004 で撤廃済)/status NOT NULL
     * ＋ uq_sa_schedule_user(schedule_id, user_id)。proxy_input_record_id（撤廃対象列・FK）。
     */
    private long insertScheduleAttendance(Connection c, long scheduleId, long userId, long proxyRecordId)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO schedule_attendances
                    (schedule_id, user_id, status, is_proxy_input, proxy_input_record_id, created_at, updated_at)
                VALUES (?, ?, 'ATTEND', 1, ?, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, scheduleId);
            ps.setLong(2, userId);
            ps.setLong(3, proxyRecordId);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    // ── 2. surveys / survey_questions / survey_responses ──

    /** surveys 行を挿入（survey_questions/survey_responses.survey_id CASCADE の親）。scope_type/scope_id/title NOT NULL。 */
    private long insertSurvey(Connection c, long teamId, long createdBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO surveys
                    (scope_type, scope_id, title, status, created_by, created_at, updated_at)
                VALUES ('TEAM', ?, '最終局面五Dアンケート', 'PUBLISHED', ?, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, teamId);
            ps.setLong(2, createdBy);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** survey_questions 行を挿入（survey_responses.question_id CASCADE の親）。survey_id(CASCADE)/question_type/question_text NOT NULL。 */
    private long insertSurveyQuestion(Connection c, long surveyId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO survey_questions
                    (survey_id, question_type, question_text, created_at)
                VALUES (?, 'SINGLE_CHOICE', '最終局面五D設問', NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, surveyId);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /**
     * survey_responses 行を挿入。survey_id(CASCADE)/question_id(CASCADE)/user_id(NOT NULL・FK は V62.011 で撤廃済) NOT NULL。
     * option_id は任意（FK SET NULL・NULL 可）。proxy_input_record_id（撤廃対象列・FK）。
     */
    private long insertSurveyResponse(Connection c, long surveyId, long questionId, long userId, long proxyRecordId)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO survey_responses
                    (survey_id, question_id, user_id, text_response, is_proxy_input, proxy_input_record_id,
                     created_at, updated_at)
                VALUES (?, ?, ?, '最終局面五D回答', 1, ?, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, surveyId);
            ps.setLong(2, questionId);
            ps.setLong(3, userId);
            ps.setLong(4, proxyRecordId);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    // ── generic helpers ────────────────────────────────────────

    private void deleteRow(Connection c, String table, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + table + " WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    private static boolean rowExists(Connection c, String table, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM " + table + " WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1) > 0;
            }
        }
    }

    private static long longColumn(Connection c, String table, String column, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT " + column + " FROM " + table + " WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                long v = rs.getLong(1);
                return rs.wasNull() ? -1L : v;
            }
        }
    }

    private static boolean foreignKeyExists(Connection c, String table, String constraintName)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT COUNT(*) FROM information_schema.table_constraints
                WHERE table_schema = DATABASE()
                  AND table_name = ?
                  AND constraint_name = ?
                  AND constraint_type = 'FOREIGN KEY'
                """)) {
            ps.setString(1, table);
            ps.setString(2, constraintName);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }
}
