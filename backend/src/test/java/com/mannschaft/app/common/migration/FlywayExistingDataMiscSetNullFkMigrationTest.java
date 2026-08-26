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
 * <b>クロスドメインFK撤廃 第四陣C（Phase 4-C）の番人テスト。</b>
 *
 * <p>V111.001 で「他ドメインの実テーブル（surveys / activity_results / budget_transactions / incidents /
 * reservation_lines / projects / team_templates / confirmable_notifications）を ON DELETE SET NULL で参照する
 * 群2＝構造参照のクロスドメインFK 8件」を撤廃only する。本テストが守る不変条件は次の通り:</p>
 * <ol>
 *   <li>V111.001 の直前（V110.001）まで適用 → 参照先行＋参照元行（外部キー列に参照先 id をセット）をシード。</li>
 *   <li>残り（V111.001 含む）を適用しても既存データを壊さず成功する。</li>
 *   <li>V111.001 で対象8FKが撤廃される。</li>
 *   <li><b>参照先テーブルの行を（テスト内で意図的に）物理 DELETE しても、参照元の外部キー列が NULL 化されず
 *       孤児値を保持し続ける</b>（＝SET NULL 撤廃only の肝）。
 *       本番では参照先テーブル（surveys / activity_results / budget_transactions / incidents / reservation_lines /
 *       projects / team_templates）はいずれも論理削除（deleted_at + @SQLRestriction / softDelete）のみで、
 *       confirmable_notifications は status 遷移（CANCELLED/EXPIRED/COMPLETED）のみで物理削除されない
 *       （＝SET NULL 発火不能）。撤廃後の SET-NULL 不在を直接実証するためテスト内では物理 DELETE で検証する。</li>
 * </ol>
 *
 * <p>方針: Spring を起動せず Testcontainers の実 MySQL 8.0 に {@link Flyway} を Java API で直接実行する。
 * Docker 未起動環境では {@code @EnabledIf} でスキップ（骨抜きにしない・根治原則）。
 * 物理削除する参照先テーブルは、本テストでは「他テーブルから ON DELETE RESTRICT で被参照される子行」を
 * 一切シードしない（撤廃対象8FKの参照元のみをシードし、全て SET NULL なので削除を阻害しない）。
 * これにより参照先の物理 DELETE は RESTRICT に阻まれず成立する。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.common.migration.FlywayExistingDataMiscSetNullFkMigrationTest#isDockerAvailable")
@DisplayName("Flyway 既存データ survey/activity/budget_tx/incident/reservation_line/project/team_template/confirmable 参照 SET NULL FK撤廃（V111.001）番人テスト")
class FlywayExistingDataMiscSetNullFkMigrationTest {

    /** V111.001 の直前の版（origin/main 最大 = V110.001）。ここまで適用して参照元/先をシードする。 */
    private static final String PRE_V111_001_TARGET = "110.001";

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_phase4c_setnull_fk")
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
                .target(MigrationVersion.fromVersion(PRE_V111_001_TARGET))
                .load();
        MigrateResult preResult = pre.migrate();
        assertThat(preResult.success).as("V110.001 までの適用が成功すること").isTrue();
    }

    private void migrateRemaining() {
        Flyway rest = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .load();
        MigrateResult restResult = rest.migrate();
        assertThat(restResult.success).as("V111.001 を含む残りのマイグレーションが成功すること").isTrue();
    }

    /**
     * 1回の pre→seed→migrate サイクルで8件すべてを検証する。
     *
     * <p>注意: 同一DBを共有するため複数 @Test に分けると、2本目以降は既に V111.001 まで適用済みとなり
     * 「FK実在 sanity（pre-state）」が成立しなくなる。よって1メソッドに集約し、
     * V110.001 時点での全8FK実在 → 8件ぶんのシード → 残り適用 → 全8FK撤廃 + 8件の孤児保持
     * を一気通貫で検証する。</p>
     */
    @Test
    @DisplayName("V110.001で全8FK実在_V111.001適用で全8FK撤廃_参照先物理削除でも参照元の外部キー列が孤児値を保持")
    void 既存データを持つDBでV111_001が群2SET_NULL_FK8件を撤廃onlyで安全に適用される() throws Exception {
        migrateToPreTarget();

        // 参照先（target）id
        final long surveyId;
        final long activityResultId;
        final long budgetTxId;
        final long incidentId;
        final long reservationLineId;
        final long projectId;
        final long teamTemplateId;
        final long confirmableId;

        // 参照元（referencing source）id ＋ 撤廃対象 FK 列にセットする値
        final long eventId;            // events.pre_survey_id = surveyId
        final long perfRecordId;       // performance_records.activity_result_id = activityResultId
        final long workPackageBtId;    // property_work_packages.budget_transaction_id = budgetTxId
        final long workPackageIncId;   // property_work_packages.incident_id = incidentId
        final long recruitmentId;      // recruitment_listings.reservation_line_id = reservationLineId
        final long shiftScheduleId;    // shift_schedules.linked_project_id = projectId
        final long teamId;             // teams.template_id = teamTemplateId
        final long distLogId;          // committee_distribution_logs.confirmable_notification_id = confirmableId

        try (Connection c = conn()) {
            // ── given: V110.001 時点では対象8FKが全て実在すること（pre-state sanity）──
            assertThat(foreignKeyExists(c, "events", "fk_events_pre_survey"))
                    .as("V110.001 時点では fk_events_pre_survey が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "performance_records", "fk_pr_activity"))
                    .as("V110.001 時点では fk_pr_activity が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "property_work_packages", "fk_pwp_budget_tx"))
                    .as("V110.001 時点では fk_pwp_budget_tx が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "property_work_packages", "fk_pwp_incident"))
                    .as("V110.001 時点では fk_pwp_incident が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "recruitment_listings", "fk_rl_reservation_line"))
                    .as("V110.001 時点では fk_rl_reservation_line が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "shift_schedules", "fk_ss_linked_project"))
                    .as("V110.001 時点では fk_ss_linked_project が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "teams", "fk_teams_template"))
                    .as("V110.001 時点では fk_teams_template が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "committee_distribution_logs", "fk_cdl_confirmable"))
                    .as("V110.001 時点では fk_cdl_confirmable が実在すること").isTrue();

            // ── 共通の親 ──
            long ownerUserId = insertUser(c, "p4c-owner@example.com");
            long organizationId = insertOrganization(c, "p4c-org-001");
            long teamForRefs = insertTeam(c, "p4c-team-refs", null); // reservation_lines / shift_schedules / performance_metrics 用（template_id 無し）

            // ── 1. events.pre_survey_id → surveys ──
            // surveys は ORGANIZATION スコープ（scope_type/scope_id/title NOT NULL）。
            surveyId = insertSurvey(c, organizationId);
            // events は schedule_id(RESTRICT・UNIQUE) と scope/slug NOT NULL。ORGANIZATION スコープの schedule を親に作る。
            long eventScheduleId = insertOrgSchedule(c, organizationId, ownerUserId);
            eventId = insertEvent(c, organizationId, eventScheduleId, surveyId);

            // ── 2. performance_records.activity_result_id → activity_results ──
            // activity_results は template_id(RESTRICT activity_templates) と scope/title/activity_date NOT NULL。
            long activityTemplateId = insertActivityTemplate(c, teamForRefs, ownerUserId);
            activityResultId = insertActivityResult(c, teamForRefs, activityTemplateId);
            // performance_records は metric_id(RESTRICT performance_metrics) と user_id(RESTRICT users)。
            long metricId = insertPerformanceMetric(c, teamForRefs);
            perfRecordId = insertPerformanceRecord(c, metricId, ownerUserId, activityResultId);

            // ── 3+4. property_work_packages.budget_transaction_id → budget_transactions / incident_id → incidents ──
            // budget chain: fiscal_year(RESTRICT) → category(RESTRICT) → transaction。
            long fiscalYearId = insertBudgetFiscalYear(c, organizationId, ownerUserId);
            long budgetCategoryId = insertBudgetCategory(c, fiscalYearId);
            budgetTxId = insertBudgetTransaction(c, fiscalYearId, budgetCategoryId, organizationId, ownerUserId);
            incidentId = insertIncident(c, organizationId, ownerUserId);
            // pwp は ORGANIZATION スコープ＋created_by(RESTRICT)。budget_transaction_id 検証用と incident_id 検証用に
            // それぞれ別行をシード（同一行に両方セットしてもよいが、撤廃対象FK列ごとに独立検証するため分離）。
            workPackageBtId = insertPropertyWorkPackage(c, organizationId, ownerUserId, budgetTxId, null);
            workPackageIncId = insertPropertyWorkPackage(c, organizationId, ownerUserId, null, incidentId);

            // ── 5. recruitment_listings.reservation_line_id → reservation_lines ──
            // reservation_lines は team_id(CASCADE)＋name。
            reservationLineId = insertReservationLine(c, teamForRefs);
            // recruitment_listings は ORGANIZATION スコープ＋category_id(RESTRICT)＋created_by(RESTRICT)＋日付CHECK。
            long recruitmentCategoryId = insertRecruitmentCategory(c);
            recruitmentId = insertRecruitmentListing(c, organizationId, recruitmentCategoryId, ownerUserId, reservationLineId);

            // ── 6. shift_schedules.linked_project_id → projects ──
            // projects は ORGANIZATION スコープ＋title＋created_by（FK制約なし）。
            projectId = insertProject(c, organizationId, ownerUserId);
            // shift_schedules は team_id(CASCADE)＋title＋start_date/end_date。
            shiftScheduleId = insertShiftSchedule(c, teamForRefs, projectId);

            // ── 7. teams.template_id → team_templates ──
            // team_templates は name＋slug(UNIQUE)。teams は name＋slug(UNIQUE 3〜30英数ハイフン)。
            teamTemplateId = insertTeamTemplate(c, "p4c-tpl-slug");
            teamId = insertTeam(c, "p4c-team-tpl", teamTemplateId);

            // ── 8. committee_distribution_logs.confirmable_notification_id → confirmable_notifications ──
            // confirmable_notifications は ORGANIZATION スコープ＋title。
            confirmableId = insertConfirmableNotification(c, organizationId);
            // committee_distribution_logs は committee_id(CASCADE committees)＋content_type/target_scope/
            // announcement_enabled/confirmation_mode NOT NULL。
            long committeeId = insertCommittee(c, organizationId);
            distLogId = insertCommitteeDistributionLog(c, committeeId, confirmableId);
        }

        // ── when: 残り（V111.001 含む）を適用 ──
        migrateRemaining();

        try (Connection c = conn()) {
            // ── then-1: 対象8FKが全て撤廃された ──
            assertThat(foreignKeyExists(c, "events", "fk_events_pre_survey"))
                    .as("V111.001 で fk_events_pre_survey が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "performance_records", "fk_pr_activity"))
                    .as("V111.001 で fk_pr_activity が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "property_work_packages", "fk_pwp_budget_tx"))
                    .as("V111.001 で fk_pwp_budget_tx が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "property_work_packages", "fk_pwp_incident"))
                    .as("V111.001 で fk_pwp_incident が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "recruitment_listings", "fk_rl_reservation_line"))
                    .as("V111.001 で fk_rl_reservation_line が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "shift_schedules", "fk_ss_linked_project"))
                    .as("V111.001 で fk_ss_linked_project が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "teams", "fk_teams_template"))
                    .as("V111.001 で fk_teams_template が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "committee_distribution_logs", "fk_cdl_confirmable"))
                    .as("V111.001 で fk_cdl_confirmable が撤廃されること").isFalse();

            // ── then-2（中核）: 各参照先テーブルの行を物理削除しても、参照元の外部キー列が NULL 化されず孤児値を保持 ──

            // 1. survey 物理削除 → events.pre_survey_id 孤児保持
            deleteRow(c, "surveys", surveyId);
            assertThat(rowExists(c, "surveys", surveyId)).as("参照先 survey が物理削除されたこと").isFalse();
            assertThat(longColumn(c, "events", "pre_survey_id", eventId))
                    .as("events.pre_survey_id が SET NULL されず孤児 survey_id を保持すること").isEqualTo(surveyId);

            // 2. activity_result 物理削除 → performance_records.activity_result_id 孤児保持
            deleteRow(c, "activity_results", activityResultId);
            assertThat(rowExists(c, "activity_results", activityResultId)).as("参照先 activity_result が物理削除されたこと").isFalse();
            assertThat(longColumn(c, "performance_records", "activity_result_id", perfRecordId))
                    .as("performance_records.activity_result_id が SET NULL されず孤児 activity_result_id を保持すること").isEqualTo(activityResultId);

            // 3. budget_transaction 物理削除 → property_work_packages.budget_transaction_id 孤児保持
            deleteRow(c, "budget_transactions", budgetTxId);
            assertThat(rowExists(c, "budget_transactions", budgetTxId)).as("参照先 budget_transaction が物理削除されたこと").isFalse();
            assertThat(longColumn(c, "property_work_packages", "budget_transaction_id", workPackageBtId))
                    .as("property_work_packages.budget_transaction_id が SET NULL されず孤児 budget_tx_id を保持すること").isEqualTo(budgetTxId);

            // 4. incident 物理削除 → property_work_packages.incident_id 孤児保持
            deleteRow(c, "incidents", incidentId);
            assertThat(rowExists(c, "incidents", incidentId)).as("参照先 incident が物理削除されたこと").isFalse();
            assertThat(longColumn(c, "property_work_packages", "incident_id", workPackageIncId))
                    .as("property_work_packages.incident_id が SET NULL されず孤児 incident_id を保持すること").isEqualTo(incidentId);

            // 5. reservation_line 物理削除 → recruitment_listings.reservation_line_id 孤児保持
            deleteRow(c, "reservation_lines", reservationLineId);
            assertThat(rowExists(c, "reservation_lines", reservationLineId)).as("参照先 reservation_line が物理削除されたこと").isFalse();
            assertThat(longColumn(c, "recruitment_listings", "reservation_line_id", recruitmentId))
                    .as("recruitment_listings.reservation_line_id が SET NULL されず孤児 reservation_line_id を保持すること").isEqualTo(reservationLineId);

            // 6. project 物理削除 → shift_schedules.linked_project_id 孤児保持
            deleteRow(c, "projects", projectId);
            assertThat(rowExists(c, "projects", projectId)).as("参照先 project が物理削除されたこと").isFalse();
            assertThat(longColumn(c, "shift_schedules", "linked_project_id", shiftScheduleId))
                    .as("shift_schedules.linked_project_id が SET NULL されず孤児 project_id を保持すること").isEqualTo(projectId);

            // 7. team_template 物理削除 → teams.template_id 孤児保持
            deleteRow(c, "team_templates", teamTemplateId);
            assertThat(rowExists(c, "team_templates", teamTemplateId)).as("参照先 team_template が物理削除されたこと").isFalse();
            assertThat(longColumn(c, "teams", "template_id", teamId))
                    .as("teams.template_id が SET NULL されず孤児 template_id を保持すること").isEqualTo(teamTemplateId);

            // 8. confirmable_notification 物理削除 → committee_distribution_logs.confirmable_notification_id 孤児保持
            deleteRow(c, "confirmable_notifications", confirmableId);
            assertThat(rowExists(c, "confirmable_notifications", confirmableId)).as("参照先 confirmable_notification が物理削除されたこと").isFalse();
            assertThat(longColumn(c, "committee_distribution_logs", "confirmable_notification_id", distLogId))
                    .as("committee_distribution_logs.confirmable_notification_id が SET NULL されず孤児 confirmable_id を保持すること").isEqualTo(confirmableId);
        }
    }

    // ── 共通 seed helpers ──────────────────────────────────────

    private long insertUser(Connection c, String email) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO users
                    (email, last_name, first_name, display_name, status, created_at, updated_at)
                VALUES (?, '第四', 'C郎', '第四C郎', 'ACTIVE', NOW(), NOW())
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
                VALUES ('第四陣C監査組織', 'OTHER', ?, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, slug);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** teams 行を挿入。name NOT NULL / slug NOT NULL+UNIQUE（3〜30英数ハイフン）/ chk_teams_visibility。template_id は任意。 */
    private long insertTeam(Connection c, String slug, Long templateId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO teams
                    (name, slug, visibility, template_id, created_at, updated_at)
                VALUES ('第四陣Cチーム', ?, 'PUBLIC', ?, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, slug);
            if (templateId == null) {
                ps.setNull(2, java.sql.Types.BIGINT);
            } else {
                ps.setLong(2, templateId);
            }
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    // ── 1. surveys / events ──

    /** surveys 行を挿入。scope_type/scope_id/title NOT NULL を満たす（ORGANIZATION スコープ）。 */
    private long insertSurvey(Connection c, long organizationId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO surveys
                    (scope_type, scope_id, title, created_at, updated_at)
                VALUES ('ORGANIZATION', ?, '第四陣Cアンケート', NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, organizationId);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** ORGANIZATION スコープの schedule（chk_schedule_scope: organization_id のみ非NULL）を挿入。events.schedule_id(RESTRICT・UNIQUE) の親。 */
    private long insertOrgSchedule(Connection c, long organizationId, long createdBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO schedules
                    (organization_id, title, start_at, event_type, status, created_by, created_at, updated_at)
                VALUES (?, '第四陣Cイベント予定', NOW() + INTERVAL 1 DAY, 'OTHER', 'SCHEDULED', ?, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, organizationId);
            ps.setLong(2, createdBy);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** events 行を挿入。scope_type/scope_id/slug(UNIQUE) NOT NULL / schedule_id(RESTRICT・UNIQUE) / pre_survey_id(撤廃対象列)。 */
    private long insertEvent(Connection c, long organizationId, long scheduleId, long preSurveyId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO events
                    (scope_type, scope_id, schedule_id, slug, pre_survey_id, created_at, updated_at)
                VALUES ('ORGANIZATION', ?, ?, 'p4c-event-slug', ?, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, organizationId);
            ps.setLong(2, scheduleId);
            ps.setLong(3, preSurveyId);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    // ── 2. activity_results / performance_records ──

    /** activity_templates 行を挿入（activity_results.template_id RESTRICT の親）。scope/name/created_by(RESTRICT) NOT NULL。 */
    private long insertActivityTemplate(Connection c, long teamId, long createdBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO activity_templates
                    (scope_type, scope_id, name, created_by, created_at, updated_at)
                VALUES ('TEAM', ?, '第四陣C活動テンプレ', ?, NOW(), NOW())
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

    /** activity_results 行を挿入。scope/template_id(RESTRICT)/title/activity_date NOT NULL。 */
    private long insertActivityResult(Connection c, long teamId, long templateId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO activity_results
                    (scope_type, scope_id, template_id, title, activity_date, created_at, updated_at)
                VALUES ('TEAM', ?, ?, '第四陣C活動結果', CURDATE(), NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, teamId);
            ps.setLong(2, templateId);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** performance_metrics 行を挿入（performance_records.metric_id RESTRICT の親）。team_id(FK)/name NOT NULL。 */
    private long insertPerformanceMetric(Connection c, long teamId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO performance_metrics
                    (team_id, name, created_at, updated_at)
                VALUES (?, '第四陣C指標', NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, teamId);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** performance_records 行を挿入。metric_id(RESTRICT)/user_id(RESTRICT)/recorded_date/value NOT NULL。activity_result_id(撤廃対象列)。 */
    private long insertPerformanceRecord(Connection c, long metricId, long userId, long activityResultId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO performance_records
                    (metric_id, user_id, recorded_date, value, activity_result_id, created_at, updated_at)
                VALUES (?, ?, CURDATE(), 12.3400, ?, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, metricId);
            ps.setLong(2, userId);
            ps.setLong(3, activityResultId);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    // ── 3+4. budget_transactions / incidents / property_work_packages ──

    /** budget_fiscal_years 行を挿入。scope/name/dates/status/created_by(RESTRICT) NOT NULL。 */
    private long insertBudgetFiscalYear(Connection c, long organizationId, long createdBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO budget_fiscal_years
                    (scope_type, scope_id, name, start_date, end_date, status, created_by, created_at, updated_at)
                VALUES ('ORGANIZATION', ?, '第四陣C年度', '2020-04-01', '2021-03-31', 'OPEN', ?, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, organizationId);
            ps.setLong(2, createdBy);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** budget_categories 行を挿入。fiscal_year_id(RESTRICT)/name/category_type CHECK(INCOME/EXPENSE) NOT NULL。 */
    private long insertBudgetCategory(Connection c, long fiscalYearId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO budget_categories
                    (fiscal_year_id, name, category_type, sort_order, created_at, updated_at)
                VALUES (?, '第四陣C費目', 'EXPENSE', 0, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, fiscalYearId);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /**
     * budget_transactions 行を挿入（property_work_packages.budget_transaction_id の参照先）。
     * CHECK: chk_bt_transaction_type(INCOME/EXPENSE)/chk_bt_approval_status/chk_bt_scope_type(TEAM/ORGANIZATION) を満たす。
     */
    private long insertBudgetTransaction(Connection c, long fiscalYearId, long categoryId,
                                         long organizationId, long recordedBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO budget_transactions
                    (fiscal_year_id, category_id, scope_type, scope_id, transaction_type, amount, transaction_date,
                     title, approval_status, recorded_by, created_at, updated_at)
                VALUES (?, ?, 'ORGANIZATION', ?, 'EXPENSE', 5000, CURDATE(),
                        '第四陣C取引', 'APPROVED', ?, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, fiscalYearId);
            ps.setLong(2, categoryId);
            ps.setLong(3, organizationId);
            ps.setLong(4, recordedBy);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** incidents 行を挿入。scope_type/scope_id/title/reported_by(RESTRICT)/created_at/updated_at NOT NULL。 */
    private long insertIncident(Connection c, long organizationId, long reportedBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO incidents
                    (scope_type, scope_id, title, reported_by, created_at, updated_at)
                VALUES ('ORGANIZATION', ?, '第四陣Cインシデント', ?, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, organizationId);
            ps.setLong(2, reportedBy);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /**
     * property_work_packages 行を挿入。ORGANIZATION スコープ＋created_by(RESTRICT)。
     * CHECK: chk_pwp_scope_type(TEAM/ORGANIZATION)/chk_pwp_work_type/chk_pwp_status/chk_pwp_visibility/chk_pwp_currency。
     * budget_transaction_id / incident_id（いずれも撤廃対象列）を任意でセット。
     */
    private long insertPropertyWorkPackage(Connection c, long organizationId, long createdBy,
                                           Long budgetTransactionId, Long incidentId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO property_work_packages
                    (scope_type, scope_id, work_type, title, currency, is_disclosable,
                     visibility, status, attachment_count, comment_count,
                     budget_transaction_id, incident_id, created_by, created_at, updated_at)
                VALUES ('ORGANIZATION', ?, 'REPAIR', '第四陣C工事', 'JPY', 1,
                        'ADMINS_ONLY', 'PLANNED', 0, 0, ?, ?, ?, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, organizationId);
            if (budgetTransactionId == null) {
                ps.setNull(2, java.sql.Types.BIGINT);
            } else {
                ps.setLong(2, budgetTransactionId);
            }
            if (incidentId == null) {
                ps.setNull(3, java.sql.Types.BIGINT);
            } else {
                ps.setLong(3, incidentId);
            }
            ps.setLong(4, createdBy);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    // ── 5. reservation_lines / recruitment_listings ──

    /** reservation_lines 行を挿入。team_id(CASCADE)/name NOT NULL。 */
    private long insertReservationLine(Connection c, long teamId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO reservation_lines
                    (team_id, name, created_at, updated_at)
                VALUES (?, '第四陣C予約ライン', NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, teamId);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** recruitment_categories 行を挿入（recruitment_listings.category_id RESTRICT の親）。code は NOT NULL+UNIQUE。 */
    private long insertRecruitmentCategory(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO recruitment_categories
                    (code, name_i18n_key, default_participation_type, display_order, is_active, created_at, updated_at)
                VALUES ('P4C_CAT', 'recruitment.category.p4c', 'INDIVIDUAL', 0, TRUE, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /**
     * recruitment_listings 行を挿入。NOT NULL/CHECK を全て充足:
     * scope / category_id(RESTRICT) / title / participation_type /
     * 日付4列（CHECK: auto_cancel <= deadline < start < end）/ capacity >= min_capacity / created_by(RESTRICT) /
     * reservation_line_id（撤廃対象列）。
     */
    private long insertRecruitmentListing(Connection c, long organizationId, long categoryId, long createdBy,
                                          long reservationLineId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO recruitment_listings
                    (scope_type, scope_id, category_id, title, participation_type,
                     start_at, end_at, application_deadline, auto_cancel_at,
                     capacity, min_capacity, created_by, reservation_line_id, created_at, updated_at)
                VALUES ('ORGANIZATION', ?, ?, '第四陣C募集', 'INDIVIDUAL',
                        NOW() + INTERVAL 10 DAY, NOW() + INTERVAL 11 DAY, NOW() + INTERVAL 5 DAY, NOW() + INTERVAL 4 DAY,
                        10, 1, ?, ?, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, organizationId);
            ps.setLong(2, categoryId);
            ps.setLong(3, createdBy);
            ps.setLong(4, reservationLineId);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    // ── 6. projects / shift_schedules ──

    /** projects 行を挿入。scope_type/scope_id/title/created_by(FK制約なし) NOT NULL。 */
    private long insertProject(Connection c, long organizationId, long createdBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO projects
                    (scope_type, scope_id, title, created_by, created_at, updated_at)
                VALUES ('ORGANIZATION', ?, '第四陣Cプロジェクト', ?, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, organizationId);
            ps.setLong(2, createdBy);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** shift_schedules 行を挿入。team_id(CASCADE)/title/start_date/end_date NOT NULL。linked_project_id（撤廃対象列）。 */
    private long insertShiftSchedule(Connection c, long teamId, long linkedProjectId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO shift_schedules
                    (team_id, title, start_date, end_date, linked_project_id, created_at, updated_at)
                VALUES (?, '第四陣Cシフト表', CURDATE(), CURDATE() + INTERVAL 7 DAY, ?, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, teamId);
            ps.setLong(2, linkedProjectId);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    // ── 7. team_templates ──

    /** team_templates 行を挿入（teams.template_id の参照先）。name/slug(UNIQUE)/created_at/updated_at NOT NULL。 */
    private long insertTeamTemplate(Connection c, String slug) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO team_templates
                    (name, slug, created_at, updated_at)
                VALUES ('第四陣Cチームテンプレ', ?, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, slug);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    // ── 8. confirmable_notifications / committees / committee_distribution_logs ──

    /** confirmable_notifications 行を挿入。scope_type(ENUM TEAM/ORGANIZATION)/scope_id/title NOT NULL。 */
    private long insertConfirmableNotification(Connection c, long organizationId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO confirmable_notifications
                    (scope_type, scope_id, title, created_at, updated_at)
                VALUES ('ORGANIZATION', ?, '第四陣C確認付き通知', NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, organizationId);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** committees 行を挿入（committee_distribution_logs.committee_id CASCADE の親）。organization_id(CASCADE)/name NOT NULL。 */
    private long insertCommittee(Connection c, long organizationId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO committees
                    (organization_id, name, created_at, updated_at)
                VALUES (?, '第四陣C委員会', NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, organizationId);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /**
     * committee_distribution_logs 行を挿入。committee_id(CASCADE)/content_type/target_scope/
     * announcement_enabled/confirmation_mode NOT NULL。confirmable_notification_id（撤廃対象列）をセット。
     */
    private long insertCommitteeDistributionLog(Connection c, long committeeId, long confirmableNotificationId)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO committee_distribution_logs
                    (committee_id, content_type, target_scope, announcement_enabled, confirmation_mode,
                     confirmable_notification_id, created_at)
                VALUES (?, 'CUSTOM_MESSAGE', 'COMMITTEE_ONLY', TRUE, 'OPTIONAL', ?, NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, committeeId);
            ps.setLong(2, confirmableNotificationId);
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
