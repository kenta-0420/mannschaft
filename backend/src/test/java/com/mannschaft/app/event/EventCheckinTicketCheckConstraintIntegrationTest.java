package com.mannschaft.app.event;

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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code event_checkins.ticket_id} に種別連動 CHECK 制約を追加した V70.008 の挙動検証テスト。
 *
 * <p><b>このテストが検証する制約</b>:</p>
 * <ul>
 *   <li>{@code ticket_id} は点呼・代理チェックインのため V70.006 で NULL 許可になった。</li>
 *   <li>その副作用として「チケット式（{@link CheckinType#STAFF_SCAN} / {@link CheckinType#SELF}）なのに
 *       {@code ticket_id} が無い不正行」を DB が許してしまう状態になっていた。</li>
 *   <li>V70.008 で CHECK 制約 {@code chk_event_checkins_ticket_by_type} を追加し、
 *       「チケット式は {@code ticket_id} 必須」「チケットレス
 *       （{@code ROLL_CALL} / {@code ROLL_CALL_BATCH} / {@code PROXY}）は {@code ticket_id = NULL}」を
 *       DB レベルで強制する。</li>
 * </ul>
 *
 * <p><b>なぜ Testcontainers の実 MySQL で検証するか</b>:</p>
 * <p>共通の統合テスト環境（{@code src/test/resources/application-test.yml}）は
 * {@code spring.jpa.hibernate.ddl-auto=create} + {@code spring.flyway.enabled=false} で動作する。
 * CHECK 制約は Hibernate の Entity 定義から自動生成されないため、テスト DB に CHECK 制約が
 * そもそも存在せず、制約挙動を検証できない。
 * そこで本テストは {@link EventCheckinTicketIdNullableIntegrationTest} と同じく、
 * Testcontainers の実 MySQL 8.0 に対し本番同一の DDL
 * （V3.104 で {@code event_checkins} を作成 → V70.006 で {@code ticket_id} を NULL 許可へ
 * → V70.008 で CHECK 制約を追加）を JDBC で直接適用し、制約が期待どおり効くことを検証する。</p>
 *
 * <p>全 Flyway マイグレーションを通さず event_checkins ドメインの DDL のみを忠実に再現するのは、
 * {@link EventCheckinTicketIdNullableIntegrationTest} と同じ理由（無関係なマイグレーション順序問題で
 * コンテキスト起動が阻害される）による。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.event.EventCheckinTicketCheckConstraintIntegrationTest#isDockerAvailable")
@DisplayName("event_checkins.ticket_id 種別連動 CHECK 制約テスト")
class EventCheckinTicketCheckConstraintIntegrationTest {

    /**
     * V3.104 が定義する event_checkins テーブルに、V70.006（ticket_id NULL 許可）を適用後の状態を再現する DDL。
     * 点呼カラム（roll_call_user_id / roll_call_session_id）も含める。ticket_id は NULL 許可。
     */
    private static final String DDL_TABLE = """
            CREATE TABLE event_checkins (
                id                  BIGINT UNSIGNED          NOT NULL AUTO_INCREMENT,
                event_id            BIGINT UNSIGNED          NOT NULL,
                ticket_id           BIGINT UNSIGNED,
                checkin_type        VARCHAR(20)     NOT NULL DEFAULT 'STAFF_SCAN',
                checked_in_by       BIGINT UNSIGNED,
                checked_in_at       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
                note                VARCHAR(300),
                created_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
                roll_call_user_id   BIGINT UNSIGNED,
                roll_call_session_id VARCHAR(36),
                PRIMARY KEY (id),
                UNIQUE KEY uq_event_checkins_ticket (ticket_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """;

    /** V70.008 が適用する CHECK 制約（種別連動）。本番マイグレーションと同一文。 */
    private static final String DDL_V70_008 = """
            ALTER TABLE event_checkins
              ADD CONSTRAINT chk_event_checkins_ticket_by_type CHECK (
                (checkin_type IN ('STAFF_SCAN', 'SELF') AND ticket_id IS NOT NULL)
                OR
                (checkin_type IN ('ROLL_CALL', 'ROLL_CALL_BATCH', 'PROXY') AND ticket_id IS NULL)
              )
            """;

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_test")
            .withUsername("test")
            .withPassword("test")
            .withTmpFs(java.util.Map.of("/var/lib/mysql", "rw"));

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

    private Connection newConnection() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    /** 各テストの冒頭で、CHECK 制約が適用済みの event_checkins テーブルを作り直す。 */
    private void recreateTableWithConstraint(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS event_checkins");
            st.execute(DDL_TABLE);
            st.execute(DDL_V70_008);
        }
    }

    @Test
    @DisplayName("STAFF_SCAN_ticketId非NULL_CHECK制約を満たしINSERT成功する")
    void staffScanでticketIdが非NULLならINSERTに成功する() throws SQLException {
        try (Connection conn = newConnection()) {
            recreateTableWithConstraint(conn);

            try (Statement ins = conn.createStatement()) {
                int affected = ins.executeUpdate(
                        "INSERT INTO event_checkins (event_id, ticket_id, checkin_type) "
                                + "VALUES (1, 1001, 'STAFF_SCAN')");
                assertThat(affected).isEqualTo(1);
            }

            try (Statement sel = conn.createStatement();
                 ResultSet rs = sel.executeQuery(
                         "SELECT ticket_id, checkin_type FROM event_checkins")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong("ticket_id")).isEqualTo(1001L);
                assertThat(rs.getString("checkin_type")).isEqualTo("STAFF_SCAN");
            }
        }
    }

    @Test
    @DisplayName("STAFF_SCAN_ticketIdがNULL_CHECK制約違反でINSERT失敗する")
    void staffScanでticketIdがNULLならCHECK制約違反で失敗する() throws SQLException {
        try (Connection conn = newConnection()) {
            recreateTableWithConstraint(conn);

            assertThatThrownBy(() -> {
                try (Statement ins = conn.createStatement()) {
                    ins.executeUpdate(
                            "INSERT INTO event_checkins (event_id, ticket_id, checkin_type) "
                                    + "VALUES (1, NULL, 'STAFF_SCAN')");
                }
            })
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("chk_event_checkins_ticket_by_type");
        }
    }

    @Test
    @DisplayName("ROLL_CALL_ticketIdがNULL_CHECK制約を満たしINSERT成功する")
    void rollCallでticketIdがNULLならINSERTに成功する() throws SQLException {
        try (Connection conn = newConnection()) {
            recreateTableWithConstraint(conn);

            try (Statement ins = conn.createStatement()) {
                int affected = ins.executeUpdate(
                        "INSERT INTO event_checkins (event_id, ticket_id, checkin_type, roll_call_user_id) "
                                + "VALUES (1, NULL, 'ROLL_CALL', 9999001)");
                assertThat(affected).isEqualTo(1);
            }

            try (Statement sel = conn.createStatement();
                 ResultSet rs = sel.executeQuery(
                         "SELECT ticket_id, checkin_type, roll_call_user_id FROM event_checkins")) {
                assertThat(rs.next()).isTrue();
                rs.getLong("ticket_id");
                assertThat(rs.wasNull()).as("ticket_id は NULL のまま保持される").isTrue();
                assertThat(rs.getString("checkin_type")).isEqualTo("ROLL_CALL");
                assertThat(rs.getLong("roll_call_user_id")).isEqualTo(9999001L);
            }
        }
    }

    @Test
    @DisplayName("ROLL_CALL_ticketId非NULL_CHECK制約違反でINSERT失敗する")
    void rollCallでticketIdが非NULLならCHECK制約違反で失敗する() throws SQLException {
        try (Connection conn = newConnection()) {
            recreateTableWithConstraint(conn);

            assertThatThrownBy(() -> {
                try (Statement ins = conn.createStatement()) {
                    ins.executeUpdate(
                            "INSERT INTO event_checkins (event_id, ticket_id, checkin_type, roll_call_user_id) "
                                    + "VALUES (1, 1001, 'ROLL_CALL', 9999001)");
                }
            })
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("chk_event_checkins_ticket_by_type");
        }
    }
}
