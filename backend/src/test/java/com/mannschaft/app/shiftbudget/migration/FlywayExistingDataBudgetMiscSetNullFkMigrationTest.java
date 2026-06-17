package com.mannschaft.app.shiftbudget.migration;

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
 * <b>クロスドメインFK撤廃 第三陣F（第三陣ラスト・org_modules / budget / promotion / confirmable / bulletin）の番人テスト。</b>
 *
 * <p>V107.001 で「users を親とする ON DELETE SET NULL の監査/操作者カラム」FK 5件を撤廃only する:</p>
 * <ul>
 *   <li>{@code organization_enabled_modules.fk_oem_user}（enabled_by → users SET NULL）</li>
 *   <li>{@code budget_threshold_alerts.fk_bta_acked_by}（acknowledged_by → users SET NULL）</li>
 *   <li>{@code promotions.fk_promotions_approved_by}（approved_by → users SET NULL）</li>
 *   <li>{@code confirmable_notification_recipients.fk_cnr_excluded_by}（excluded_by → users SET NULL）</li>
 *   <li>{@code bulletin_archive_folders.fk_bulletin_archive_folders_created_by}（created_by → users SET NULL）</li>
 * </ul>
 *
 * <p>本テストが守る不変条件:</p>
 * <ol>
 *   <li>V107.001 の直前（V106.001）まで適用 → 監査列＝対象 user を持つ子行をシード。</li>
 *   <li>V107.001 直前時点で対象5FKが実在することを sanity 確認。</li>
 *   <li>残り（V107.001 含む）を適用しても既存データを壊さず成功する。</li>
 *   <li>V107.001 で対象5FKが撤廃される。</li>
 *   <li><b>親 users 行（監査列でのみ参照される user）を物理 DELETE しても監査列が NULL 化されず孤児 user_id 値を保持する</b>
 *       （＝SET NULL 撤廃only の肝・「誰が有効化/確認/承認/免除/作成したか」の証跡温存）。</li>
 * </ol>
 *
 * <p>方針: Spring を起動せず Testcontainers の実 MySQL 8.0 に {@link Flyway} を Java API で直接実行する。
 * Docker 未起動環境では {@code @EnabledIf} でスキップ（骨抜きにしない・根治原則）。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.shiftbudget.migration.FlywayExistingDataBudgetMiscSetNullFkMigrationTest#isDockerAvailable")
@DisplayName("Flyway 既存データ org_modules/budget/promotion/confirmable/bulletin 監査列 SET NULL FK撤廃（V107.001）番人テスト")
class FlywayExistingDataBudgetMiscSetNullFkMigrationTest {

    /** V107.001 の直前バージョン（origin/main 全体最大＝第三陣E）。ここまで適用してから既存行をシードする。 */
    private static final String PRE_V107_001_TARGET = "106.001";

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_budget_misc_setnull_fk")
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

    @Test
    @DisplayName("既存子行を持つDBにV107.001適用_監査列SET_NULL_FK5件撤廃_親user物理削除でも監査列が孤児user_idを保持")
    void 既存データを持つDBでV107_001が監査列SET_NULL_FK撤廃onlyで安全に適用される() throws Exception {
        // given: V107.001 の直前（V106.001）まで適用 ＝ 対象5FKはまだ生きている
        Flyway pre = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .target(MigrationVersion.fromVersion(PRE_V107_001_TARGET))
                .load();
        MigrateResult preResult = pre.migrate();
        assertThat(preResult.success).as("V106.001 までの適用が成功すること").isTrue();

        // 監査・操作者 user 群（監査列でのみ参照される＝撤廃対象列に充てる user）
        final long oemEnabledBy;     // organization_enabled_modules.enabled_by（監査列）
        final long btaAckedBy;       // budget_threshold_alerts.acknowledged_by（監査列）
        final long promoApprovedBy;  // promotions.approved_by（監査列）
        final long cnrExcludedBy;    // confirmable_notification_recipients.excluded_by（監査列）
        final long bafCreatedBy;     // bulletin_archive_folders.created_by（監査列）

        // 撤廃対象でない NOT NULL/RESTRICT FK を満たすための user 群（物理削除しない）
        final long budgetCreator;    // budget_fiscal_years.created_by / shift_budget_allocations.created_by（RESTRICT・退会防止）
        final long promoCreatedBy;   // promotions.created_by（RESTRICT・退会防止）
        final long cnrRecipient;     // confirmable_notification_recipients.user_id（CASCADE・受信者本人 NOT NULL）

        // 親（非 user）行 id
        final long organizationId;
        final long moduleId;
        final long fiscalYearId;
        final long budgetCategoryId;
        final long allocationId;
        final long confirmableNotificationId;

        // 撤廃対象を監査列に持つ子行 id
        final long oemId;
        final long btaId;
        final long promotionId;
        final long cnrId;
        final byte[] bafId;

        try (Connection c = conn()) {
            // sanity: V106.001 時点で対象5FKが実在すること
            assertThat(foreignKeyExists(c, "organization_enabled_modules", "fk_oem_user"))
                    .as("V106.001 時点で fk_oem_user が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "budget_threshold_alerts", "fk_bta_acked_by"))
                    .as("V106.001 時点で fk_bta_acked_by が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "promotions", "fk_promotions_approved_by"))
                    .as("V106.001 時点で fk_promotions_approved_by が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "confirmable_notification_recipients", "fk_cnr_excluded_by"))
                    .as("V106.001 時点で fk_cnr_excluded_by が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "bulletin_archive_folders", "fk_bulletin_archive_folders_created_by"))
                    .as("V106.001 時点で fk_bulletin_archive_folders_created_by が実在すること").isTrue();

            // users（監査列用）
            oemEnabledBy = insertUser(c, "oem-enabled-3f@example.com");
            btaAckedBy = insertUser(c, "bta-acked-3f@example.com");
            promoApprovedBy = insertUser(c, "promo-approved-3f@example.com");
            cnrExcludedBy = insertUser(c, "cnr-excluded-3f@example.com");
            bafCreatedBy = insertUser(c, "baf-created-3f@example.com");
            // users（撤廃対象外 FK 用・物理削除しない）
            budgetCreator = insertUser(c, "budget-creator-3f@example.com");
            promoCreatedBy = insertUser(c, "promo-created-3f@example.com");
            cnrRecipient = insertUser(c, "cnr-recipient-3f@example.com");

            // ── organization_enabled_modules チェーン ──
            // fk_oem_org RESTRICT → organizations（slug NOT NULL + UNIQUE）/ fk_oem_module RESTRICT → module_definitions（slug UNIQUE + module_type CHECK）
            organizationId = insertOrganization(c, "phase3f-org-budget");
            moduleId = insertModuleDefinition(c, "phase3f-module");
            oemId = insertOrganizationEnabledModule(c, organizationId, moduleId, oemEnabledBy);

            // ── budget_threshold_alerts チェーン ──
            // fk_bta_allocation CASCADE → shift_budget_allocations。さらに allocation の親:
            //   organization_id CASCADE → organizations（上で作成済）
            //   fiscal_year_id RESTRICT → budget_fiscal_years（created_by RESTRICT → users＝budgetCreator）
            //   budget_category_id RESTRICT → budget_categories（fiscal_year RESTRICT）
            //   created_by RESTRICT → users＝budgetCreator
            fiscalYearId = insertBudgetFiscalYear(c, organizationId, budgetCreator);
            budgetCategoryId = insertBudgetCategory(c, fiscalYearId);
            allocationId = insertShiftBudgetAllocation(c, organizationId, fiscalYearId, budgetCategoryId, budgetCreator);
            btaId = insertBudgetThresholdAlert(c, allocationId, btaAckedBy);

            // ── promotions（created_by RESTRICT → users＝promoCreatedBy / approved_by SET NULL → users＝promoApprovedBy）──
            promotionId = insertPromotion(c, promoCreatedBy, promoApprovedBy);

            // ── confirmable_notification_recipients チェーン ──
            // fk_cnr_notification CASCADE → confirmable_notifications（created_by/cancelled_by は nullable で省略可）
            // fk_cnr_user CASCADE → users＝cnrRecipient（受信者・NOT NULL）/ excluded_by SET NULL → users＝cnrExcludedBy
            confirmableNotificationId = insertConfirmableNotification(c, organizationId);
            cnrId = insertConfirmableNotificationRecipient(c, confirmableNotificationId, cnrRecipient, cnrExcludedBy);

            // ── bulletin_archive_folders（PK は BINARY(16) UUIDv7・created_by SET NULL → users＝bafCreatedBy）──
            bafId = insertBulletinArchiveFolder(c, organizationId, bafCreatedBy);
        }

        // when: 残りのマイグレーション（V107.001 含む）を適用する
        Flyway rest = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .load();
        MigrateResult restResult = rest.migrate();
        assertThat(restResult.success).as("V107.001 を含む残りのマイグレーションが成功すること").isTrue();

        try (Connection c = conn()) {
            // then-1: 対象5FKが撤廃された
            assertThat(foreignKeyExists(c, "organization_enabled_modules", "fk_oem_user"))
                    .as("V107.001 で fk_oem_user が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "budget_threshold_alerts", "fk_bta_acked_by"))
                    .as("V107.001 で fk_bta_acked_by が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "promotions", "fk_promotions_approved_by"))
                    .as("V107.001 で fk_promotions_approved_by が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "confirmable_notification_recipients", "fk_cnr_excluded_by"))
                    .as("V107.001 で fk_cnr_excluded_by が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "bulletin_archive_folders", "fk_bulletin_archive_folders_created_by"))
                    .as("V107.001 で fk_bulletin_archive_folders_created_by が撤廃されること").isFalse();

            // 対象外（対照）: 撤廃済でない同一/隣接ドメイン FK が撤廃後も残存していること。
            // 注: 過去 wave で既に撤廃済の FK を対照に使うと誤って fail するため、確実に net-active な FK のみ使う。
            //   ・fk_oem_org（organization_enabled_modules → organizations RESTRICT）は過去 wave での DROP なし net-active。
            //   ・fk_promotions_created_by（promotions → users RESTRICT・作成者退会防止）は本 PR でも対象外で残存。
            assertThat(foreignKeyExists(c, "organization_enabled_modules", "fk_oem_org"))
                    .as("fk_oem_org（organizations RESTRICT）は撤廃対象外で残存すること").isTrue();
            assertThat(foreignKeyExists(c, "promotions", "fk_promotions_created_by"))
                    .as("fk_promotions_created_by（users RESTRICT・作成者）は撤廃対象外で残存すること").isTrue();

            // then-2: 既存子行が生存していること
            assertThat(rowExists(c, "organization_enabled_modules", oemId))
                    .as("FK 撤廃後も organization_enabled_modules 子行が生存していること").isTrue();
            assertThat(rowExists(c, "budget_threshold_alerts", btaId))
                    .as("FK 撤廃後も budget_threshold_alerts 子行が生存していること").isTrue();
            assertThat(rowExists(c, "promotions", promotionId))
                    .as("FK 撤廃後も promotions 子行が生存していること").isTrue();
            assertThat(rowExists(c, "confirmable_notification_recipients", cnrId))
                    .as("FK 撤廃後も confirmable_notification_recipients 子行が生存していること").isTrue();
            assertThat(rowExistsBinary(c, "bulletin_archive_folders", bafId))
                    .as("FK 撤廃後も bulletin_archive_folders 子行が生存していること").isTrue();

            // then-3（中核）: 監査列でのみ参照される親 users を物理削除しても監査列が NULL 化されず孤児値を保持
            deleteUserPhysically(c, oemEnabledBy);
            deleteUserPhysically(c, btaAckedBy);
            deleteUserPhysically(c, promoApprovedBy);
            deleteUserPhysically(c, cnrExcludedBy);
            deleteUserPhysically(c, bafCreatedBy);

            assertThat(rowExists(c, "users", oemEnabledBy)).as("親 users（oem enabled_by）が物理削除されたこと").isFalse();
            assertThat(rowExists(c, "users", bafCreatedBy)).as("親 users（baf created_by）が物理削除されたこと").isFalse();

            assertThat(longColumn(c, "organization_enabled_modules", "enabled_by", oemId))
                    .as("organization_enabled_modules.enabled_by が SET NULL されず孤児 user_id を保持すること").isEqualTo(oemEnabledBy);
            assertThat(longColumn(c, "budget_threshold_alerts", "acknowledged_by", btaId))
                    .as("budget_threshold_alerts.acknowledged_by が SET NULL されず孤児 user_id を保持すること").isEqualTo(btaAckedBy);
            assertThat(longColumn(c, "promotions", "approved_by", promotionId))
                    .as("promotions.approved_by が SET NULL されず孤児 user_id を保持すること").isEqualTo(promoApprovedBy);
            assertThat(longColumn(c, "confirmable_notification_recipients", "excluded_by", cnrId))
                    .as("confirmable_notification_recipients.excluded_by が SET NULL されず孤児 user_id を保持すること").isEqualTo(cnrExcludedBy);
            assertThat(longColumnBinary(c, "bulletin_archive_folders", "created_by", bafId))
                    .as("bulletin_archive_folders.created_by が SET NULL されず孤児 user_id を保持すること").isEqualTo(bafCreatedBy);
        }
    }

    // ── helpers (insert) ────────────────────────────────────────────────

    private long insertUser(Connection c, String email) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO users
                    (email, last_name, first_name, display_name, status, created_at, updated_at)
                VALUES (?, '監査', '太郎', '監査太郎', 'ACTIVE', NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, email);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** organizations 行を挿入する。slug は NOT NULL + UNIQUE（最大30文字英数字ハイフン）を満たす。 */
    private long insertOrganization(Connection c, String slug) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO organizations
                    (name, org_type, slug, created_at, updated_at)
                VALUES ('第三陣F監査組織', 'OTHER', ?, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, slug);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** module_definitions 行を挿入する。slug は UNIQUE、module_type は CHECK(DEFAULT/OPTIONAL) を満たす。 */
    private long insertModuleDefinition(Connection c, String slug) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO module_definitions
                    (name, slug, module_type, module_number, created_at, updated_at)
                VALUES ('第三陣F監査モジュール', ?, 'OPTIONAL', 999, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, slug);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** organization_enabled_modules 行を挿入する。撤廃対象列 enabled_by に user をセットする。 */
    private long insertOrganizationEnabledModule(Connection c, long organizationId, long moduleId, long enabledBy)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO organization_enabled_modules
                    (organization_id, module_id, is_enabled, enabled_at, enabled_by, created_at, updated_at)
                VALUES (?, ?, 1, NOW(), ?, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, organizationId);
            ps.setLong(2, moduleId);
            ps.setLong(3, enabledBy);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** budget_fiscal_years 行を挿入する。created_by RESTRICT → users。scope_type CHECK/status CHECK/dates CHECK を満たす。 */
    private long insertBudgetFiscalYear(Connection c, long organizationId, long createdBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO budget_fiscal_years
                    (scope_type, scope_id, name, start_date, end_date, status, created_by, created_at, updated_at)
                VALUES ('ORGANIZATION', ?, '第三陣F年度', '2020-04-01', '2021-03-31', 'OPEN', ?, NOW(), NOW())
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

    /** budget_categories 行を挿入する。fiscal_year RESTRICT。category_type CHECK(INCOME/EXPENSE) を満たす。 */
    private long insertBudgetCategory(Connection c, long fiscalYearId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO budget_categories
                    (fiscal_year_id, name, category_type, sort_order, created_at, updated_at)
                VALUES (?, '第三陣F費目', 'EXPENSE', 0, NOW(), NOW())
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
     * shift_budget_allocations 行を挿入する。NOT NULL 列を全て充足:
     * organization_id(CASCADE) / fiscal_year_id(RESTRICT) / budget_category_id(RESTRICT) / period(CHECK start<=end)
     * / allocated_amount(CHECK >=0) / created_by(RESTRICT → users)。
     */
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

    /**
     * budget_threshold_alerts 行を挿入する。NOT NULL 列を全て充足:
     * allocation_id(CASCADE) / threshold_percent(CHECK IN(80,100,120)) / consumed_amount_at_trigger(CHECK >=0)
     * / notified_user_ids(JSON NOT NULL = '[]')。撤廃対象列 acknowledged_by に user をセットする。
     */
    private long insertBudgetThresholdAlert(Connection c, long allocationId, long ackedBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO budget_threshold_alerts
                    (allocation_id, threshold_percent, triggered_at, consumed_amount_at_trigger, notified_user_ids,
                     acknowledged_at, acknowledged_by, created_at, updated_at)
                VALUES (?, 80, NOW(), 800000, '[]', NOW(), ?, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, allocationId);
            ps.setLong(2, ackedBy);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /**
     * promotions 行を挿入する。NOT NULL 列を全て充足:
     * scope_type / scope_id / created_by(RESTRICT → users) / title / status(ENUM)。
     * 撤廃対象列 approved_by に user をセットする。
     */
    private long insertPromotion(Connection c, long createdBy, long approvedBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO promotions
                    (scope_type, scope_id, created_by, title, status, approved_by, approved_at, created_at, updated_at)
                VALUES ('ORGANIZATION', 999999, ?, '第三陣F監査プロモ', 'APPROVED', ?, NOW(), NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, createdBy);
            ps.setLong(2, approvedBy);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /**
     * confirmable_notifications 行を挿入する（confirmable_notification_recipients の親・fk_cnr_notification CASCADE）。
     * NOT NULL 列を全て充足: source_type(default有) / scope_type(ENUM TEAM/ORGANIZATION) / scope_id / title。
     * created_by/cancelled_by は nullable のため省略（SET NULL FK・本 PR の対象外）。
     */
    private long insertConfirmableNotification(Connection c, long scopeId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO confirmable_notifications
                    (source_type, scope_type, scope_id, title, priority, status, created_at, updated_at)
                VALUES ('EMERGENCY_CLOSURE', 'ORGANIZATION', ?, '第三陣F確認通知', 'NORMAL', 'ACTIVE', NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, scopeId);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /**
     * confirmable_notification_recipients 行を挿入する。NOT NULL 列を全て充足:
     * confirmable_notification_id(CASCADE) / user_id(CASCADE → users・受信者) / confirm_token(VARCHAR(36) NOT NULL)。
     * 撤廃対象列 excluded_by に user をセットする。
     */
    private long insertConfirmableNotificationRecipient(Connection c, long confirmableNotificationId,
                                                        long recipientUserId, long excludedBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO confirmable_notification_recipients
                    (confirmable_notification_id, user_id, confirm_token, is_confirmed, excluded_at, excluded_by, created_at)
                VALUES (?, ?, ?, FALSE, NOW(), ?, NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, confirmableNotificationId);
            ps.setLong(2, recipientUserId);
            // confirm_token は VARCHAR(36)。UUID(36文字) を使い長さ制約内に収める。
            ps.setString(3, java.util.UUID.randomUUID().toString());
            ps.setLong(4, excludedBy);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /**
     * bulletin_archive_folders 行を挿入する。PK は BINARY(16) UUIDv7（自動採番なし・明示生成）。
     * NOT NULL 列を全て充足: id / scope_type / scope_id / name。depth/display_order は default 有。
     * 撤廃対象列 created_by に user をセットする。
     */
    private byte[] insertBulletinArchiveFolder(Connection c, long scopeId, long createdBy) throws SQLException {
        // UUIDv7 でなくても本テストでは時刻順ソートは不問。UUID_TO_BIN(UUID()) で BINARY(16) PK を生成する。
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO bulletin_archive_folders
                    (id, scope_type, scope_id, name, depth, display_order, created_by, created_at, updated_at)
                VALUES (UUID_TO_BIN(UUID()), 'ORGANIZATION', ?, '第三陣Fフォルダ', 0, 0, ?, NOW(), NOW())
                """)) {
            ps.setLong(1, scopeId);
            ps.setLong(2, createdBy);
            ps.executeUpdate();
        }
        // 生成した PK を created_by で逆引き（テスト内で一意）。
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id FROM bulletin_archive_folders WHERE created_by = ?")) {
            ps.setLong(1, createdBy);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBytes(1);
            }
        }
    }

    private void deleteUserPhysically(Connection c, long userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM users WHERE id = ?")) {
            ps.setLong(1, userId);
            ps.executeUpdate();
        }
    }

    // ── helpers (assert) ────────────────────────────────────────────────

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

    private static boolean rowExistsBinary(Connection c, String table, byte[] id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM " + table + " WHERE id = ?")) {
            ps.setBytes(1, id);
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

    private static long longColumnBinary(Connection c, String table, String column, byte[] id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT " + column + " FROM " + table + " WHERE id = ?")) {
            ps.setBytes(1, id);
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
