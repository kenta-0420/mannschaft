package com.mannschaft.app.event.migration;

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
 * <b>クロスドメインFK撤廃 第三陣C（event ドメイン）の番人テスト。</b>
 *
 * <p>V104.001 で event ドメインの「users を親とする ON DELETE SET NULL の監査/操作者カラム」FK 5件を撤廃only する:</p>
 * <ul>
 *   <li>{@code event_checkins.fk_event_checkins_checked_by}（checked_in_by → users SET NULL）</li>
 *   <li>{@code event_guest_invite_tokens.fk_event_guest_invite_tokens_created_by}（created_by → users SET NULL）</li>
 *   <li>{@code event_registrations.fk_event_registrations_approved_by}（approved_by → users SET NULL）</li>
 *   <li>{@code event_registrations.fk_event_registrations_user}（user_id → users SET NULL）</li>
 *   <li>{@code events.fk_events_created_by}（created_by → users SET NULL）</li>
 * </ul>
 *
 * <p>本テストが守る不変条件:</p>
 * <ol>
 *   <li>V104.001 の直前（V103.001）まで適用 → 監査列＝対象 user を持つ子行をシード。</li>
 *   <li>V104.001 直前時点で対象5FKが実在することを sanity 確認。</li>
 *   <li>残り（V104.001 含む）を適用しても既存データを壊さず成功する。</li>
 *   <li>V104.001 で対象5FKが撤廃される。</li>
 *   <li><b>親 users 行（監査列でのみ参照される user）を物理 DELETE しても監査列が NULL 化されず孤児 user_id 値を保持する</b>
 *       （＝SET NULL 撤廃only の肝・「誰がチェックイン/招待作成/承認/登録/イベント作成したか」の操作者証跡温存）。</li>
 * </ol>
 *
 * <p>方針: Spring を起動せず Testcontainers の実 MySQL 8.0 に {@link Flyway} を Java API で直接実行する。
 * Docker 未起動環境では {@code @EnabledIf} でスキップ（骨抜きにしない・根治原則）。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.event.migration.FlywayExistingDataEventSetNullFkMigrationTest#isDockerAvailable")
@DisplayName("Flyway 既存データ event 監査列 SET NULL FK撤廃（V104.001）番人テスト")
class FlywayExistingDataEventSetNullFkMigrationTest {

    /** V104.001 の直前バージョン（origin/main 全体最大）。ここまで適用してから既存行をシードする。 */
    private static final String PRE_V104_001_TARGET = "103.001";

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_event_setnull_fk")
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
    @DisplayName("既存子行を持つDBにV104.001適用_event監査列SET_NULL_FK5件撤廃_親user物理削除でも監査列が孤児user_idを保持")
    void 既存データを持つDBでV104_001がevent監査列SET_NULL_FK撤廃onlyで安全に適用される() throws Exception {
        // given: V104.001 の直前（V103.001）まで適用 ＝ 対象5FKはまだ生きている
        Flyway pre = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .target(MigrationVersion.fromVersion(PRE_V104_001_TARGET))
                .load();
        MigrateResult preResult = pre.migrate();
        assertThat(preResult.success).as("V103.001 までの適用が成功すること").isTrue();

        final long eventCreatedBy;   // events.created_by
        final long tokenCreatedBy;   // event_guest_invite_tokens.created_by
        final long checkinBy;        // event_checkins.checked_in_by
        final long regUser;          // event_registrations.user_id
        final long regApprovedBy;    // event_registrations.approved_by
        final long eventId;
        final long ticketTypeId;
        final long tokenId;
        final long checkinId;
        final long registrationId;

        try (Connection c = conn()) {
            // sanity: V103.001 時点で対象5FKが実在すること
            assertThat(foreignKeyExists(c, "event_checkins", "fk_event_checkins_checked_by"))
                    .as("V103.001 時点で fk_event_checkins_checked_by が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "event_guest_invite_tokens", "fk_event_guest_invite_tokens_created_by"))
                    .as("V103.001 時点で fk_event_guest_invite_tokens_created_by が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "event_registrations", "fk_event_registrations_approved_by"))
                    .as("V103.001 時点で fk_event_registrations_approved_by が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "event_registrations", "fk_event_registrations_user"))
                    .as("V103.001 時点で fk_event_registrations_user が実在すること").isTrue();
            assertThat(foreignKeyExists(c, "events", "fk_events_created_by"))
                    .as("V103.001 時点で fk_events_created_by が実在すること").isTrue();

            eventCreatedBy = insertUser(c, "event-createdby@example.com");
            tokenCreatedBy = insertUser(c, "token-createdby@example.com");
            checkinBy = insertUser(c, "checkin-by@example.com");
            regUser = insertUser(c, "reg-user@example.com");
            regApprovedBy = insertUser(c, "reg-approvedby@example.com");

            // 親: events（scope_type/scope_id/slug NOT NULL・schedule_id は nullable で省略）
            eventId = insertEvent(c, "監査FK撤廃テストイベント", eventCreatedBy);
            // 親: event_ticket_types（event_registrations.ticket_type_id → RESTRICT）
            ticketTypeId = insertTicketType(c, eventId);

            // 子: event_guest_invite_tokens（created_by 監査列）
            tokenId = insertInviteToken(c, eventId, tokenCreatedBy);
            // 子: event_checkins（checked_in_by 監査列・点呼式 ROLL_CALL で ticket_id=NULL／chk_event_checkins_ticket_by_type 充足）
            checkinId = insertRollCallCheckin(c, eventId, checkinBy);
            // 子: event_registrations（user_id / approved_by 監査列）
            registrationId = insertRegistration(c, eventId, ticketTypeId, regUser, regApprovedBy);
        }

        // when: 残りのマイグレーション（V104.001 含む）を適用する
        Flyway rest = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .load();
        MigrateResult restResult = rest.migrate();
        assertThat(restResult.success).as("V104.001 を含む残りのマイグレーションが成功すること").isTrue();

        try (Connection c = conn()) {
            // then-1: 対象5FKが撤廃された
            assertThat(foreignKeyExists(c, "event_checkins", "fk_event_checkins_checked_by"))
                    .as("V104.001 で fk_event_checkins_checked_by が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "event_guest_invite_tokens", "fk_event_guest_invite_tokens_created_by"))
                    .as("V104.001 で fk_event_guest_invite_tokens_created_by が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "event_registrations", "fk_event_registrations_approved_by"))
                    .as("V104.001 で fk_event_registrations_approved_by が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "event_registrations", "fk_event_registrations_user"))
                    .as("V104.001 で fk_event_registrations_user が撤廃されること").isFalse();
            assertThat(foreignKeyExists(c, "events", "fk_events_created_by"))
                    .as("V104.001 で fk_events_created_by が撤廃されること").isFalse();

            // 対象外: user_id をカバーする名前付き index は FK 撤廃後も残存していること（逆引き継続可）
            assertThat(indexExists(c, "event_registrations", "idx_er_user_event"))
                    .as("idx_er_user_event が FK 撤廃後も残存すること（user_id 引き継続可）").isTrue();

            // then-2: 既存子行が生存していること
            assertThat(rowExists(c, "events", eventId)).as("FK 撤廃後も events 親行が生存していること").isTrue();
            assertThat(rowExists(c, "event_guest_invite_tokens", tokenId))
                    .as("FK 撤廃後も event_guest_invite_tokens 子行が生存していること").isTrue();
            assertThat(rowExists(c, "event_checkins", checkinId))
                    .as("FK 撤廃後も event_checkins 子行が生存していること").isTrue();
            assertThat(rowExists(c, "event_registrations", registrationId))
                    .as("FK 撤廃後も event_registrations 子行が生存していること").isTrue();

            // then-3（中核）: 監査列でのみ参照される親 users を物理削除しても監査列が NULL 化されず孤児値を保持
            deleteUserPhysically(c, eventCreatedBy);
            deleteUserPhysically(c, tokenCreatedBy);
            deleteUserPhysically(c, checkinBy);
            deleteUserPhysically(c, regUser);
            deleteUserPhysically(c, regApprovedBy);

            assertThat(rowExists(c, "users", eventCreatedBy)).as("親 users（event created_by）が物理削除されたこと").isFalse();
            assertThat(rowExists(c, "users", tokenCreatedBy)).as("親 users（token created_by）が物理削除されたこと").isFalse();
            assertThat(rowExists(c, "users", checkinBy)).as("親 users（checkin by）が物理削除されたこと").isFalse();
            assertThat(rowExists(c, "users", regUser)).as("親 users（reg user）が物理削除されたこと").isFalse();
            assertThat(rowExists(c, "users", regApprovedBy)).as("親 users（reg approved_by）が物理削除されたこと").isFalse();

            assertThat(longColumn(c, "events", "created_by", eventId))
                    .as("events.created_by が SET NULL されず孤児 user_id を保持すること")
                    .isEqualTo(eventCreatedBy);
            assertThat(longColumn(c, "event_guest_invite_tokens", "created_by", tokenId))
                    .as("event_guest_invite_tokens.created_by が SET NULL されず孤児 user_id を保持すること")
                    .isEqualTo(tokenCreatedBy);
            assertThat(longColumn(c, "event_checkins", "checked_in_by", checkinId))
                    .as("event_checkins.checked_in_by が SET NULL されず孤児 user_id を保持すること")
                    .isEqualTo(checkinBy);
            assertThat(longColumn(c, "event_registrations", "user_id", registrationId))
                    .as("event_registrations.user_id が SET NULL されず孤児 user_id を保持すること")
                    .isEqualTo(regUser);
            assertThat(longColumn(c, "event_registrations", "approved_by", registrationId))
                    .as("event_registrations.approved_by が SET NULL されず孤児 user_id を保持すること")
                    .isEqualTo(regApprovedBy);
        }
    }

    // ── helpers ────────────────────────────────────────────────

    private long insertUser(Connection c, String email) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO users
                    (email, last_name, first_name, display_name, status, created_at, updated_at)
                VALUES (?, 'イベント', '担当', 'イベント担当', 'ACTIVE', NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, email);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /**
     * events 親行を挿入する。scope_type / scope_id / slug は NOT NULL（default 無し）。
     * schedule_id（→ schedules RESTRICT）は nullable なので省略し、他ドメイン親を要しない構成にする。
     * created_by は本テストの監査列。
     */
    private long insertEvent(Connection c, String slug, long createdBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO events
                    (scope_type, scope_id, slug, status, created_by, created_at, updated_at)
                VALUES ('TEAM', 1, ?, 'DRAFT', ?, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, slug);
            ps.setLong(2, createdBy);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long insertTicketType(Connection c, long eventId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO event_ticket_types
                    (event_id, name, price, created_at, updated_at)
                VALUES (?, '一般チケット', 0, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, eventId);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long insertInviteToken(Connection c, long eventId, long createdBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO event_guest_invite_tokens
                    (event_id, token, label, created_by, created_at, updated_at)
                VALUES (?, ?, '監査FK撤廃テストトークン', ?, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, eventId);
            ps.setString(2, java.util.UUID.randomUUID().toString());
            ps.setLong(3, createdBy);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /**
     * event_checkins 子行を点呼式（ROLL_CALL）で挿入する。
     * V70.008 の chk_event_checkins_ticket_by_type により、チケットレス（ROLL_CALL）は ticket_id = NULL でなければならない。
     * checked_in_by が本テストの監査列。
     */
    private long insertRollCallCheckin(Connection c, long eventId, long checkedInBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO event_checkins
                    (event_id, ticket_id, checkin_type, checked_in_by, checked_in_at, created_at)
                VALUES (?, NULL, 'ROLL_CALL', ?, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, eventId);
            ps.setLong(2, checkedInBy);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long insertRegistration(Connection c, long eventId, long ticketTypeId, long userId, long approvedBy)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO event_registrations
                    (event_id, user_id, ticket_type_id, status, approved_by, created_at, updated_at)
                VALUES (?, ?, ?, 'APPROVED', ?, NOW(), NOW())
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, eventId);
            ps.setLong(2, userId);
            ps.setLong(3, ticketTypeId);
            ps.setLong(4, approvedBy);
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

    private static boolean indexExists(Connection c, String table, String indexName) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = ?
                  AND index_name = ?
                """)) {
            ps.setString(1, table);
            ps.setString(2, indexName);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }
}
