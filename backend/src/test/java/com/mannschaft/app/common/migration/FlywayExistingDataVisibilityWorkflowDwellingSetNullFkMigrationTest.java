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
 * <b>クロスドメインFK撤廃 第四陣B（Phase 4-B）の番人テスト。</b>
 *
 * <p>V110.001 で「他ドメインの実テーブル（visibility_templates / workflow_templates / workflow_requests /
 * dwelling_units）を ON DELETE SET NULL で参照する群2＝構造参照のクロスドメインFK 10件」を撤廃only する。
 * 本テストが守る不変条件は、参照先テーブルのグループ単位（visibility / workflow / dwelling）で次を検証する:</p>
 * <ol>
 *   <li>V110.001 の直前（V109.001）まで適用 → 参照先行＋参照元行（外部キー列に参照先 id をセット）をシード。</li>
 *   <li>残り（V110.001 含む）を適用しても既存データを壊さず成功する。</li>
 *   <li>V110.001 で対象10FKが撤廃される。</li>
 *   <li><b>参照先テーブルの行を（テスト内で意図的に）物理 DELETE しても、参照元の外部キー列が NULL 化されず
 *       孤児値を保持し続ける</b>（＝SET NULL 撤廃only の肝）。
 *       本番では参照先テーブル（workflow_templates / workflow_requests / dwelling_units）はいずれも論理削除のみで
 *       物理削除されない（＝SET NULL 発火不能）。visibility_templates は owner_user_id CASCADE で物理削除され得るが、
 *       VisibilityTemplateEvaluator が findById empty→拒否(fail-closed)のため孤児でも権限漏洩なし。
 *       いずれにせよ撤廃後の SET-NULL 不在を直接実証するためテスト内では物理 DELETE で検証する。</li>
 * </ol>
 *
 * <p>方針: Spring を起動せず Testcontainers の実 MySQL 8.0 に {@link Flyway} を Java API で直接実行する。
 * Docker 未起動環境では {@code @EnabledIf} でスキップ（骨抜きにしない・根治原則）。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.common.migration.FlywayExistingDataVisibilityWorkflowDwellingSetNullFkMigrationTest#isDockerAvailable")
@DisplayName("Flyway 既存データ visibility_templates/workflow/dwelling_units 参照 SET NULL FK撤廃（V110.001）番人テスト")
class FlywayExistingDataVisibilityWorkflowDwellingSetNullFkMigrationTest {

    /** V110.001 の直前の版（origin/main 最大 = V109.001）。ここまで適用して参照元/先をシードする。 */
    private static final String PRE_V110_001_TARGET = "109.001";

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_phase4b_setnull_fk")
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
                .target(MigrationVersion.fromVersion(PRE_V110_001_TARGET))
                .load();
        MigrateResult preResult = pre.migrate();
        assertThat(preResult.success).as("V109.001 までの適用が成功すること").isTrue();
    }

    private void migrateRemaining() {
        Flyway rest = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .load();
        MigrateResult restResult = rest.migrate();
        assertThat(restResult.success).as("V110.001 を含む残りのマイグレーションが成功すること").isTrue();
    }

    /**
     * 1回の pre→seed→migrate サイクルで visibility / workflow / dwelling の3参照先グループすべてを検証する。
     *
     * <p>注意: 同一DBを共有するため複数 @Test に分けると、2本目以降は既に V110.001 まで適用済みとなり
     * 「FK実在 sanity（pre-state）」が成立しなくなる。よって1メソッドに集約し、
     * V109.001 時点での全10FK実在 → 3グループぶんのシード → 残り適用 → 全10FK撤廃 + 3グループの孤児保持
     * を一気通貫で検証する。</p>
     */
    @Test
    @DisplayName("V109.001で全10FK実在_V110.001適用で全10FK撤廃_visibility/workflow/dwelling物理削除でも参照元の外部キー列が孤児値を保持")
    void 既存データを持つDBでV110_001が群2SET_NULL_FK10件を撤廃onlyで安全に適用される() throws Exception {
        migrateToPreTarget();

        final long vtId;            // visibility_template（参照先）
        final long blogPostId;      // blog_posts.visibility_template_id = vtId
        final long recruitmentId;   // recruitment_listings.visibility_template_id = vtId
        final long scheduleId;      // schedules.visibility_template_id = vtId

        final long workflowTemplateId; // workflow_templates（参照先）
        final long workflowRequestId;  // workflow_requests（参照先）
        final long budgetConfigId;     // budget_configs.workflow_template_id = workflowTemplateId / over_limit_workflow_id = workflowTemplateId
        final long btaId;              // budget_threshold_alerts.workflow_request_id = workflowRequestId
        final long budgetTxId;         // budget_transactions.workflow_request_id = workflowRequestId

        final long dwellingUnitId;     // dwelling_units（参照先）
        final long disclosureExportId; // disclosure_exports.target_dwelling_unit_id = dwellingUnitId
        final long disclosureDraftId;  // disclosure_form_drafts.target_dwelling_unit_id = dwellingUnitId
        final long workPackageId;      // property_work_packages.dwelling_unit_id = dwellingUnitId

        try (Connection c = conn()) {
            // ── given: V109.001 時点では対象10FKが全て実在すること（pre-state sanity）──
            assertThat(foreignKeyExists(c, "blog_posts", "fk_blog_posts_vt"))
                    .as("V109.001 時点では fk_blog_posts_vt が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "recruitment_listings", "fk_recruitment_listings_vt"))
                    .as("V109.001 時点では fk_recruitment_listings_vt が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "schedules", "fk_schedules_vt"))
                    .as("V109.001 時点では fk_schedules_vt が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "budget_configs", "fk_bconf_over_limit_workflow"))
                    .as("V109.001 時点では fk_bconf_over_limit_workflow が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "budget_configs", "fk_bconf_workflow_template"))
                    .as("V109.001 時点では fk_bconf_workflow_template が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "budget_threshold_alerts", "fk_bta_workflow_request"))
                    .as("V109.001 時点では fk_bta_workflow_request が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "budget_transactions", "fk_bt_workflow_request"))
                    .as("V109.001 時点では fk_bt_workflow_request が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "disclosure_exports", "fk_de_dwelling"))
                    .as("V109.001 時点では fk_de_dwelling が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "disclosure_form_drafts", "fk_dfd_dwelling"))
                    .as("V109.001 時点では fk_dfd_dwelling が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "property_work_packages", "fk_pwp_dwelling"))
                    .as("V109.001 時点では fk_pwp_dwelling が実在すること").isTrue();

            // ── 共通の親 ──
            long ownerUserId = insertUser(c, "p4b-owner@example.com");
            long organizationId = insertOrganization(c, "p4b-org-001");

            // ── seed group-1: visibility_templates 参照 ──
            // user 定義テンプレ（is_system_preset=FALSE → owner_user_id NOT NULL / preset_key NULL）。
            vtId = insertVisibilityTemplate(c, ownerUserId);
            // user スコープの blog_post（chk_bp_scope: user_id のみ非NULL）+ visibility_template_id
            blogPostId = insertBlogPost(c, ownerUserId, vtId);
            // ORGANIZATION スコープの recruitment_listing + visibility_template_id
            recruitmentId = insertRecruitmentListing(c, organizationId, ownerUserId, vtId);
            // user スコープの schedule（chk_schedule_scope: user_id のみ非NULL）+ visibility_template_id
            scheduleId = insertUserSchedule(c, ownerUserId, vtId);

            // ── seed group-2: workflow_templates / workflow_requests 参照 ──
            workflowTemplateId = insertWorkflowTemplate(c, organizationId, ownerUserId);
            workflowRequestId = insertWorkflowRequest(c, workflowTemplateId, organizationId, ownerUserId);
            // budget_configs: workflow_template_id と over_limit_workflow_id の両方を同一テンプレに紐付け
            budgetConfigId = insertBudgetConfig(c, organizationId, workflowTemplateId);
            // budget chain（threshold_alert / transaction が workflow_request を参照する）
            long fiscalYearId = insertBudgetFiscalYear(c, organizationId, ownerUserId);
            long budgetCategoryId = insertBudgetCategory(c, fiscalYearId);
            long allocationId = insertShiftBudgetAllocation(c, organizationId, fiscalYearId, budgetCategoryId, ownerUserId);
            btaId = insertBudgetThresholdAlert(c, allocationId, workflowRequestId);
            budgetTxId = insertBudgetTransaction(c, fiscalYearId, budgetCategoryId, ownerUserId, workflowRequestId);

            // ── seed group-3: dwelling_units 参照 ──
            // ORGANIZATION スコープの dwelling_unit
            dwellingUnitId = insertDwellingUnit(c, organizationId);
            // disclosure 系（scope_type='ORGANIZATION' のみ許可）
            long templateId = insertDisclosureFormTemplate(c, organizationId, ownerUserId);
            long sharedFileId = insertSharedFile(c, organizationId, ownerUserId);
            disclosureDraftId = insertDisclosureFormDraft(c, organizationId, templateId, dwellingUnitId, ownerUserId);
            disclosureExportId = insertDisclosureExport(c, organizationId, templateId, sharedFileId, dwellingUnitId, ownerUserId);
            // property_work_package（ORGANIZATION スコープ）
            workPackageId = insertPropertyWorkPackage(c, organizationId, dwellingUnitId, ownerUserId);
        }

        // ── when: 残り（V110.001 含む）を適用 ──
        migrateRemaining();

        try (Connection c = conn()) {
            // ── then-1: 対象10FKが全て撤廃された ──
            assertThat(foreignKeyExists(c, "blog_posts", "fk_blog_posts_vt"))
                    .as("V110.001 で fk_blog_posts_vt が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "recruitment_listings", "fk_recruitment_listings_vt"))
                    .as("V110.001 で fk_recruitment_listings_vt が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "schedules", "fk_schedules_vt"))
                    .as("V110.001 で fk_schedules_vt が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "budget_configs", "fk_bconf_over_limit_workflow"))
                    .as("V110.001 で fk_bconf_over_limit_workflow が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "budget_configs", "fk_bconf_workflow_template"))
                    .as("V110.001 で fk_bconf_workflow_template が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "budget_threshold_alerts", "fk_bta_workflow_request"))
                    .as("V110.001 で fk_bta_workflow_request が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "budget_transactions", "fk_bt_workflow_request"))
                    .as("V110.001 で fk_bt_workflow_request が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "disclosure_exports", "fk_de_dwelling"))
                    .as("V110.001 で fk_de_dwelling が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "disclosure_form_drafts", "fk_dfd_dwelling"))
                    .as("V110.001 で fk_dfd_dwelling が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "property_work_packages", "fk_pwp_dwelling"))
                    .as("V110.001 で fk_pwp_dwelling が撤廃されること").isFalse();

            // ── then-2（中核）: 各参照先テーブルの行を物理削除しても、参照元の外部キー列が NULL 化されず孤児値を保持 ──

            // group-1: visibility_template を物理削除 → 3参照元の visibility_template_id が孤児値保持
            deleteRow(c, "visibility_templates", vtId);
            assertThat(rowExists(c, "visibility_templates", vtId)).as("参照先 visibility_template が物理削除されたこと").isFalse();
            assertThat(longColumn(c, "blog_posts", "visibility_template_id", blogPostId))
                    .as("blog_posts.visibility_template_id が SET NULL されず孤児 vt_id を保持すること").isEqualTo(vtId);
            assertThat(longColumn(c, "recruitment_listings", "visibility_template_id", recruitmentId))
                    .as("recruitment_listings.visibility_template_id が SET NULL されず孤児 vt_id を保持すること").isEqualTo(vtId);
            assertThat(longColumn(c, "schedules", "visibility_template_id", scheduleId))
                    .as("schedules.visibility_template_id が SET NULL されず孤児 vt_id を保持すること").isEqualTo(vtId);

            // group-2b: workflow_request を先に物理削除 → alert / transaction の workflow_request_id が孤児値保持
            //   注: workflow_requests.template_id は workflow_templates へ ON DELETE RESTRICT(fk_workflow_requests_template・
            //   同一ドメイン・本PR対象外)。よって workflow_template より先に workflow_request を削除して RESTRICT を解消する。
            deleteRow(c, "workflow_requests", workflowRequestId);
            assertThat(rowExists(c, "workflow_requests", workflowRequestId)).as("参照先 workflow_request が物理削除されたこと").isFalse();
            assertThat(longColumn(c, "budget_threshold_alerts", "workflow_request_id", btaId))
                    .as("budget_threshold_alerts.workflow_request_id が SET NULL されず孤児 request_id を保持すること").isEqualTo(workflowRequestId);
            assertThat(longColumn(c, "budget_transactions", "workflow_request_id", budgetTxId))
                    .as("budget_transactions.workflow_request_id が SET NULL されず孤児 request_id を保持すること").isEqualTo(workflowRequestId);

            // group-2a: workflow_request 削除後に workflow_template を物理削除 → budget_configs の2列が孤児値保持
            deleteRow(c, "workflow_templates", workflowTemplateId);
            assertThat(rowExists(c, "workflow_templates", workflowTemplateId)).as("参照先 workflow_template が物理削除されたこと").isFalse();
            assertThat(longColumn(c, "budget_configs", "workflow_template_id", budgetConfigId))
                    .as("budget_configs.workflow_template_id が SET NULL されず孤児 template_id を保持すること").isEqualTo(workflowTemplateId);
            assertThat(longColumn(c, "budget_configs", "over_limit_workflow_id", budgetConfigId))
                    .as("budget_configs.over_limit_workflow_id が SET NULL されず孤児 template_id を保持すること").isEqualTo(workflowTemplateId);

            // group-3: dwelling_unit を物理削除 → 3参照元の dwelling 列が孤児値保持
            deleteRow(c, "dwelling_units", dwellingUnitId);
            assertThat(rowExists(c, "dwelling_units", dwellingUnitId)).as("参照先 dwelling_unit が物理削除されたこと").isFalse();
            assertThat(longColumn(c, "disclosure_exports", "target_dwelling_unit_id", disclosureExportId))
                    .as("disclosure_exports.target_dwelling_unit_id が SET NULL されず孤児 dwelling_id を保持すること").isEqualTo(dwellingUnitId);
            assertThat(longColumn(c, "disclosure_form_drafts", "target_dwelling_unit_id", disclosureDraftId))
                    .as("disclosure_form_drafts.target_dwelling_unit_id が SET NULL されず孤児 dwelling_id を保持すること").isEqualTo(dwellingUnitId);
            assertThat(longColumn(c, "property_work_packages", "dwelling_unit_id", workPackageId))
                    .as("property_work_packages.dwelling_unit_id が SET NULL されず孤児 dwelling_id を保持すること").isEqualTo(dwellingUnitId);
        }
    }

    // ── 共通 seed helpers ──────────────────────────────────────

    private long insertUser(Connection c, String email) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO users
                    (email, last_name, first_name, display_name, status, created_at, updated_at)
                VALUES (?, '第四', 'B郎', '第四B郎', 'ACTIVE', NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, email);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** organizations 行を挿入。slug は NOT NULL + UNIQUE（3〜30文字英数字ハイフン）/ org_type は 'OTHER'。 */
    private long insertOrganization(Connection c, String slug) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO organizations
                    (name, org_type, slug, created_at, updated_at)
                VALUES ('第四陣B監査組織', 'OTHER', ?, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, slug);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    // ── group-1: visibility_templates 参照 ──

    /** visibility_templates 行を挿入。chk_vt_preset_owner（FALSE → owner_user_id NOT NULL / preset_key NULL）を満たす。 */
    private long insertVisibilityTemplate(Connection c, long ownerUserId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO visibility_templates
                    (owner_user_id, name, is_system_preset, created_at, updated_at)
                VALUES (?, '第四陣B可視性テンプレ', FALSE, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, ownerUserId);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** user スコープの blog_post（chk_bp_scope: user_id のみ非NULL）を visibility_template_id 付きで挿入。 */
    private long insertBlogPost(Connection c, long userId, long vtId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO blog_posts
                    (user_id, title, slug, body, visibility_template_id, created_at, updated_at)
                VALUES (?, '第四陣B記事', 'p4b-blog-slug', '第四陣B本文', ?, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, userId);
            ps.setLong(2, vtId);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /**
     * recruitment_listing を挿入。NOT NULL/CHECK を全て充足:
     * scope / category_id（recruitment_categories）/ title / participation_type /
     * 日付4列（CHECK: auto_cancel <= deadline < start < end）/ capacity >= min_capacity / created_by /
     * visibility_template_id。
     */
    private long insertRecruitmentListing(Connection c, long organizationId, long createdBy, long vtId)
            throws SQLException {
        long categoryId = insertRecruitmentCategory(c);
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO recruitment_listings
                    (scope_type, scope_id, category_id, title, participation_type,
                     start_at, end_at, application_deadline, auto_cancel_at,
                     capacity, min_capacity, created_by, visibility_template_id, created_at, updated_at)
                VALUES ('ORGANIZATION', ?, ?, '第四陣B募集', 'INDIVIDUAL',
                        NOW() + INTERVAL 10 DAY, NOW() + INTERVAL 11 DAY, NOW() + INTERVAL 5 DAY, NOW() + INTERVAL 4 DAY,
                        10, 1, ?, ?, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, organizationId);
            ps.setLong(2, categoryId);
            ps.setLong(3, createdBy);
            ps.setLong(4, vtId);
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
                VALUES ('P4B_CAT', 'recruitment.category.p4b', 'INDIVIDUAL', 0, TRUE, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** user スコープの schedule（chk_schedule_scope: user_id のみ非NULL）を visibility_template_id 付きで挿入。 */
    private long insertUserSchedule(Connection c, long userId, long vtId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO schedules
                    (user_id, title, start_at, event_type, status, created_by, visibility_template_id, created_at, updated_at)
                VALUES (?, '第四陣B予定', NOW() + INTERVAL 1 DAY, 'OTHER', 'SCHEDULED', ?, ?, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, userId);
            ps.setLong(2, userId);
            ps.setLong(3, vtId);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    // ── group-2: workflow_templates / workflow_requests 参照 ──

    /** workflow_templates 行を挿入。scope_type/scope_id/name NOT NULL を満たす。 */
    private long insertWorkflowTemplate(Connection c, long organizationId, long createdBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO workflow_templates
                    (scope_type, scope_id, name, created_by, created_at, updated_at)
                VALUES ('ORGANIZATION', ?, '第四陣Bワークフローテンプレ', ?, NOW(), NOW())
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

    /** workflow_requests 行を挿入。template_id（RESTRICT）/scope/title NOT NULL を満たす。 */
    private long insertWorkflowRequest(Connection c, long templateId, long organizationId, long requestedBy)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO workflow_requests
                    (template_id, scope_type, scope_id, title, status, requested_by, created_at, updated_at)
                VALUES (?, 'ORGANIZATION', ?, '第四陣Bワークフロー申請', 'DRAFT', ?, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, templateId);
            ps.setLong(2, organizationId);
            ps.setLong(3, requestedBy);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** budget_configs 行を挿入。workflow_template_id / over_limit_workflow_id の両方を同一テンプレに紐付け。 */
    private long insertBudgetConfig(Connection c, long organizationId, long workflowTemplateId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO budget_configs
                    (scope_type, scope_id, workflow_template_id, over_limit_workflow_id, created_at, updated_at)
                VALUES ('ORGANIZATION', ?, ?, ?, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, organizationId);
            ps.setLong(2, workflowTemplateId);
            ps.setLong(3, workflowTemplateId);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** budget_fiscal_years 行を挿入。scope_type/status/dates CHECK / created_by RESTRICT を満たす。 */
    private long insertBudgetFiscalYear(Connection c, long organizationId, long createdBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO budget_fiscal_years
                    (scope_type, scope_id, name, start_date, end_date, status, created_by, created_at, updated_at)
                VALUES ('ORGANIZATION', ?, '第四陣B年度', '2020-04-01', '2021-03-31', 'OPEN', ?, NOW(), NOW())
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

    /** budget_categories 行を挿入。fiscal_year RESTRICT / category_type CHECK(INCOME/EXPENSE) を満たす。 */
    private long insertBudgetCategory(Connection c, long fiscalYearId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO budget_categories
                    (fiscal_year_id, name, category_type, sort_order, created_at, updated_at)
                VALUES (?, '第四陣B費目', 'EXPENSE', 0, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, fiscalYearId);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** shift_budget_allocations 行を挿入（budget_threshold_alerts.allocation_id CASCADE の親）。 */
    private long insertShiftBudgetAllocation(Connection c, long organizationId, long fiscalYearId,
                                             long budgetCategoryId, long createdBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO shift_budget_allocations
                    (organization_id, fiscal_year_id, budget_category_id, period_start, period_end,
                     allocated_amount, consumed_amount, confirmed_amount, currency, created_by, created_at, updated_at)
                VALUES (?, ?, ?, '2020-04-01', '2021-03-31', 1000000, 0, 0, 'JPY', ?, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, organizationId);
            ps.setLong(2, fiscalYearId);
            ps.setLong(3, budgetCategoryId);
            ps.setLong(4, createdBy);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** budget_threshold_alerts 行を挿入。workflow_request_id（撤廃対象列）をセット。 */
    private long insertBudgetThresholdAlert(Connection c, long allocationId, long workflowRequestId)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO budget_threshold_alerts
                    (allocation_id, threshold_percent, triggered_at, consumed_amount_at_trigger, notified_user_ids,
                     workflow_request_id, created_at, updated_at)
                VALUES (?, 80, NOW(), 800000, '[]', ?, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, allocationId);
            ps.setLong(2, workflowRequestId);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** budget_transactions 行を挿入。workflow_request_id（撤廃対象列）をセット。CHECK 3件を満たす。 */
    private long insertBudgetTransaction(Connection c, long fiscalYearId, long categoryId,
                                         long recordedBy, long workflowRequestId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO budget_transactions
                    (fiscal_year_id, category_id, scope_type, scope_id, transaction_type, amount, transaction_date,
                     title, approval_status, recorded_by, workflow_request_id, created_at, updated_at)
                VALUES (?, ?, 'ORGANIZATION', 999999, 'EXPENSE', 5000, CURDATE(),
                        '第四陣B取引', 'APPROVED', ?, ?, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, fiscalYearId);
            ps.setLong(2, categoryId);
            ps.setLong(3, recordedBy);
            ps.setLong(4, workflowRequestId);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    // ── group-3: dwelling_units 参照 ──

    /** dwelling_units 行を挿入（ORGANIZATION スコープ・organization_id NOT NULL）。 */
    private long insertDwellingUnit(Connection c, long organizationId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO dwelling_units
                    (scope_type, organization_id, unit_number, unit_type, resident_count, created_at, updated_at)
                VALUES ('ORGANIZATION', ?, 'P4B-101', 'STANDARD', 0, NOW(), NOW())
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
     * disclosure_form_templates 行を挿入（disclosure_exports/drafts.template_id RESTRICT の親）。
     * code/name/version/form_schema NOT NULL / chk_dft_scope_type(ORGANIZATION) /
     * chk_dft_system_scope(is_system_template=0 → scope_type/scope_id NOT NULL) を満たす。
     */
    private long insertDisclosureFormTemplate(Connection c, long organizationId, long createdBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO disclosure_form_templates
                    (code, name, version, is_system_template, scope_type, scope_id, form_schema, is_active,
                     created_by, created_at, updated_at)
                VALUES ('P4B-TPL', '第四陣B開示テンプレ', 'v1', 0, 'ORGANIZATION', ?, '{}', 1, ?, NOW(), NOW())
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

    /** shared_folders 行を挿入（shared_files.folder_id CASCADE の親）。ORGANIZATION スコープ。 */
    private long insertSharedFolder(Connection c, long organizationId, long createdBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO shared_folders
                    (scope_type, organization_id, name, created_by, created_at, updated_at)
                VALUES ('ORGANIZATION', ?, '第四陣Bフォルダ', ?, NOW(), NOW())
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

    /** shared_files 行を挿入（disclosure_exports.shared_file_id RESTRICT の親）。folder_id CASCADE 必須。 */
    private long insertSharedFile(Connection c, long organizationId, long createdBy) throws SQLException {
        long folderId = insertSharedFolder(c, organizationId, createdBy);
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO shared_files
                    (folder_id, name, file_key, file_size, content_type, created_by, created_at, updated_at)
                VALUES (?, 'p4b.pdf', 'p4b/key/p4b.pdf', 1024, 'application/pdf', ?, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, folderId);
            ps.setLong(2, createdBy);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** disclosure_form_drafts 行を挿入。target_dwelling_unit_id（撤廃対象列）をセット。 */
    private long insertDisclosureFormDraft(Connection c, long organizationId, long templateId,
                                           long dwellingUnitId, long createdBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO disclosure_form_drafts
                    (scope_type, scope_id, template_id, template_version_snapshot, title, target_dwelling_unit_id,
                     form_data, status, created_by, created_at, updated_at)
                VALUES ('ORGANIZATION', ?, ?, 'v1', '第四陣B下書き', ?, '{}', 'DRAFT', ?, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, organizationId);
            ps.setLong(2, templateId);
            ps.setLong(3, dwellingUnitId);
            ps.setLong(4, createdBy);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** disclosure_exports 行を挿入。target_dwelling_unit_id（撤廃対象列）をセット。 */
    private long insertDisclosureExport(Connection c, long organizationId, long templateId, long sharedFileId,
                                        long dwellingUnitId, long requesterUserId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO disclosure_exports
                    (scope_type, scope_id, template_id, template_code_snapshot, template_version_snapshot,
                     output_format, shared_file_id, target_dwelling_unit_id, requester_user_id, data_snapshot, created_at)
                VALUES ('ORGANIZATION', ?, ?, 'P4B-TPL', 'v1', 'PDF', ?, ?, ?, '{}', NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, organizationId);
            ps.setLong(2, templateId);
            ps.setLong(3, sharedFileId);
            ps.setLong(4, dwellingUnitId);
            ps.setLong(5, requesterUserId);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** property_work_packages 行を挿入。dwelling_unit_id（撤廃対象列）をセット。CHECK 群を満たす。 */
    private long insertPropertyWorkPackage(Connection c, long organizationId, long dwellingUnitId, long createdBy)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO property_work_packages
                    (scope_type, scope_id, dwelling_unit_id, work_type, title, currency, is_disclosable,
                     visibility, status, attachment_count, comment_count, created_by, created_at, updated_at)
                VALUES ('ORGANIZATION', ?, ?, 'REPAIR', '第四陣B工事', 'JPY', 1,
                        'ADMINS_ONLY', 'PLANNED', 0, 0, ?, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, organizationId);
            ps.setLong(2, dwellingUnitId);
            ps.setLong(3, createdBy);
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
