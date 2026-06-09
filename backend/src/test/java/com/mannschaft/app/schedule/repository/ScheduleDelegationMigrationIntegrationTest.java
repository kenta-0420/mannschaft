package com.mannschaft.app.schedule.repository;

import org.flywaydb.core.Flyway;
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
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F03.10 第一陣 — {@code schedule_delegations} テーブルの DDL 挙動を <b>実 Flyway スキーマ</b>に対して検証する。
 *
 * <h2>なぜ Flyway 直適用 + JDBC なのか</h2>
 * <p>本プロジェクトの通常の統合テスト（{@code application-test.yml}）は {@code ddl-auto=create} +
 * {@code flyway.enabled=false} で動作する。すなわちスキーマは Entity から生成され、Flyway DDL は実行されない。
 * 本テストが検証したい {@code active_delegator_marker}（DB 生成カラム）+ {@code UNIQUE KEY uq_active_delegation}
 * は <b>DDL でしか定義されず Entity にはマップしていない</b>ため、Hibernate 生成スキーマでは再現できない。
 * よって {@link FlywayFromScratchMigrationTest と同方式で} 実 MySQL に Flyway を直接適用し、
 * 生 JDBC で生成カラム + UNIQUE の挙動を検証する。</p>
 *
 * <p>Docker 未起動環境では {@code @EnabledIf} によりスキップされる。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.schedule.repository.ScheduleDelegationMigrationIntegrationTest#isDockerAvailable")
@DisplayName("schedule_delegations DDL 挙動テスト（active_delegator_marker + UNIQUE）")
class ScheduleDelegationMigrationIntegrationTest {

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_sched_deleg")
            .withUsername("test")
            .withPassword("test")
            .withTmpFs(java.util.Map.of("/var/lib/mysql", "rw"))
            .withCommand("--log_bin_trust_function_creators=1");

    private static final long DELEGATE_ID = 7002L;
    private static final long ORG_ID = 1001L;

    /** @BeforeAll で作成する親スケジュールの id（FK fk_sched_deleg_schedule を満たすため）。 */
    private long scheduleId;

    public static boolean isDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception e) {
            return false;
        }
    }

    @BeforeAll
    void startAndMigrate() throws SQLException {
        MYSQL.start();
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .load()
                .migrate();
        // FK 制約（schedule_id → schedules.id）を満たすため親スケジュールを 1 件作成する。
        // schedules には scope XOR の CHECK 制約（ck_schedules_scope_xor: team/org/user/committee の
        // いずれか 1 つだけ NOT NULL）があるため、親 organizations を 1 件作って organization_id をセットする。
        try (Connection c = conn()) {
            long orgId;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO organizations (name, org_type, created_at, updated_at, slug) "
                            + "VALUES ('代理出席テスト組織', 'OTHER', NOW(), NOW(), LEFT(REPLACE(UUID(), '-', ''), 22))",
                    PreparedStatement.RETURN_GENERATED_KEYS)) {
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    rs.next();
                    orgId = rs.getLong(1);
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO schedules (organization_id, title, start_at, event_type, visibility, "
                            + "min_view_role, min_response_role, status, attendance_status, comment_option, "
                            + "created_at, updated_at) "
                            + "VALUES (?, '代理出席テスト', NOW(), 'OTHER', 'MEMBERS_ONLY', 'MEMBER_PLUS', "
                            + "'MEMBER_PLUS', 'SCHEDULED', 'READY', 'OPTIONAL', NOW(), NOW())",
                    PreparedStatement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, orgId);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    rs.next();
                    this.scheduleId = rs.getLong(1);
                }
            }
        }
    }

    @AfterAll
    void stop() {
        MYSQL.stop();
    }

    private Connection conn() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    /** schedule_delegations を 1 行 INSERT する。 */
    private void insertDelegation(Connection c, UUID id, long delegatorId, String status) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO schedule_delegations
                    (id, schedule_id, delegator_id, delegate_id, organization_id, status, reason, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
                """)) {
            ps.setBytes(1, toBytes(id));
            ps.setLong(2, scheduleId);
            ps.setLong(3, delegatorId);
            ps.setLong(4, DELEGATE_ID);
            ps.setLong(5, ORG_ID);
            ps.setString(6, status);
            ps.setString(7, "出張のため");
            ps.executeUpdate();
        }
    }

    private static byte[] toBytes(UUID uuid) {
        byte[] bytes = new byte[16];
        long hi = uuid.getMostSignificantBits();
        long lo = uuid.getLeastSignificantBits();
        for (int i = 0; i < 8; i++) {
            bytes[i] = (byte) (hi >>> (8 * (7 - i)));
            bytes[8 + i] = (byte) (lo >>> (8 * (7 - i)));
        }
        return bytes;
    }

    @Test
    @DisplayName("INSERT/SELECT が通り active_delegator_marker が delegator_id に設定される（PENDING）")
    void insertSelect_アクティブ時はマーカーがdelegatorに設定される() throws SQLException {
        try (Connection c = conn()) {
            UUID id = UUID.randomUUID();
            insertDelegation(c, id, 8001L, "PENDING");

            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT status, active_delegator_marker FROM schedule_delegations WHERE id = ?")) {
                ps.setBytes(1, toBytes(id));
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("status")).isEqualTo("PENDING");
                    assertThat(rs.getLong("active_delegator_marker")).isEqualTo(8001L);
                }
            }
        }
    }

    @Test
    @DisplayName("CANCELLED 行では active_delegator_marker が NULL になる")
    void cancelled時はマーカーがNULLになる() throws SQLException {
        try (Connection c = conn()) {
            UUID id = UUID.randomUUID();
            insertDelegation(c, id, 8002L, "CANCELLED");

            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT active_delegator_marker FROM schedule_delegations WHERE id = ?")) {
                ps.setBytes(1, toBytes(id));
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    rs.getLong("active_delegator_marker");
                    assertThat(rs.wasNull()).as("CANCELLED 行のマーカーは NULL であること").isTrue();
                }
            }
        }
    }

    @Test
    @DisplayName("同一(schedule_id, delegator_id)でアクティブ委任を2件INSERTすると UNIQUE 違反")
    void アクティブ委任の重複INSERTはUNIQUE違反() throws SQLException {
        try (Connection c = conn()) {
            insertDelegation(c, UUID.randomUUID(), 8003L, "PENDING");

            assertThatThrownBy(() -> insertDelegation(c, UUID.randomUUID(), 8003L, "ACCEPTED"))
                    .isInstanceOf(SQLIntegrityConstraintViolationException.class);
        }
    }

    @Test
    @DisplayName("片方を CANCELLED にすればマーカーが NULL 化し同一委任者で再 INSERT 可能")
    void cancel後は同一委任者で再INSERT可能() throws SQLException {
        try (Connection c = conn()) {
            long delegator = 8004L;
            UUID first = UUID.randomUUID();
            insertDelegation(c, first, delegator, "PENDING");

            // 1件目を CANCELLED に更新 → marker が NULL 化
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE schedule_delegations SET status = 'CANCELLED' WHERE id = ?")) {
                ps.setBytes(1, toBytes(first));
                ps.executeUpdate();
            }

            // 同一(schedule_id, delegator_id)で再度アクティブ委任を作成できる
            UUID second = UUID.randomUUID();
            insertDelegation(c, second, delegator, "PENDING");

            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT COUNT(*) FROM schedule_delegations WHERE delegator_id = " + delegator)) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(2);
            }
        }
    }
}
