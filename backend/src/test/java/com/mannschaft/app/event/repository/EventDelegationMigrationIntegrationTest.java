package com.mannschaft.app.event.repository;

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
 * F03.10 第一陣 — {@code event_delegations} テーブルの DDL 挙動を <b>実 Flyway スキーマ</b>に対して検証する。
 *
 * <p>検証目的・方式は {@code ScheduleDelegationMigrationIntegrationTest} と同様
 * （{@code ddl-auto=create} では生成カラム {@code active_delegator_marker} + UNIQUE が再現できないため、
 * 実 MySQL に Flyway を直適用し生 JDBC で検証する）。
 * 加えて F08.3 連携カラム {@code proxy_vote_session_id} / {@code proxy_delegation_id} の永続化も検証する。</p>
 *
 * <p>Docker 未起動環境では {@code @EnabledIf} によりスキップされる。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.event.repository.EventDelegationMigrationIntegrationTest#isDockerAvailable")
@DisplayName("event_delegations DDL 挙動テスト（active_delegator_marker + UNIQUE + F08.3連携カラム）")
class EventDelegationMigrationIntegrationTest {

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_event_deleg")
            .withUsername("test")
            .withPassword("test")
            .withCommand("--log_bin_trust_function_creators=1");

    private static final long EVENT_ID = 9501L;
    private static final long DELEGATE_ID = 7102L;
    private static final long ORG_ID = 1101L;

    public static boolean isDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception e) {
            return false;
        }
    }

    @BeforeAll
    void startAndMigrate() {
        MYSQL.start();
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .load()
                .migrate();
    }

    @AfterAll
    void stop() {
        MYSQL.stop();
    }

    private Connection conn() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    /** event_delegations を 1 行 INSERT する（proxy_vote_session_id 任意）。 */
    private void insertDelegation(Connection c, UUID id, long delegatorId, String status,
                                  Long proxyVoteSessionId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO event_delegations
                    (id, event_id, delegator_id, delegate_id, organization_id, status, reason,
                     proxy_vote_session_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
                """)) {
            ps.setBytes(1, toBytes(id));
            ps.setLong(2, EVENT_ID);
            ps.setLong(3, delegatorId);
            ps.setLong(4, DELEGATE_ID);
            ps.setLong(5, ORG_ID);
            ps.setString(6, status);
            ps.setString(7, "急病のため");
            if (proxyVoteSessionId == null) {
                ps.setNull(8, java.sql.Types.BIGINT);
            } else {
                ps.setLong(8, proxyVoteSessionId);
            }
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
    @DisplayName("INSERT/SELECT が通り proxy_vote_session_id を保持できる（PENDING）")
    void insertSelect_投票セッションIDを保持できる() throws SQLException {
        try (Connection c = conn()) {
            UUID id = UUID.randomUUID();
            insertDelegation(c, id, 8101L, "PENDING", 99L);

            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT status, active_delegator_marker, proxy_vote_session_id, proxy_delegation_id "
                            + "FROM event_delegations WHERE id = ?")) {
                ps.setBytes(1, toBytes(id));
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("status")).isEqualTo("PENDING");
                    assertThat(rs.getLong("active_delegator_marker")).isEqualTo(8101L);
                    assertThat(rs.getLong("proxy_vote_session_id")).isEqualTo(99L);
                    rs.getLong("proxy_delegation_id");
                    assertThat(rs.wasNull()).as("proxy_delegation_id は連携前なので NULL").isTrue();
                }
            }
        }
    }

    @Test
    @DisplayName("REJECTED 行では active_delegator_marker が NULL になる")
    void rejected時はマーカーがNULLになる() throws SQLException {
        try (Connection c = conn()) {
            UUID id = UUID.randomUUID();
            insertDelegation(c, id, 8102L, "REJECTED", null);

            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT active_delegator_marker FROM event_delegations WHERE id = ?")) {
                ps.setBytes(1, toBytes(id));
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    rs.getLong("active_delegator_marker");
                    assertThat(rs.wasNull()).as("REJECTED 行のマーカーは NULL であること").isTrue();
                }
            }
        }
    }

    @Test
    @DisplayName("同一(event_id, delegator_id)でアクティブ委任を2件INSERTすると UNIQUE 違反")
    void アクティブ委任の重複INSERTはUNIQUE違反() throws SQLException {
        try (Connection c = conn()) {
            insertDelegation(c, UUID.randomUUID(), 8103L, "PENDING", null);

            assertThatThrownBy(() -> insertDelegation(c, UUID.randomUUID(), 8103L, "ACCEPTED", null))
                    .isInstanceOf(SQLIntegrityConstraintViolationException.class);
        }
    }

    @Test
    @DisplayName("片方を REJECTED にすればマーカーが NULL 化し同一委任者で再 INSERT 可能")
    void reject後は同一委任者で再INSERT可能() throws SQLException {
        try (Connection c = conn()) {
            long delegator = 8104L;
            UUID first = UUID.randomUUID();
            insertDelegation(c, first, delegator, "PENDING", null);

            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE event_delegations SET status = 'REJECTED' WHERE id = ?")) {
                ps.setBytes(1, toBytes(first));
                ps.executeUpdate();
            }

            UUID second = UUID.randomUUID();
            insertDelegation(c, second, delegator, "PENDING", null);

            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT COUNT(*) FROM event_delegations WHERE delegator_id = " + delegator)) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(2);
            }
        }
    }
}
