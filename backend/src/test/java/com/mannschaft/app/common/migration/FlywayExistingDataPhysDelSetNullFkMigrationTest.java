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
 * <b>クロスドメインFK撤廃 第四陣D（Phase 4-D・ラスト）の番人テスト。</b>
 *
 * <p>V113.001 で「他ドメインの実テーブル（proxy_input_records / timetable_slots / timetable_changes /
 * activity_template_fields）を ON DELETE SET NULL で参照する群2＝構造参照のクロスドメインFK 7件」を撤廃only する。
 * 本 PR-4d の特殊性は「参照先テーブルが実際に物理削除される運用がある」点（proxy=保持期限ジョブ/退会purge、
 * timetable_slots=再構築deleteAll、timetable_changes=取消delete、activity_template_fields=テンプレ編集delete）。
 * それでも参照元の外部キー列は write-only / 不活性（getter/JOIN/query 0件）であり、孤児化しても漏洩/NPE/誤集計が
 * 発生しないため撤廃only（孤児保持）が安全。本テストはその不変条件を厳密に検証する:</p>
 * <ol>
 *   <li>V113.001 の直前（V111.001）まで適用 → 参照先行＋参照元行（外部キー列に参照先 id をセット）をシード。</li>
 *   <li>残り（V113.001 含む）を適用しても既存データを壊さず成功する。</li>
 *   <li>V113.001 で対象7FKが撤廃される。</li>
 *   <li><b>参照先テーブルの行を（テスト内で）物理 DELETE しても、参照元の外部キー列が NULL 化されず孤児値を保持し続ける</b>
 *       （＝SET NULL 撤廃only の肝）。本 PR-4d は参照先が実際に物理削除されるため、この検証が特に本質的である。</li>
 * </ol>
 *
 * <p>方針: Spring を起動せず Testcontainers の実 MySQL 8.0 に {@link Flyway} を Java API で直接実行する。
 * Docker 未起動環境では {@code @EnabledIf} でスキップ（骨抜きにしない・根治原則）。
 * 物理削除する参照先テーブルは、本テストでは「他テーブルから ON DELETE RESTRICT で被参照される余計な子行」を
 * 一切シードしない（撤廃対象7FKの参照元のみをシードし、全て SET NULL なので削除を阻害しない）。
 * これにより参照先の物理 DELETE は RESTRICT に阻まれず成立する。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.common.migration.FlywayExistingDataPhysDelSetNullFkMigrationTest#isDockerAvailable")
@DisplayName("Flyway 既存データ proxy_input_records/timetable_slots/timetable_changes/activity_template_fields 参照 SET NULL FK撤廃（V113.001）番人テスト")
class FlywayExistingDataPhysDelSetNullFkMigrationTest {

    /** V113.001 の直前の版（origin/main 最大 = V111.001）。ここまで適用して参照元/先をシードする。 */
    private static final String PRE_V113_001_TARGET = "111.001";

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_phase4d_setnull_fk")
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
                .target(MigrationVersion.fromVersion(PRE_V113_001_TARGET))
                .load();
        MigrateResult preResult = pre.migrate();
        assertThat(preResult.success).as("V111.001 までの適用が成功すること").isTrue();
    }

    private void migrateRemaining() {
        Flyway rest = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .load();
        MigrateResult restResult = rest.migrate();
        assertThat(restResult.success).as("V113.001 を含む残りのマイグレーションが成功すること").isTrue();
    }

    /**
     * 1回の pre→seed→migrate サイクルで7件すべてを検証する。
     *
     * <p>注意: 同一DBを共有するため複数 @Test に分けると、2本目以降は既に V113.001 まで適用済みとなり
     * 「FK実在 sanity（pre-state）」が成立しなくなる。よって1メソッドに集約し、
     * V111.001 時点での全7FK実在 → 7件ぶんのシード → 残り適用 → 全7FK撤廃 + 7件の孤児保持
     * を一気通貫で検証する。</p>
     */
    @Test
    @DisplayName("V111.001で全7FK実在_V113.001適用で全7FK撤廃_参照先物理削除でも参照元の外部キー列が孤児値を保持")
    void 既存データを持つDBでV113_001が群2SET_NULL_FK7件を撤廃onlyで安全に適用される() throws Exception {
        migrateToPreTarget();

        // 参照先（target）id
        final long proxyRecordForAnnouncement;
        final long proxyRecordForCirculation;
        final long proxyRecordForParking;
        final long proxyRecordForShift;
        final long timetableSlotId;
        final long timetableChangeId;
        final long activityFieldId;

        // 参照元（referencing source）id ＋ 撤廃対象 FK 列にセットする値
        final long announcementReadStatusId; // announcement_read_status.proxy_input_record_id = proxyRecordForAnnouncement
        final long circulationRecipientId;   // circulation_recipients.proxy_input_record_id = proxyRecordForCirculation
        final long parkingApplicationId;     // parking_applications.proxy_input_record_id = proxyRecordForParking
        final long shiftRequestId;           // shift_requests.proxy_input_record_id = proxyRecordForShift
        final long parSlotId;                // period_attendance_records.timetable_slot_id = timetableSlotId
        final long parChangeId;              // period_attendance_records.timetable_change_id = timetableChangeId
        final long performanceMetricId;      // performance_metrics.linked_activity_field_id = activityFieldId

        try (Connection c = conn()) {
            // ── given: V111.001 時点では対象7FKが全て実在すること（pre-state sanity）──
            assertThat(foreignKeyExists(c, "announcement_read_status", "fk_announcement_read_status_proxy"))
                    .as("V111.001 時点では fk_announcement_read_status_proxy が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "circulation_recipients", "fk_circulation_recipients_proxy"))
                    .as("V111.001 時点では fk_circulation_recipients_proxy が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "parking_applications", "fk_parking_applications_proxy"))
                    .as("V111.001 時点では fk_parking_applications_proxy が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "shift_requests", "fk_shift_requests_proxy"))
                    .as("V111.001 時点では fk_shift_requests_proxy が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "period_attendance_records", "fk_par_timetable_slot"))
                    .as("V111.001 時点では fk_par_timetable_slot が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "period_attendance_records", "fk_par_timetable_change"))
                    .as("V111.001 時点では fk_par_timetable_change が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "performance_metrics", "fk_pm_linked_field"))
                    .as("V111.001 時点では fk_pm_linked_field が実在すること").isTrue();

            // ── 共通の親 ──
            long subjectUserId = insertUser(c, "p4d-subject@example.com");
            long proxyUserId = insertUser(c, "p4d-proxy@example.com");
            long organizationId = insertOrganization(c, "p4d-org-001");
            long teamId = insertTeam(c, "p4d-team-001");

            // ── 1〜4. proxy_input_records（参照先）を4本シード（各参照元ごとに独立検証するため別行）──
            // proxy_input_records は consent(RESTRICT)/subject_user(FK)/proxy_user(FK) と多数の NOT NULL 列を持つ。
            long consentId = insertProxyInputConsent(c, subjectUserId, proxyUserId, organizationId);
            proxyRecordForAnnouncement = insertProxyInputRecord(c, consentId, subjectUserId, proxyUserId, "ANNOUNCEMENT_READ", 1001L);
            proxyRecordForCirculation = insertProxyInputRecord(c, consentId, subjectUserId, proxyUserId, "CIRCULATION_STAMP", 1002L);
            proxyRecordForParking = insertProxyInputRecord(c, consentId, subjectUserId, proxyUserId, "PARKING_APPLICATION", 1003L);
            proxyRecordForShift = insertProxyInputRecord(c, consentId, subjectUserId, proxyUserId, "SHIFT_REQUEST", 1004L);

            // 1. announcement_read_status.proxy_input_record_id → proxy_input_records
            long announcementFeedId = insertAnnouncementFeed(c, organizationId);
            announcementReadStatusId = insertAnnouncementReadStatus(c, announcementFeedId, subjectUserId, proxyRecordForAnnouncement);

            // 2. circulation_recipients.proxy_input_record_id → proxy_input_records
            long circulationDocumentId = insertCirculationDocument(c, organizationId, proxyUserId);
            circulationRecipientId = insertCirculationRecipient(c, circulationDocumentId, subjectUserId, proxyRecordForCirculation);

            // 3. parking_applications.proxy_input_record_id → proxy_input_records
            //    parking_applications は space_id/user_id/vehicle_id が NOT NULL だが FK 制約なし（素のカラム）。
            long parkingSpaceId = insertParkingSpace(c, organizationId, proxyUserId);
            long vehicleId = insertRegisteredVehicle(c, subjectUserId);
            parkingApplicationId = insertParkingApplication(c, parkingSpaceId, subjectUserId, vehicleId, proxyRecordForParking);

            // 4. shift_requests.proxy_input_record_id → proxy_input_records
            long shiftScheduleId = insertShiftSchedule(c, teamId);
            shiftRequestId = insertShiftRequest(c, shiftScheduleId, subjectUserId, proxyRecordForShift);

            // ── 5+6. period_attendance_records.timetable_slot_id / timetable_change_id → timetable_slots / timetable_changes ──
            // timetable chain: timetable_terms(RESTRICT)→timetables(CASCADE)→{timetable_slots, timetable_changes}。
            long timetableTermId = insertTimetableTerm(c, teamId);
            long timetableId = insertTimetable(c, teamId, timetableTermId, proxyUserId);
            timetableSlotId = insertTimetableSlot(c, timetableId);
            timetableChangeId = insertTimetableChange(c, timetableId);
            // period_attendance_records は team_id(CASCADE)/student_user_id(CASCADE)/recorded_by(RESTRICT) NOT NULL ＋ uq_par。
            // timetable_slot_id 検証用と timetable_change_id 検証用に別行をシード（period_number を変えて uq_par 衝突回避）。
            parSlotId = insertPeriodAttendanceRecord(c, teamId, subjectUserId, (short) 1, timetableSlotId, null);
            parChangeId = insertPeriodAttendanceRecord(c, teamId, subjectUserId, (short) 2, null, timetableChangeId);

            // ── 7. performance_metrics.linked_activity_field_id → activity_template_fields ──
            // activity chain: activity_templates(created_by RESTRICT)→activity_template_fields(CASCADE)。
            long activityTemplateId = insertActivityTemplate(c, teamId, proxyUserId);
            activityFieldId = insertActivityTemplateField(c, activityTemplateId);
            // performance_metrics は team_id(FK teams)/name NOT NULL ＋ linked_activity_field_id（撤廃対象列）。
            performanceMetricId = insertPerformanceMetric(c, teamId, activityFieldId);
        }

        // ── when: 残り（V113.001 含む）を適用 ──
        migrateRemaining();

        try (Connection c = conn()) {
            // ── then-1: 対象7FKが全て撤廃された ──
            assertThat(foreignKeyExists(c, "announcement_read_status", "fk_announcement_read_status_proxy"))
                    .as("V113.001 で fk_announcement_read_status_proxy が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "circulation_recipients", "fk_circulation_recipients_proxy"))
                    .as("V113.001 で fk_circulation_recipients_proxy が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "parking_applications", "fk_parking_applications_proxy"))
                    .as("V113.001 で fk_parking_applications_proxy が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "shift_requests", "fk_shift_requests_proxy"))
                    .as("V113.001 で fk_shift_requests_proxy が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "period_attendance_records", "fk_par_timetable_slot"))
                    .as("V113.001 で fk_par_timetable_slot が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "period_attendance_records", "fk_par_timetable_change"))
                    .as("V113.001 で fk_par_timetable_change が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "performance_metrics", "fk_pm_linked_field"))
                    .as("V113.001 で fk_pm_linked_field が撤廃されること").isFalse();

            // ── then-2（中核）: 各参照先テーブルの行を物理削除しても、参照元の外部キー列が NULL 化されず孤児値を保持 ──

            // 1. proxy_input_record 物理削除 → announcement_read_status.proxy_input_record_id 孤児保持
            deleteRow(c, "proxy_input_records", proxyRecordForAnnouncement);
            assertThat(rowExists(c, "proxy_input_records", proxyRecordForAnnouncement))
                    .as("参照先 proxy_input_record(announcement) が物理削除されたこと").isFalse();
            assertThat(longColumn(c, "announcement_read_status", "proxy_input_record_id", announcementReadStatusId))
                    .as("announcement_read_status.proxy_input_record_id が SET NULL されず孤児値を保持すること")
                    .isEqualTo(proxyRecordForAnnouncement);

            // 2. proxy_input_record 物理削除 → circulation_recipients.proxy_input_record_id 孤児保持
            deleteRow(c, "proxy_input_records", proxyRecordForCirculation);
            assertThat(rowExists(c, "proxy_input_records", proxyRecordForCirculation))
                    .as("参照先 proxy_input_record(circulation) が物理削除されたこと").isFalse();
            assertThat(longColumn(c, "circulation_recipients", "proxy_input_record_id", circulationRecipientId))
                    .as("circulation_recipients.proxy_input_record_id が SET NULL されず孤児値を保持すること")
                    .isEqualTo(proxyRecordForCirculation);

            // 3. proxy_input_record 物理削除 → parking_applications.proxy_input_record_id 孤児保持
            deleteRow(c, "proxy_input_records", proxyRecordForParking);
            assertThat(rowExists(c, "proxy_input_records", proxyRecordForParking))
                    .as("参照先 proxy_input_record(parking) が物理削除されたこと").isFalse();
            assertThat(longColumn(c, "parking_applications", "proxy_input_record_id", parkingApplicationId))
                    .as("parking_applications.proxy_input_record_id が SET NULL されず孤児値を保持すること")
                    .isEqualTo(proxyRecordForParking);

            // 4. proxy_input_record 物理削除 → shift_requests.proxy_input_record_id 孤児保持
            deleteRow(c, "proxy_input_records", proxyRecordForShift);
            assertThat(rowExists(c, "proxy_input_records", proxyRecordForShift))
                    .as("参照先 proxy_input_record(shift) が物理削除されたこと").isFalse();
            assertThat(longColumn(c, "shift_requests", "proxy_input_record_id", shiftRequestId))
                    .as("shift_requests.proxy_input_record_id が SET NULL されず孤児値を保持すること")
                    .isEqualTo(proxyRecordForShift);

            // 5. timetable_slot 物理削除 → period_attendance_records.timetable_slot_id 孤児保持
            deleteRow(c, "timetable_slots", timetableSlotId);
            assertThat(rowExists(c, "timetable_slots", timetableSlotId))
                    .as("参照先 timetable_slot が物理削除されたこと").isFalse();
            assertThat(longColumn(c, "period_attendance_records", "timetable_slot_id", parSlotId))
                    .as("period_attendance_records.timetable_slot_id が SET NULL されず孤児値を保持すること")
                    .isEqualTo(timetableSlotId);

            // 6. timetable_change 物理削除 → period_attendance_records.timetable_change_id 孤児保持
            deleteRow(c, "timetable_changes", timetableChangeId);
            assertThat(rowExists(c, "timetable_changes", timetableChangeId))
                    .as("参照先 timetable_change が物理削除されたこと").isFalse();
            assertThat(longColumn(c, "period_attendance_records", "timetable_change_id", parChangeId))
                    .as("period_attendance_records.timetable_change_id が SET NULL されず孤児値を保持すること")
                    .isEqualTo(timetableChangeId);

            // 7. activity_template_field 物理削除 → performance_metrics.linked_activity_field_id 孤児保持
            deleteRow(c, "activity_template_fields", activityFieldId);
            assertThat(rowExists(c, "activity_template_fields", activityFieldId))
                    .as("参照先 activity_template_field が物理削除されたこと").isFalse();
            assertThat(longColumn(c, "performance_metrics", "linked_activity_field_id", performanceMetricId))
                    .as("performance_metrics.linked_activity_field_id が SET NULL されず孤児値を保持すること")
                    .isEqualTo(activityFieldId);
        }
    }

    // ── 共通 seed helpers ──────────────────────────────────────

    private long insertUser(Connection c, String email) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO users
                    (email, last_name, first_name, display_name, status, created_at, updated_at)
                VALUES (?, '第四', 'D郎', '第四D郎', 'ACTIVE', NOW(), NOW())
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
                VALUES ('第四陣D監査組織', 'OTHER', ?, NOW(), NOW())
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
                VALUES ('第四陣Dチーム', ?, 'PUBLIC', NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, slug);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    // ── 1〜4. proxy_input_consents / proxy_input_records ──

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
     * UNIQUE uq_pir_idempotent(consent_id, target_entity_type, target_entity_id) を避けるため target_entity_type を分ける。
     */
    private long insertProxyInputRecord(Connection c, long consentId, long subjectUserId, long proxyUserId,
                                        String targetEntityType, long targetEntityId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO proxy_input_records
                    (proxy_input_consent_id, subject_user_id, proxy_user_id, feature_scope,
                     target_entity_type, target_entity_id, input_source, original_storage_location, created_at)
                VALUES (?, ?, ?, 'SCHEDULE_ATTENDANCE', ?, ?, 'PAPER_FORM', '第四陣D倉庫A-1', NOW())
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

    // ── 1. announcement_feeds / announcement_read_status ──

    /** announcement_feeds 行を挿入（announcement_read_status.announcement_feed_id CASCADE の親）。scope/source/title_cache NOT NULL。 */
    private long insertAnnouncementFeed(Connection c, long organizationId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO announcement_feeds
                    (scope_type, scope_id, source_type, source_id, title_cache, created_at, updated_at)
                VALUES ('ORGANIZATION', ?, 'BLOG_POST', 9001, '第四陣Dお知らせ', NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, organizationId);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** announcement_read_status 行を挿入。announcement_feed_id(CASCADE)/user_id(CASCADE) NOT NULL。proxy_input_record_id（撤廃対象列）。 */
    private long insertAnnouncementReadStatus(Connection c, long feedId, long userId, long proxyRecordId)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO announcement_read_status
                    (announcement_feed_id, user_id, read_at, is_proxy_confirmed, proxy_input_record_id)
                VALUES (?, ?, NOW(), 1, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, feedId);
            ps.setLong(2, userId);
            ps.setLong(3, proxyRecordId);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    // ── 2. circulation_documents / circulation_recipients ──

    /** circulation_documents 行を挿入（circulation_recipients.document_id CASCADE の親）。scope/created_by(RESTRICT)/title/body NOT NULL。 */
    private long insertCirculationDocument(Connection c, long organizationId, long createdBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO circulation_documents
                    (scope_type, scope_id, created_by, title, body, created_at, updated_at)
                VALUES ('ORGANIZATION', ?, ?, '第四陣D回覧', '本文', NOW(), NOW())
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

    /** circulation_recipients 行を挿入。document_id(CASCADE)/user_id(CASCADE) NOT NULL。proxy_input_record_id（撤廃対象列）。 */
    private long insertCirculationRecipient(Connection c, long documentId, long userId, long proxyRecordId)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO circulation_recipients
                    (document_id, user_id, sort_order, status, is_proxy_confirmed, proxy_input_record_id, created_at, updated_at)
                VALUES (?, ?, 0, 'PENDING', 1, ?, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, documentId);
            ps.setLong(2, userId);
            ps.setLong(3, proxyRecordId);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    // ── 3. parking_spaces / registered_vehicles / parking_applications ──

    /** parking_spaces 行を挿入（parking_applications.space_id は FK制約なしだが現実的な親として用意）。scope/space_number/space_type/created_by NOT NULL。 */
    private long insertParkingSpace(Connection c, long organizationId, long createdBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO parking_spaces
                    (scope_type, scope_id, space_number, space_type, created_by, created_at, updated_at)
                VALUES ('ORGANIZATION', ?, 'P4D-01', 'OUTDOOR', ?, NOW(), NOW())
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

    /** registered_vehicles 行を挿入（parking_applications.vehicle_id は FK制約なしだが現実的な親として用意）。user_id/vehicle_type/plate_number(VARBINARY)/plate_number_hash NOT NULL。 */
    private long insertRegisteredVehicle(Connection c, long userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO registered_vehicles
                    (user_id, vehicle_type, plate_number, plate_number_hash, created_at, updated_at)
                VALUES (?, 'CAR', ?, ?, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, userId);
            ps.setBytes(2, "ENC-P4D-PLATE".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            ps.setString(3, "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"); // CHAR(64) ちょうど64文字(16×4)
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** parking_applications 行を挿入。space_id/user_id/vehicle_id NOT NULL（FK制約なし）。proxy_input_record_id（撤廃対象列・FK）。 */
    private long insertParkingApplication(Connection c, long spaceId, long userId, long vehicleId, long proxyRecordId)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO parking_applications
                    (space_id, user_id, vehicle_id, source_type, status, is_proxy_input, proxy_input_record_id, created_at)
                VALUES (?, ?, ?, 'VACANCY', 'PENDING', 1, ?, NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, spaceId);
            ps.setLong(2, userId);
            ps.setLong(3, vehicleId);
            ps.setLong(4, proxyRecordId);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    // ── 4. shift_schedules / shift_requests ──

    /** shift_schedules 行を挿入（shift_requests.schedule_id CASCADE の親）。team_id(CASCADE)/title/start_date/end_date NOT NULL。 */
    private long insertShiftSchedule(Connection c, long teamId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO shift_schedules
                    (team_id, title, start_date, end_date, created_at, updated_at)
                VALUES (?, '第四陣Dシフト表', CURDATE(), CURDATE() + INTERVAL 7 DAY, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, teamId);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /**
     * shift_requests 行を挿入。schedule_id(CASCADE)/user_id(CASCADE)/slot_date/preference NOT NULL。
     * CHECK chk_shift_requests_preference(PREFERRED/AVAILABLE/WEAK_REST/STRONG_REST/ABSOLUTE_REST)。
     * slot_id は任意（FK・NULL 可）。proxy_input_record_id（撤廃対象列）。
     */
    private long insertShiftRequest(Connection c, long scheduleId, long userId, long proxyRecordId)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO shift_requests
                    (schedule_id, user_id, slot_date, preference, is_proxy_input, proxy_input_record_id,
                     submitted_at, updated_at)
                VALUES (?, ?, CURDATE(), 'AVAILABLE', 1, ?, NOW(), NOW())
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

    // ── 5+6. timetable_terms / timetables / timetable_slots / timetable_changes / period_attendance_records ──

    /** timetable_terms 行を挿入（timetables.term_id RESTRICT の親）。chk_term_scope（team_id XOR organization_id）/chk_term_date_order。 */
    private long insertTimetableTerm(Connection c, long teamId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO timetable_terms
                    (team_id, academic_year, name, start_date, end_date, created_at, updated_at)
                VALUES (?, 2026, '第四陣D学期', '2026-04-01', '2026-07-31', NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, teamId);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** timetables 行を挿入（timetable_slots/timetable_changes.timetable_id CASCADE の親）。team_id(CASCADE)/term_id(RESTRICT)/name/effective_from NOT NULL。 */
    private long insertTimetable(Connection c, long teamId, long termId, long createdBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO timetables
                    (team_id, term_id, name, effective_from, created_by, created_at, updated_at)
                VALUES (?, ?, '第四陣D時間割', CURDATE(), ?, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, teamId);
            ps.setLong(2, termId);
            ps.setLong(3, createdBy);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** timetable_slots 行を挿入（参照先）。timetable_id(CASCADE)/day_of_week/period_number/week_pattern/subject_name NOT NULL ＋ uq_ts。 */
    private long insertTimetableSlot(Connection c, long timetableId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO timetable_slots
                    (timetable_id, day_of_week, period_number, week_pattern, subject_name, created_at, updated_at)
                VALUES (?, 'MON', 1, 'EVERY', '第四陣D国語', NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, timetableId);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** timetable_changes 行を挿入（参照先）。timetable_id(CASCADE)/target_date/change_type NOT NULL ＋ uq_tc。 */
    private long insertTimetableChange(Connection c, long timetableId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO timetable_changes
                    (timetable_id, target_date, period_number, change_type, subject_name, created_at, updated_at)
                VALUES (?, CURDATE(), 1, 'REPLACE', '第四陣D数学', NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, timetableId);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /**
     * period_attendance_records 行を挿入。team_id(CASCADE)/student_user_id(CASCADE)/attendance_date/period_number/
     * subject_name/recorded_by(RESTRICT) NOT NULL ＋ uq_par(team, student, date, period)。
     * timetable_slot_id / timetable_change_id（いずれも撤廃対象列）を任意でセット。
     */
    private long insertPeriodAttendanceRecord(Connection c, long teamId, long studentUserId, short periodNumber,
                                              Long timetableSlotId, Long timetableChangeId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO period_attendance_records
                    (team_id, student_user_id, attendance_date, period_number, timetable_slot_id, timetable_change_id,
                     subject_name, status, recorded_by, recorded_at, updated_at)
                VALUES (?, ?, CURDATE(), ?, ?, ?, '第四陣D教科', 'ATTENDING', ?, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, teamId);
            ps.setLong(2, studentUserId);
            ps.setShort(3, periodNumber);
            if (timetableSlotId == null) {
                ps.setNull(4, java.sql.Types.BIGINT);
            } else {
                ps.setLong(4, timetableSlotId);
            }
            if (timetableChangeId == null) {
                ps.setNull(5, java.sql.Types.BIGINT);
            } else {
                ps.setLong(5, timetableChangeId);
            }
            ps.setLong(6, studentUserId); // recorded_by（記録者・本テストでは便宜上 student と同一でよい）
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    // ── 7. activity_templates / activity_template_fields / performance_metrics ──

    /** activity_templates 行を挿入（activity_template_fields.template_id CASCADE の親）。scope/name/created_by(RESTRICT) NOT NULL。 */
    private long insertActivityTemplate(Connection c, long teamId, long createdBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO activity_templates
                    (scope_type, scope_id, name, created_by, created_at, updated_at)
                VALUES ('TEAM', ?, '第四陣D活動テンプレ', ?, NOW(), NOW())
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

    /** activity_template_fields 行を挿入（参照先）。template_id(CASCADE)/field_key/field_label/field_type NOT NULL ＋ uq_atf_key。 */
    private long insertActivityTemplateField(Connection c, long templateId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO activity_template_fields
                    (template_id, field_key, field_label, field_type)
                VALUES (?, 'p4d_field', '第四陣Dフィールド', 'NUMBER')
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, templateId);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** performance_metrics 行を挿入。team_id(FK teams)/name NOT NULL。linked_activity_field_id（撤廃対象列・FK）。 */
    private long insertPerformanceMetric(Connection c, long teamId, long linkedActivityFieldId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO performance_metrics
                    (team_id, name, linked_activity_field_id, created_at, updated_at)
                VALUES (?, '第四陣D指標', ?, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, teamId);
            ps.setLong(2, linkedActivityFieldId);
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
