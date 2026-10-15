package com.mannschaft.app.errorreport.migration;

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
 * <b>クロスドメインFK撤廃 第三陣E（errorreport / resident_registry / survey / todo / notification）の番人テスト。</b>
 *
 * <p>V106.001 で「users を親とする ON DELETE SET NULL の監査/操作者カラム」FK 8件を撤廃only する:</p>
 * <ul>
 *   <li>{@code error_reports.fk_error_reports_assignee_id}（assignee_id → users SET NULL）</li>
 *   <li>{@code error_reports.fk_error_reports_resolved_by}（resolved_by → users SET NULL）</li>
 *   <li>{@code error_reports.fk_error_reports_user_id}（user_id → users SET NULL）</li>
 *   <li>{@code resident_registry.fk_rr_user}（user_id → users SET NULL）</li>
 *   <li>{@code resident_registry.fk_rr_verified_by}（verified_by → users SET NULL）</li>
 *   <li>{@code surveys.fk_surveys_created_by}（created_by → users SET NULL）</li>
 *   <li>{@code todo_status_labels.fk_tsl_created_by}（created_by → users SET NULL）</li>
 *   <li>{@code notifications.fk_notifications_actor}（actor_id → users SET NULL）</li>
 * </ul>
 *
 * <p>本テストが守る不変条件:</p>
 * <ol>
 *   <li>V106.001 の直前（V105.001）まで適用 → 監査列＝対象 user を持つ子行をシード。</li>
 *   <li>V106.001 直前時点で対象8FKが実在することを sanity 確認。</li>
 *   <li>残り（V106.001 含む）を適用しても既存データを壊さず成功する。</li>
 *   <li>V106.001 で対象8FKが撤廃される。</li>
 *   <li><b>親 users 行（監査列でのみ参照される user）を物理 DELETE しても監査列が NULL 化されず孤児 user_id 値を保持する</b>
 *       （＝SET NULL 撤廃only の肝・「誰が報告/担当/解決/作成/確認/操作したか」の証跡温存）。</li>
 * </ol>
 *
 * <p>方針: Spring を起動せず Testcontainers の実 MySQL 8.0 に {@link Flyway} を Java API で直接実行する。
 * Docker 未起動環境では {@code @EnabledIf} でスキップ（骨抜きにしない・根治原則）。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.errorreport.migration.FlywayExistingDataErrorReportMiscSetNullFkMigrationTest#isDockerAvailable")
@DisplayName("Flyway 既存データ errorreport/resident/survey/todo/notification 監査列 SET NULL FK撤廃（V106.001）番人テスト")
class FlywayExistingDataErrorReportMiscSetNullFkMigrationTest {

    /** V106.001 の直前バージョン（origin/main 全体最大＝第三陣D）。ここまで適用してから既存行をシードする。 */
    private static final String PRE_V106_001_TARGET = "105.001";

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_errorreport_misc_setnull_fk")
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
    @DisplayName("既存子行を持つDBにV106.001適用_監査列SET_NULL_FK8件撤廃_親user物理削除でも監査列が孤児user_idを保持")
    void 既存データを持つDBでV106_001が監査列SET_NULL_FK撤廃onlyで安全に適用される() throws Exception {
        // given: V106.001 の直前（V105.001）まで適用 ＝ 対象8FKはまだ生きている
        Flyway pre = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .target(MigrationVersion.fromVersion(PRE_V106_001_TARGET))
                .load();
        MigrateResult preResult = pre.migrate();
        assertThat(preResult.success).as("V105.001 までの適用が成功すること").isTrue();

        // 監査・操作者 user 群
        final long erUser;       // error_reports.user_id（報告者）
        final long erResolvedBy; // error_reports.resolved_by（監査列）
        final long erAssignee;   // error_reports.assignee_id（監査列）
        final long rrUser;       // resident_registry.user_id（居住者ユーザー）
        final long rrVerifiedBy; // resident_registry.verified_by（監査列）
        final long surveyCreator;// surveys.created_by（監査列）
        final long tslCreator;   // todo_status_labels.created_by（監査列）
        final long notifActor;   // notifications.actor_id（操作者）

        // 子行 id
        final long errorReportId;
        final long dwellingUnitId;
        final long residentRegistryId;
        final long surveyId;
        final long statusLabelId;
        final long notificationId;
        // notifications.user_id は NOT NULL（受信者）。撤廃対象ではない（fk_notifications_user は V100.001 で撤廃済）が
        // NOT NULL を満たす必要があるため受信者 user を別途用意する。
        final long notifRecipient;

        try (Connection c = conn()) {
            // sanity: V105.001 時点で対象8FKが実在すること
            assertThat(foreignKeyExists(c, "error_reports", "fk_error_reports_assignee_id"))
                    .as("V105.001 時点で fk_error_reports_assignee_id が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "error_reports", "fk_error_reports_resolved_by"))
                    .as("V105.001 時点で fk_error_reports_resolved_by が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "error_reports", "fk_error_reports_user_id"))
                    .as("V105.001 時点で fk_error_reports_user_id が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "resident_registry", "fk_rr_user"))
                    .as("V105.001 時点で fk_rr_user が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "resident_registry", "fk_rr_verified_by"))
                    .as("V105.001 時点で fk_rr_verified_by が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "surveys", "fk_surveys_created_by"))
                    .as("V105.001 時点で fk_surveys_created_by が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "todo_status_labels", "fk_tsl_created_by"))
                    .as("V105.001 時点で fk_tsl_created_by が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "notifications", "fk_notifications_actor"))
                    .as("V105.001 時点で fk_notifications_actor が実在すること").isTrue();

            erUser = insertUser(c, "er-user-3e@example.com");
            erResolvedBy = insertUser(c, "er-resolved-3e@example.com");
            erAssignee = insertUser(c, "er-assignee-3e@example.com");
            rrUser = insertUser(c, "rr-user-3e@example.com");
            rrVerifiedBy = insertUser(c, "rr-verified-3e@example.com");
            surveyCreator = insertUser(c, "survey-creator-3e@example.com");
            tslCreator = insertUser(c, "tsl-creator-3e@example.com");
            notifActor = insertUser(c, "notif-actor-3e@example.com");
            notifRecipient = insertUser(c, "notif-recipient-3e@example.com");

            errorReportId = insertErrorReport(c, erUser, erResolvedBy, erAssignee);
            // resident_registry.dwelling_unit_id は NOT NULL + fk_rr_dwelling_unit(CASCADE)。
            // dwelling_units.team_id/organization_id は nullable で各 FK は V95.001 で撤廃済 → scope のみ設定で親シード可。
            dwellingUnitId = insertDwellingUnit(c);
            residentRegistryId = insertResidentRegistry(c, dwellingUnitId, rrUser, rrVerifiedBy);
            // surveys.scope_id は BIGINT で FK 無し → 任意値で可。
            surveyId = insertSurvey(c, surveyCreator);
            // todo_status_labels は SYSTEM スコープ（scope_id IS NULL）が chk_tsl_scope_id_for_system を満たし最簡。
            statusLabelId = insertSystemStatusLabel(c, tslCreator);
            notificationId = insertNotification(c, notifRecipient, notifActor);
        }

        // when: 残りのマイグレーション（V106.001 含む）を適用する
        Flyway rest = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .load();
        MigrateResult restResult = rest.migrate();
        assertThat(restResult.success).as("V106.001 を含む残りのマイグレーションが成功すること").isTrue();

        try (Connection c = conn()) {
            // then-1: 対象8FKが撤廃された
            assertThat(foreignKeyExists(c, "error_reports", "fk_error_reports_assignee_id"))
                    .as("V106.001 で fk_error_reports_assignee_id が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "error_reports", "fk_error_reports_resolved_by"))
                    .as("V106.001 で fk_error_reports_resolved_by が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "error_reports", "fk_error_reports_user_id"))
                    .as("V106.001 で fk_error_reports_user_id が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "resident_registry", "fk_rr_user"))
                    .as("V106.001 で fk_rr_user が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "resident_registry", "fk_rr_verified_by"))
                    .as("V106.001 で fk_rr_verified_by が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "surveys", "fk_surveys_created_by"))
                    .as("V106.001 で fk_surveys_created_by が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "todo_status_labels", "fk_tsl_created_by"))
                    .as("V106.001 で fk_tsl_created_by が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "notifications", "fk_notifications_actor"))
                    .as("V106.001 で fk_notifications_actor が撤廃されること").isFalse();

            // 対象外（対照）: 撤廃済でない同一ドメイン FK が撤廃後も残存していること。
            // 注: 過去 wave で既に撤廃済の FK を対照に使うと誤って fail するため使用しない:
            //     ・error_reports → organizations（fk_error_reports_organization_id）は第一陣 V95.001 で撤廃済 → 対照に使わない。
            //     ・notifications → users（fk_notifications_user）は第二陣 V100.001 で撤廃済 → 対照に使わない。
            //   fk_rr_dwelling_unit は同一 F09.1 住民台帳ドメインの CASCADE で過去 wave での DROP は無く net-active のため対照に使う。
            assertThat(foreignKeyExists(c, "resident_registry", "fk_rr_dwelling_unit"))
                    .as("fk_rr_dwelling_unit（同一 F09.1 住民台帳ドメイン CASCADE）は撤廃対象外で残存すること").isTrue();

            // then-2: 既存子行が生存していること
            assertThat(rowExists(c, "error_reports", errorReportId))
                    .as("FK 撤廃後も error_reports 子行が生存していること").isTrue();
            assertThat(rowExists(c, "resident_registry", residentRegistryId))
                    .as("FK 撤廃後も resident_registry 子行が生存していること").isTrue();
            assertThat(rowExists(c, "surveys", surveyId))
                    .as("FK 撤廃後も surveys 子行が生存していること").isTrue();
            assertThat(rowExists(c, "todo_status_labels", statusLabelId))
                    .as("FK 撤廃後も todo_status_labels 子行が生存していること").isTrue();
            assertThat(rowExists(c, "notifications", notificationId))
                    .as("FK 撤廃後も notifications 子行が生存していること").isTrue();

            // then-3（中核）: 監査列でのみ参照される親 users を物理削除しても監査列が NULL 化されず孤児値を保持
            deleteUserPhysically(c, erUser);
            deleteUserPhysically(c, erResolvedBy);
            deleteUserPhysically(c, erAssignee);
            deleteUserPhysically(c, rrUser);
            deleteUserPhysically(c, rrVerifiedBy);
            deleteUserPhysically(c, surveyCreator);
            deleteUserPhysically(c, tslCreator);
            deleteUserPhysically(c, notifActor);

            assertThat(rowExists(c, "users", erUser)).as("親 users（error report user）が物理削除されたこと").isFalse();
            assertThat(rowExists(c, "users", notifActor)).as("親 users（notif actor）が物理削除されたこと").isFalse();

            assertThat(longColumn(c, "error_reports", "user_id", errorReportId))
                    .as("error_reports.user_id が SET NULL されず孤児 user_id を保持すること").isEqualTo(erUser);
            assertThat(longColumn(c, "error_reports", "resolved_by", errorReportId))
                    .as("error_reports.resolved_by が SET NULL されず孤児 user_id を保持すること").isEqualTo(erResolvedBy);
            assertThat(longColumn(c, "error_reports", "assignee_id", errorReportId))
                    .as("error_reports.assignee_id が SET NULL されず孤児 user_id を保持すること").isEqualTo(erAssignee);
            assertThat(longColumn(c, "resident_registry", "user_id", residentRegistryId))
                    .as("resident_registry.user_id が SET NULL されず孤児 user_id を保持すること").isEqualTo(rrUser);
            assertThat(longColumn(c, "resident_registry", "verified_by", residentRegistryId))
                    .as("resident_registry.verified_by が SET NULL されず孤児 user_id を保持すること").isEqualTo(rrVerifiedBy);
            assertThat(longColumn(c, "surveys", "created_by", surveyId))
                    .as("surveys.created_by が SET NULL されず孤児 user_id を保持すること").isEqualTo(surveyCreator);
            assertThat(longColumn(c, "todo_status_labels", "created_by", statusLabelId))
                    .as("todo_status_labels.created_by が SET NULL されず孤児 user_id を保持すること").isEqualTo(tslCreator);
            assertThat(longColumn(c, "notifications", "actor_id", notificationId))
                    .as("notifications.actor_id が SET NULL されず孤児 user_id を保持すること").isEqualTo(notifActor);
        }
    }

    // ── helpers ────────────────────────────────────────────────

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

    /** error_reports 行を挿入する。NOT NULL 列を全て充足し、撤廃対象3列（user_id/resolved_by/assignee_id）に user をセットする。 */
    private long insertErrorReport(Connection c, long userId, long resolvedBy, long assigneeId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO error_reports
                    (error_message, page_url, user_id, occurred_at, status, severity,
                     resolved_by, assignee_id, error_hash, occurrence_count, affected_user_count,
                     first_occurred_at, last_occurred_at, created_at, updated_at)
                VALUES ('監査FK撤廃テストエラー', 'https://example.com/test/3e', ?, NOW(), 'NEW', 'MEDIUM',
                        ?, ?, 'hash-3e-errorreport-setnull-test', 1, 1,
                        NOW(), NOW(), NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, userId);
            ps.setLong(2, resolvedBy);
            ps.setLong(3, assigneeId);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /**
     * dwelling_units 行を挿入する（resident_registry の親・fk_rr_dwelling_unit CASCADE）。
     * team_id/organization_id は nullable で各 FK は V95.001 で撤廃済のため scope_type/unit_number のみ設定で可。
     */
    private long insertDwellingUnit(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO dwelling_units
                    (scope_type, team_id, organization_id, unit_number, unit_type, resident_count,
                     created_at, updated_at)
                VALUES ('ORGANIZATION', NULL, NULL, '3E-101', 'STANDARD', 0, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** resident_registry 行を挿入する。NOT NULL 列（dwelling_unit_id/resident_type/last_name/first_name/move_in_date）を全て充足。 */
    private long insertResidentRegistry(Connection c, long dwellingUnitId, long userId, long verifiedBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO resident_registry
                    (dwelling_unit_id, user_id, resident_type, last_name, first_name, move_in_date,
                     is_primary, is_verified, verified_by, verified_at, created_at, updated_at)
                VALUES (?, ?, 'OWNER', '居住', '花子', '2020-01-01',
                        TRUE, TRUE, ?, NOW(), NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, dwellingUnitId);
            ps.setLong(2, userId);
            ps.setLong(3, verifiedBy);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** surveys 行を挿入する。NOT NULL 列（scope_type/scope_id/title）を充足。scope_id は FK 無しの任意 BIGINT。 */
    private long insertSurvey(Connection c, long createdBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO surveys
                    (scope_type, scope_id, title, status, created_by, version, created_at, updated_at)
                VALUES ('ORGANIZATION', 999999, '監査FK撤廃テストアンケート', 'DRAFT', ?, 0, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, createdBy);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /**
     * todo_status_labels 行を SYSTEM スコープで挿入する。
     * chk_tsl_scope_id_for_system（SYSTEM のとき scope_id IS NULL）と chk_tsl_scope/chk_tsl_bucket を全て満たす。
     */
    private long insertSystemStatusLabel(Connection c, long createdBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO todo_status_labels
                    (scope_type, scope_id, name, bucket, sort_order, is_system_default, created_by,
                     created_at, updated_at)
                VALUES ('SYSTEM', NULL, '監査FK撤廃テストラベル', 'OPEN', 0, FALSE, ?, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, createdBy);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /**
     * notifications 行を挿入する。NOT NULL 列（user_id/notification_type/title/source_type/scope_type）を全て充足。
     * 撤廃対象は actor_id（操作者）。user_id（受信者・NOT NULL）は別 user を充てる。
     */
    private long insertNotification(Connection c, long recipientUserId, long actorId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO notifications
                    (user_id, notification_type, priority, title, source_type, scope_type, actor_id,
                     is_read, created_at)
                VALUES (?, 'SYSTEM', 'NORMAL', '監査FK撤廃テスト通知', 'SYSTEM', 'ORGANIZATION', ?,
                        FALSE, NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, recipientUserId);
            ps.setLong(2, actorId);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private void deleteUserPhysically(Connection c, long userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM users WHERE id = ?")) {
            ps.setLong(1, userId);
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
