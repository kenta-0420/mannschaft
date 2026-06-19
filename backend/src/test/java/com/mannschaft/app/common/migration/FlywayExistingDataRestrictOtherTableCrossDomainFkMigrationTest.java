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
 * <b>クロスドメインFK撤廃 最終局面 5-B（Phase 5-B・第二弾）の番人テスト。</b>
 *
 * <p>V115.001 で「残った RESTRICT のうち、参照先が org/team 以外の他ドメイン実テーブルを参照する11件」を
 * 撤廃only する。参照先テーブルは venues / module_definitions / electronic_seals / shared_files / schedules /
 * budget_categories / budget_fiscal_years / projects / shift_schedules / shift_slots の10種で、いずれも
 * 本番では deleted_at による論理削除のみ（venues は不変マスタ・shift_slots は親 shift_schedules 追従）で
 * 物理 DELETE 経路を持たない。したがって既定の ON DELETE RESTRICT は現実には発火し得ず、FK を撤廃しても
 * 挙動は一切変わらない。</p>
 *
 * <p>本テストは「もし参照先が（テスト内で意図的に）物理削除されても、参照元の外部キー列が
 * 削除/NULL化されず孤児値を保持し続ける」ことを厳密に検証し、撤廃only（孤児保持）の肝を直接守る:</p>
 * <ol>
 *   <li>V115.001 の直前（origin/main 最大 = V114.001）まで適用 → 参照先行＋参照元行（外部キー列に参照先 id をセット）をシード。</li>
 *   <li>残り（V115.001 含む）を適用しても既存データを壊さず成功する。</li>
 *   <li>V115.001 で対象11FKが撤廃される。</li>
 *   <li><b>参照先テーブルの行を（テスト内で）物理 DELETE しても、参照元の外部キー列が孤児値を保持し続ける</b>
 *       （RESTRICT による親 DELETE 阻止が起きない）。</li>
 * </ol>
 *
 * <p>方針: Spring を起動せず Testcontainers の実 MySQL 8.0 に {@link Flyway} を Java API で直接実行する。
 * Docker 未起動環境では {@code @EnabledIf} でスキップ（骨抜きにしない・根治原則）。
 * 物理削除する参照先テーブルは、当該行を ON DELETE RESTRICT で被参照する「撤廃対象FK以外の余計な子行」を
 * 一切シードしない。撤廃対象11FKの参照元のみをシードし、それらは V115.001 適用後には FK が消えているため、
 * 参照先の物理 DELETE を阻害しない。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.common.migration.FlywayExistingDataRestrictOtherTableCrossDomainFkMigrationTest#isDockerAvailable")
@DisplayName("Flyway 既存データ RESTRICT→他テーブルクロスドメインFK撤廃（V115.001・Phase 5-B）番人テスト")
class FlywayExistingDataRestrictOtherTableCrossDomainFkMigrationTest {

    /** V115.001 の直前の版（origin/main 最大 = V114.001）。ここまで適用して参照元/先をシードする。 */
    private static final String PRE_V115_001_TARGET = "114.001";

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_phase5b_restrict_fk")
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
                .target(MigrationVersion.fromVersion(PRE_V115_001_TARGET))
                .load();
        MigrateResult preResult = pre.migrate();
        assertThat(preResult.success).as("V114.001 までの適用が成功すること").isTrue();
    }

    private void migrateRemaining() {
        Flyway rest = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .load();
        MigrateResult restResult = rest.migrate();
        assertThat(restResult.success).as("V115.001 を含む残りのマイグレーションが成功すること").isTrue();
    }

    /**
     * 1回の pre→seed→migrate サイクルで11件すべてを検証する。
     *
     * <p>注意: 同一DBを共有するため複数 @Test に分けると、2本目以降は既に V115.001 まで適用済みとなり
     * 「FK実在 sanity（pre-state）」が成立しなくなる。よって1メソッドに集約し、
     * V114.001 時点での全11FK実在 → 11件ぶんのシード → 残り適用 → 全11FK撤廃 + 11件の孤児保持
     * を一気通貫で検証する。</p>
     */
    @Test
    @DisplayName("V114.001で全11FK実在_V115.001適用で全11FK撤廃_参照先物理削除でも参照元の外部キー列が孤児値を保持")
    void 既存データを持つDBでV115_001がRESTRICT他テーブルクロスドメインFK11件を撤廃onlyで安全に適用される() throws Exception {
        migrateToPreTarget();

        // 参照元（referencing source）id ＋ 撤廃対象 FK 列にセットする値（参照先 id）
        final long arId;                // activity_results.venue_id
        final long venueForAr;          // → venues（物理削除対象・ar 用）
        final long schId;               // schedules.venue_id
        final long venueForSch;         // → venues（物理削除対象・sch 用）
        final long admId;               // analytics_daily_modules.module_id
        final long moduleId;            // → module_definitions（物理削除対象）
        final long cifId;               // chart_intake_forms.electronic_seal_id
        final long sealId;              // → electronic_seals（物理削除対象）
        final long deId;                // disclosure_exports.shared_file_id
        final long sharedFileForDe;     // → shared_files（物理削除対象）
        final long eventId;             // events.schedule_id
        final long scheduleForEvent;    // → schedules（物理削除対象）
        final long sbaId;               // shift_budget_allocations.{budget_category_id, fiscal_year_id, project_id}
        final long budgetCategoryId;    // → budget_categories（物理削除対象）
        final long fiscalYearId;        // → budget_fiscal_years（物理削除対象）
        final long projectId;           // → projects（物理削除対象）
        final long sbcId;               // shift_budget_consumptions.{shift_id, slot_id}
        final long shiftScheduleId;     // → shift_schedules（物理削除対象）
        final long shiftSlotId;         // → shift_slots（物理削除対象）

        try (Connection c = conn()) {
            // ── given: V114.001 時点では対象11FKが全て実在すること（pre-state sanity）──
            assertThat(foreignKeyExists(c, "activity_results", "fk_ar_venue"))
                    .as("V114.001 時点では fk_ar_venue が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "schedules", "fk_sch_venue"))
                    .as("V114.001 時点では fk_sch_venue が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "analytics_daily_modules", "fk_analytics_daily_modules_module"))
                    .as("V114.001 時点では fk_analytics_daily_modules_module が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "chart_intake_forms", "fk_cif_seal"))
                    .as("V114.001 時点では fk_cif_seal が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "disclosure_exports", "fk_de_shared_file"))
                    .as("V114.001 時点では fk_de_shared_file が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "events", "fk_events_schedule"))
                    .as("V114.001 時点では fk_events_schedule が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "shift_budget_allocations", "fk_sba_budget_category"))
                    .as("V114.001 時点では fk_sba_budget_category が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "shift_budget_allocations", "fk_sba_fiscal_year"))
                    .as("V114.001 時点では fk_sba_fiscal_year が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "shift_budget_allocations", "fk_sba_project"))
                    .as("V114.001 時点では fk_sba_project が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "shift_budget_consumptions", "fk_sbc_shift"))
                    .as("V114.001 時点では fk_sbc_shift が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "shift_budget_consumptions", "fk_sbc_slot"))
                    .as("V114.001 時点では fk_sbc_slot が実在すること").isTrue();

            // ── 共通の親 ──
            long actorUserId = insertUser(c, "p5b-actor@example.com");
            long orgParent = insertOrganization(c, "p5b-org-parent");       // 各種 ORGANIZATION スコープ親（物理削除しない）
            long teamParent = insertTeam(c, "p5b-team-parent");             // 各種 TEAM スコープ親（物理削除しない）

            // ── 1. activity_results → venues ──
            // venues: name NOT NULL（マスタ・物理削除対象）。
            venueForAr = insertVenue(c, "最終局面5B会場-ar");
            // activity_results: scope_type/scope_id/template_id/title/activity_date NOT NULL。venue_id（撤廃対象列）をセット。
            long templateId = insertActivityTemplate(c, "ORGANIZATION", orgParent, actorUserId);
            arId = insertActivityResult(c, "ORGANIZATION", orgParent, templateId, venueForAr);

            // ── 2. schedules → venues ──
            venueForSch = insertVenue(c, "最終局面5B会場-sch");
            // schedules: title/start_at NOT NULL ＋ chk_schedule_scope（team/org/user の1つのみ）→ team スコープ。venue_id（撤廃対象列）をセット。
            schId = insertScheduleWithVenue(c, teamParent, venueForSch);

            // ── 3. analytics_daily_modules → module_definitions ──
            // module_definitions: name/slug/module_type/module_number/created_at/updated_at NOT NULL ＋ chk_module_type。
            moduleId = insertModuleDefinition(c, "p5b-mod");
            // analytics_daily_modules: date/module_id NOT NULL ＋ UNIQUE uk_date_module。module_id（撤廃対象列）をセット。
            admId = insertAnalyticsDailyModule(c, moduleId);

            // ── 4. chart_intake_forms → electronic_seals ──
            // electronic_seals: user_id(CASCADE)/variant/display_text/svg_data/seal_hash NOT NULL ＋ UNIQUE(user_id, variant)。
            sealId = insertElectronicSeal(c, actorUserId);
            // chart_records: team_id(RESTRICT)/customer_user_id(RESTRICT)/visit_date NOT NULL（chart_intake_forms.chart_record_id CASCADE の親）。
            long chartRecordId = insertChartRecord(c, teamParent, actorUserId);
            // chart_intake_forms: chart_record_id(CASCADE)/form_type/content NOT NULL。electronic_seal_id（撤廃対象列）をセット。
            cifId = insertChartIntakeForm(c, chartRecordId, sealId);

            // ── 5. disclosure_exports → shared_files ──
            // shared_files: folder_id(CASCADE)/name/file_key/file_size/content_type/created_at/updated_at NOT NULL。
            long sharedFolderId = insertSharedFolder(c, "ORGANIZATION", orgParent, actorUserId);
            sharedFileForDe = insertSharedFile(c, sharedFolderId);
            // disclosure_form_templates: code/name/version/form_schema/created_at/updated_at NOT NULL ＋ chk_dft_system_scope（system なら scope NULL）。
            long disclosureTemplateId = insertDisclosureFormTemplate(c, "p5b-dft-code");
            // disclosure_exports: scope_type='ORGANIZATION'/scope_id/template_id/template_code_snapshot/template_version_snapshot/
            //   output_format/shared_file_id/requester_user_id/data_snapshot/created_at NOT NULL。shared_file_id（撤廃対象列）をセット。
            deId = insertDisclosureExport(c, orgParent, disclosureTemplateId, sharedFileForDe, actorUserId);

            // ── 6. events → schedules ──
            // schedules（events 用・物理削除対象）: org スコープでシード。
            scheduleForEvent = insertScheduleOrgScope(c, orgParent);
            // events: scope_type/scope_id/slug NOT NULL ＋ UNIQUE uq_events_schedule。schedule_id（撤廃対象列）をセット。
            eventId = insertEvent(c, "ORGANIZATION", orgParent, scheduleForEvent, "p5b-event-slug");

            // ── 7+8+9. shift_budget_allocations → budget_categories / budget_fiscal_years / projects ──
            // budget chain: budget_fiscal_years → budget_categories。
            fiscalYearId = insertBudgetFiscalYear(c, "ORGANIZATION", orgParent, actorUserId);
            budgetCategoryId = insertBudgetCategory(c, fiscalYearId);
            projectId = insertProject(c, "ORGANIZATION", orgParent, actorUserId);
            // shift_budget_allocations: organization_id(CASCADE)/fiscal_year_id/budget_category_id/period_start/period_end/
            //   allocated_amount/created_by NOT NULL ＋ CHECK。budget_category_id/fiscal_year_id/project_id（3撤廃対象列）を全てセット。
            sbaId = insertShiftBudgetAllocation(c, orgParent, fiscalYearId, budgetCategoryId, projectId, actorUserId);

            // ── 10+11. shift_budget_consumptions → shift_schedules / shift_slots ──
            // shift_schedules: team_id(CASCADE)/title/start_date/end_date NOT NULL。
            shiftScheduleId = insertShiftSchedule(c, teamParent);
            // shift_slots: schedule_id(CASCADE)/slot_date/start_time/end_time NOT NULL。
            shiftSlotId = insertShiftSlot(c, shiftScheduleId);
            // shift_budget_consumptions: allocation_id(RESTRICT)/shift_id/slot_id/user_id/hourly_rate_snapshot/hours/amount NOT NULL ＋ CHECK。
            //   shift_id/slot_id（2撤廃対象列）をセット。allocation_id は上記 sbaId を流用。
            sbcId = insertShiftBudgetConsumption(c, sbaId, shiftScheduleId, shiftSlotId, actorUserId);
        }

        // ── when: 残り（V115.001 含む）を適用 ──
        migrateRemaining();

        try (Connection c = conn()) {
            // ── then-1: 対象11FKが全て撤廃された ──
            assertThat(foreignKeyExists(c, "activity_results", "fk_ar_venue"))
                    .as("V115.001 で fk_ar_venue が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "schedules", "fk_sch_venue"))
                    .as("V115.001 で fk_sch_venue が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "analytics_daily_modules", "fk_analytics_daily_modules_module"))
                    .as("V115.001 で fk_analytics_daily_modules_module が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "chart_intake_forms", "fk_cif_seal"))
                    .as("V115.001 で fk_cif_seal が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "disclosure_exports", "fk_de_shared_file"))
                    .as("V115.001 で fk_de_shared_file が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "events", "fk_events_schedule"))
                    .as("V115.001 で fk_events_schedule が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "shift_budget_allocations", "fk_sba_budget_category"))
                    .as("V115.001 で fk_sba_budget_category が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "shift_budget_allocations", "fk_sba_fiscal_year"))
                    .as("V115.001 で fk_sba_fiscal_year が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "shift_budget_allocations", "fk_sba_project"))
                    .as("V115.001 で fk_sba_project が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "shift_budget_consumptions", "fk_sbc_shift"))
                    .as("V115.001 で fk_sbc_shift が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "shift_budget_consumptions", "fk_sbc_slot"))
                    .as("V115.001 で fk_sbc_slot が撤廃されること").isFalse();

            // ── then-2（中核）: 各参照先テーブルの行を物理削除しても、参照元の外部キー列が孤児値を保持 ──

            // 1. venues(ar) 物理削除 → activity_results.venue_id 孤児保持
            deleteRow(c, "venues", venueForAr);
            assertThat(rowExists(c, "venues", venueForAr))
                    .as("参照先 venue(ar) が物理削除されたこと").isFalse();
            assertThat(rowExists(c, "activity_results", arId))
                    .as("venue 物理削除でも activity_results が生存すること").isTrue();
            assertThat(longColumn(c, "activity_results", "venue_id", arId))
                    .as("activity_results.venue_id が孤児値を保持すること").isEqualTo(venueForAr);

            // 2. venues(sch) 物理削除 → schedules.venue_id 孤児保持
            deleteRow(c, "venues", venueForSch);
            assertThat(rowExists(c, "venues", venueForSch))
                    .as("参照先 venue(sch) が物理削除されたこと").isFalse();
            assertThat(rowExists(c, "schedules", schId))
                    .as("venue 物理削除でも schedules が生存すること").isTrue();
            assertThat(longColumn(c, "schedules", "venue_id", schId))
                    .as("schedules.venue_id が孤児値を保持すること").isEqualTo(venueForSch);

            // 3. module_definitions 物理削除 → analytics_daily_modules.module_id 孤児保持
            deleteRow(c, "module_definitions", moduleId);
            assertThat(rowExists(c, "module_definitions", moduleId))
                    .as("参照先 module_definition が物理削除されたこと").isFalse();
            assertThat(rowExists(c, "analytics_daily_modules", admId))
                    .as("module 物理削除でも analytics_daily_modules が生存すること").isTrue();
            assertThat(longColumn(c, "analytics_daily_modules", "module_id", admId))
                    .as("analytics_daily_modules.module_id が孤児値を保持すること").isEqualTo(moduleId);

            // 4. electronic_seals 物理削除 → chart_intake_forms.electronic_seal_id 孤児保持
            deleteRow(c, "electronic_seals", sealId);
            assertThat(rowExists(c, "electronic_seals", sealId))
                    .as("参照先 electronic_seal が物理削除されたこと").isFalse();
            assertThat(rowExists(c, "chart_intake_forms", cifId))
                    .as("seal 物理削除でも chart_intake_forms が生存すること").isTrue();
            assertThat(longColumn(c, "chart_intake_forms", "electronic_seal_id", cifId))
                    .as("chart_intake_forms.electronic_seal_id が孤児値を保持すること").isEqualTo(sealId);

            // 5. shared_files 物理削除 → disclosure_exports.shared_file_id 孤児保持
            deleteRow(c, "shared_files", sharedFileForDe);
            assertThat(rowExists(c, "shared_files", sharedFileForDe))
                    .as("参照先 shared_file が物理削除されたこと").isFalse();
            assertThat(rowExists(c, "disclosure_exports", deId))
                    .as("shared_file 物理削除でも disclosure_exports が生存すること").isTrue();
            assertThat(longColumn(c, "disclosure_exports", "shared_file_id", deId))
                    .as("disclosure_exports.shared_file_id が孤児値を保持すること").isEqualTo(sharedFileForDe);

            // 6. schedules(event 用) 物理削除 → events.schedule_id 孤児保持
            deleteRow(c, "schedules", scheduleForEvent);
            assertThat(rowExists(c, "schedules", scheduleForEvent))
                    .as("参照先 schedule(event) が物理削除されたこと").isFalse();
            assertThat(rowExists(c, "events", eventId))
                    .as("schedule 物理削除でも events が生存すること").isTrue();
            assertThat(longColumn(c, "events", "schedule_id", eventId))
                    .as("events.schedule_id が孤児値を保持すること").isEqualTo(scheduleForEvent);

            // 7. budget_categories 物理削除 → shift_budget_allocations.budget_category_id 孤児保持
            deleteRow(c, "budget_categories", budgetCategoryId);
            assertThat(rowExists(c, "budget_categories", budgetCategoryId))
                    .as("参照先 budget_category が物理削除されたこと").isFalse();
            assertThat(rowExists(c, "shift_budget_allocations", sbaId))
                    .as("budget_category 物理削除でも shift_budget_allocations が生存すること").isTrue();
            assertThat(longColumn(c, "shift_budget_allocations", "budget_category_id", sbaId))
                    .as("shift_budget_allocations.budget_category_id が孤児値を保持すること").isEqualTo(budgetCategoryId);

            // 8. budget_fiscal_years 物理削除 → shift_budget_allocations.fiscal_year_id 孤児保持
            deleteRow(c, "budget_fiscal_years", fiscalYearId);
            assertThat(rowExists(c, "budget_fiscal_years", fiscalYearId))
                    .as("参照先 budget_fiscal_year が物理削除されたこと").isFalse();
            assertThat(longColumn(c, "shift_budget_allocations", "fiscal_year_id", sbaId))
                    .as("shift_budget_allocations.fiscal_year_id が孤児値を保持すること").isEqualTo(fiscalYearId);

            // 9. projects 物理削除 → shift_budget_allocations.project_id 孤児保持
            deleteRow(c, "projects", projectId);
            assertThat(rowExists(c, "projects", projectId))
                    .as("参照先 project が物理削除されたこと").isFalse();
            assertThat(longColumn(c, "shift_budget_allocations", "project_id", sbaId))
                    .as("shift_budget_allocations.project_id が孤児値を保持すること").isEqualTo(projectId);

            // 10. shift_slots 物理削除 → shift_budget_consumptions.slot_id 孤児保持
            //   （slot を先に削除する。shift_slots は shift_schedules を親に持つため、親 shift_schedules を
            //    先に削除すると CASCADE で shift_slots も巻き添えになる。順序を slot → schedule とする。）
            deleteRow(c, "shift_slots", shiftSlotId);
            assertThat(rowExists(c, "shift_slots", shiftSlotId))
                    .as("参照先 shift_slot が物理削除されたこと").isFalse();
            assertThat(rowExists(c, "shift_budget_consumptions", sbcId))
                    .as("shift_slot 物理削除でも shift_budget_consumptions が生存すること").isTrue();
            assertThat(longColumn(c, "shift_budget_consumptions", "slot_id", sbcId))
                    .as("shift_budget_consumptions.slot_id が孤児値を保持すること").isEqualTo(shiftSlotId);

            // 11. shift_schedules 物理削除 → shift_budget_consumptions.shift_id 孤児保持
            deleteRow(c, "shift_schedules", shiftScheduleId);
            assertThat(rowExists(c, "shift_schedules", shiftScheduleId))
                    .as("参照先 shift_schedule が物理削除されたこと").isFalse();
            assertThat(rowExists(c, "shift_budget_consumptions", sbcId))
                    .as("shift_schedule 物理削除でも shift_budget_consumptions が生存すること").isTrue();
            assertThat(longColumn(c, "shift_budget_consumptions", "shift_id", sbcId))
                    .as("shift_budget_consumptions.shift_id が孤児値を保持すること").isEqualTo(shiftScheduleId);
        }
    }

    // ── 共通 seed helpers ──────────────────────────────────────

    private long insertUser(Connection c, String email) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO users
                    (email, last_name, first_name, display_name, status, created_at, updated_at)
                VALUES (?, '最終', '5B郎', '最終5B郎', 'ACTIVE', NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, email);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /** organizations 行を挿入。slug NOT NULL+UNIQUE（3〜30英数ハイフン）/ org_type は VARCHAR(30)（'OTHER'）。 */
    private long insertOrganization(Connection c, String slug) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO organizations
                    (name, org_type, slug, created_at, updated_at)
                VALUES ('最終局面5B監査組織', 'OTHER', ?, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, slug);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /** teams 行を挿入。name NOT NULL / slug NOT NULL+UNIQUE / chk_teams_visibility（'PUBLIC'）。 */
    private long insertTeam(Connection c, String slug) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO teams
                    (name, slug, visibility, created_at, updated_at)
                VALUES ('最終局面5Bチーム', ?, 'PUBLIC', NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, slug);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    // ── 1+2. venues / activity_templates / activity_results / schedules ──

    /** venues 行を挿入（参照先・マスタ）。name NOT NULL。残りは NULL 許容 or DEFAULT あり。 */
    private long insertVenue(Connection c, String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO venues (name)
                VALUES (?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /** activity_templates 行を挿入（activity_results.template_id の親）。scope_type/scope_id/name/created_by NOT NULL。 */
    private long insertActivityTemplate(Connection c, String scopeType, long scopeId, long createdBy)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO activity_templates (scope_type, scope_id, name, created_by)
                VALUES (?, ?, '最終局面5Bテンプレート', ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, scopeType);
            ps.setLong(2, scopeId);
            ps.setLong(3, createdBy);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /** activity_results 行を挿入。scope_type/scope_id/template_id/title/activity_date NOT NULL。venue_id（撤廃対象列）をセット。 */
    private long insertActivityResult(Connection c, String scopeType, long scopeId, long templateId, long venueId)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO activity_results
                    (scope_type, scope_id, template_id, title, activity_date, venue_id)
                VALUES (?, ?, ?, '最終局面5B活動記録', '2026-06-19', ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, scopeType);
            ps.setLong(2, scopeId);
            ps.setLong(3, templateId);
            ps.setLong(4, venueId);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /** schedules 行を挿入（team スコープ）。title/start_at NOT NULL ＋ chk_schedule_scope。venue_id（撤廃対象列）をセット。 */
    private long insertScheduleWithVenue(Connection c, long teamId, long venueId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO schedules (team_id, title, start_at, venue_id)
                VALUES (?, '最終局面5B予定', NOW(), ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, teamId);
            ps.setLong(2, venueId);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /** schedules 行を挿入（org スコープ・events の参照先用・物理削除対象）。title/start_at NOT NULL ＋ chk_schedule_scope。 */
    private long insertScheduleOrgScope(Connection c, long organizationId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO schedules (organization_id, title, start_at)
                VALUES (?, '最終局面5Bイベント予定', NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, organizationId);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    // ── 3. module_definitions / analytics_daily_modules ──

    /** module_definitions 行を挿入（参照先）。name/slug/module_type/module_number/created_at/updated_at NOT NULL ＋ chk_module_type。 */
    private long insertModuleDefinition(Connection c, String slug) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO module_definitions
                    (name, slug, module_type, module_number, created_at, updated_at)
                VALUES ('最終局面5Bモジュール', ?, 'OPTIONAL', 9101, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, slug);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /** analytics_daily_modules 行を挿入。date/module_id NOT NULL ＋ UNIQUE uk_date_module。module_id（撤廃対象列）をセット。 */
    private long insertAnalyticsDailyModule(Connection c, long moduleId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO analytics_daily_modules (date, module_id)
                VALUES ('2026-06-19', ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, moduleId);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    // ── 4. electronic_seals / chart_records / chart_intake_forms ──

    /** electronic_seals 行を挿入（参照先）。user_id(CASCADE)/variant/display_text/svg_data/seal_hash NOT NULL ＋ UNIQUE(user_id, variant)。 */
    private long insertElectronicSeal(Connection c, long userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO electronic_seals
                    (user_id, variant, display_text, svg_data, seal_hash)
                VALUES (?, 'ROUND', '最終5B', '<svg/>', 'abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789')
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, userId);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /** chart_records 行を挿入（chart_intake_forms.chart_record_id CASCADE の親）。team_id(RESTRICT)/customer_user_id(RESTRICT)/visit_date NOT NULL。 */
    private long insertChartRecord(Connection c, long teamId, long customerUserId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO chart_records (team_id, customer_user_id, visit_date)
                VALUES (?, ?, '2026-06-19')
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, teamId);
            ps.setLong(2, customerUserId);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /** chart_intake_forms 行を挿入。chart_record_id(CASCADE)/form_type/content NOT NULL。electronic_seal_id（撤廃対象列）をセット。 */
    private long insertChartIntakeForm(Connection c, long chartRecordId, long electronicSealId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO chart_intake_forms (chart_record_id, form_type, content, electronic_seal_id)
                VALUES (?, 'INTAKE', '{}', ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, chartRecordId);
            ps.setLong(2, electronicSealId);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    // ── 5. shared_folders / shared_files / disclosure_form_templates / disclosure_exports ──

    /** shared_folders 行を挿入（shared_files.folder_id CASCADE の親）。scope_type/name/created_by/created_at/updated_at NOT NULL。 */
    private long insertSharedFolder(Connection c, String scopeType, long orgId, long createdBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO shared_folders
                    (scope_type, organization_id, name, created_by, created_at, updated_at)
                VALUES (?, ?, '最終局面5Bフォルダ', ?, NOW(6), NOW(6))
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, scopeType);
            ps.setLong(2, orgId);
            ps.setLong(3, createdBy);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /** shared_files 行を挿入（参照先）。folder_id(CASCADE)/name/file_key/file_size/content_type/created_at/updated_at NOT NULL。 */
    private long insertSharedFile(Connection c, long folderId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO shared_files
                    (folder_id, name, file_key, file_size, content_type, created_at, updated_at)
                VALUES (?, '最終局面5B.pdf', 'p5b/key/file.pdf', 2048, 'application/pdf', NOW(6), NOW(6))
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, folderId);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /**
     * disclosure_form_templates 行を挿入（disclosure_exports.template_id RESTRICT の親）。
     * code/name/version/form_schema/created_at/updated_at NOT NULL ＋ chk_dft_system_scope
     * （is_system_template=1 なら scope NULL）。system template として挿入（scope 不要）。
     */
    private long insertDisclosureFormTemplate(Connection c, String code) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO disclosure_form_templates
                    (code, name, version, is_system_template, form_schema, created_at, updated_at)
                VALUES (?, '最終局面5B様式', 'v1', 1, '{}', NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, code);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /**
     * disclosure_exports 行を挿入。scope_type='ORGANIZATION'/scope_id/template_id/template_code_snapshot/
     * template_version_snapshot/output_format/shared_file_id/requester_user_id/data_snapshot/created_at NOT NULL
     * ＋ chk_de_scope_type / chk_de_output_format。shared_file_id（撤廃対象列）をセット。
     */
    private long insertDisclosureExport(Connection c, long scopeId, long templateId, long sharedFileId, long requesterUserId)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO disclosure_exports
                    (scope_type, scope_id, template_id, template_code_snapshot, template_version_snapshot,
                     output_format, shared_file_id, requester_user_id, data_snapshot, created_at)
                VALUES ('ORGANIZATION', ?, ?, 'p5b-dft-code', 'v1', 'PDF', ?, ?, '{}', NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, scopeId);
            ps.setLong(2, templateId);
            ps.setLong(3, sharedFileId);
            ps.setLong(4, requesterUserId);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    // ── 6. events ──

    /** events 行を挿入。scope_type/scope_id/slug NOT NULL ＋ UNIQUE uq_events_schedule。schedule_id（撤廃対象列）をセット。 */
    private long insertEvent(Connection c, String scopeType, long scopeId, long scheduleId, String slug)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO events (scope_type, scope_id, schedule_id, slug)
                VALUES (?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, scopeType);
            ps.setLong(2, scopeId);
            ps.setLong(3, scheduleId);
            ps.setString(4, slug);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    // ── 7+8+9. budget chain / projects / shift_budget_allocations ──

    /** budget_fiscal_years 行を挿入（参照先＋budget_categories の親）。scope/name/start_date/end_date/created_by NOT NULL ＋ CHECK(start<end)。 */
    private long insertBudgetFiscalYear(Connection c, String scopeType, long scopeId, long createdBy)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO budget_fiscal_years
                    (scope_type, scope_id, name, start_date, end_date, created_by)
                VALUES (?, ?, '最終局面5B年度', '2026-04-01', '2027-03-31', ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, scopeType);
            ps.setLong(2, scopeId);
            ps.setLong(3, createdBy);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /** budget_categories 行を挿入（参照先）。fiscal_year_id(RESTRICT)/name/category_type NOT NULL ＋ chk_bc_category_type。 */
    private long insertBudgetCategory(Connection c, long fiscalYearId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO budget_categories (fiscal_year_id, name, category_type)
                VALUES (?, '最終局面5B費目', 'EXPENSE')
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, fiscalYearId);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /** projects 行を挿入（参照先）。scope_type/scope_id/title/created_by NOT NULL。status/visibility は DEFAULT あり。 */
    private long insertProject(Connection c, String scopeType, long scopeId, long createdBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO projects (scope_type, scope_id, title, created_by)
                VALUES (?, ?, '最終局面5Bプロジェクト', ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, scopeType);
            ps.setLong(2, scopeId);
            ps.setLong(3, createdBy);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /**
     * shift_budget_allocations 行を挿入（shift_budget_consumptions.allocation_id RESTRICT の親も兼ねる）。
     * organization_id(CASCADE)/fiscal_year_id(撤廃対象)/budget_category_id(撤廃対象)/period_start/period_end/
     * allocated_amount/created_by NOT NULL ＋ CHECK(amount>=0 / period_start<=period_end)。
     * project_id（撤廃対象・NULL 許容）にも値をセットして3撤廃対象列を全て埋める。
     */
    private long insertShiftBudgetAllocation(Connection c, long organizationId, long fiscalYearId,
                                             long budgetCategoryId, long projectId, long createdBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO shift_budget_allocations
                    (organization_id, fiscal_year_id, budget_category_id, project_id, period_start, period_end,
                     allocated_amount, created_by)
                VALUES (?, ?, ?, ?, '2026-04-01', '2026-04-30', 100000, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, organizationId);
            ps.setLong(2, fiscalYearId);
            ps.setLong(3, budgetCategoryId);
            ps.setLong(4, projectId);
            ps.setLong(5, createdBy);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    // ── 10+11. shift_schedules / shift_slots / shift_budget_consumptions ──

    /** shift_schedules 行を挿入（参照先＋shift_slots の親）。team_id(CASCADE)/title/start_date/end_date NOT NULL。 */
    private long insertShiftSchedule(Connection c, long teamId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO shift_schedules (team_id, title, start_date, end_date)
                VALUES (?, '最終局面5Bシフト表', '2026-04-01', '2026-04-30')
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, teamId);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /** shift_slots 行を挿入（参照先）。schedule_id(CASCADE)/slot_date/start_time/end_time NOT NULL。 */
    private long insertShiftSlot(Connection c, long scheduleId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO shift_slots (schedule_id, slot_date, start_time, end_time)
                VALUES (?, '2026-04-05', '09:00:00', '18:00:00')
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, scheduleId);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    /**
     * shift_budget_consumptions 行を挿入。allocation_id(RESTRICT)/shift_id(撤廃対象)/slot_id(撤廃対象)/user_id/
     * hourly_rate_snapshot/hours/amount NOT NULL ＋ CHECK(amount>=0 / hours>=0)。
     * shift_id/slot_id（2撤廃対象列）をセット。
     */
    private long insertShiftBudgetConsumption(Connection c, long allocationId, long shiftId, long slotId, long userId)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO shift_budget_consumptions
                    (allocation_id, shift_id, slot_id, user_id, hourly_rate_snapshot, hours, amount)
                VALUES (?, ?, ?, ?, 1200.00, 8.00, 9600.00)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, allocationId);
            ps.setLong(2, shiftId);
            ps.setLong(3, slotId);
            ps.setLong(4, userId);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    // ── generic helpers ────────────────────────────────────────

    private static long generatedId(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.getGeneratedKeys()) {
            rs.next();
            return rs.getLong(1);
        }
    }

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
