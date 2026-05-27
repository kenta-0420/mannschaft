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
 * {@code event_checkins.ticket_id} を NULL 許可へ変更した V70.001 のリグレッションテスト。
 *
 * <p><b>このテストが検証する潜在バグ</b>:</p>
 * <ul>
 *   <li>{@code ticket_id} は V3.104 で {@code BIGINT UNSIGNED NOT NULL} と定義されていた。</li>
 *   <li>しかし {@code EventCheckinEntity} は当初から {@code ticketId} を nullable 前提で扱い、
 *       点呼（{@link CheckinType#ROLL_CALL} / {@link CheckinType#ROLL_CALL_BATCH}）チェックインは
 *       チケットを介さず {@code ticketId = null}（{@code rollCallUserId} がチケットの代替）で永続化する。</li>
 *   <li>結果として Flyway 適用済みの本番 DB では点呼チェックインの INSERT が
 *       NOT NULL 制約違反（SQLState 23000）で失敗していた。</li>
 * </ul>
 *
 * <p><b>なぜ既存テストで検出できなかったか</b>:</p>
 * <p>共通の統合テスト環境（{@code src/test/resources/application-test.yml}）は
 * {@code spring.jpa.hibernate.ddl-auto=create} + {@code spring.flyway.enabled=false} で動作する。
 * このためテスト DB のスキーマは Entity（nullable）から生成され、Flyway DDL の
 * NOT NULL 制約がそもそもテスト DB に存在しなかった。
 * 「テストが通っても本番が壊れる」構造だったため、本バグは長らく隠れていた。</p>
 *
 * <p><b>本テストの方針</b>:</p>
 * <p>上記の盲点を塞ぐため、本テストは Testcontainers の実 MySQL 8.0 に対し
 * <b>本番と同一の DDL（V3.104 で {@code event_checkins} を作成 → V70.001 で {@code ticket_id} を NULL 許可へ変更）</b>
 * を JDBC で直接適用し、点呼チェックイン（{@code ticket_id = NULL}）が永続化できることを検証する。</p>
 *
 * <p>Spring コンテキスト全体を起動せず、本バグに関係する DDL だけをピンポイントで再現するのは、
 * 全 Flyway マイグレーション（857 本）を通すと event_checkins とは無関係な別の既存マイグレーション順序問題
 * （V3.147 が V13.014 で追加される {@code todos.linked_schedule_id} に先行参照する）で
 * コンテキスト起動が阻害され、本バグの検証に到達できないためである。
 * そのため event_checkins ドメインの DDL のみを忠実に再現して検証範囲を本バグに限定する。</p>
 *
 * <p>V70.001 適用前（{@code ticket_id NOT NULL}）はこの INSERT が失敗し、適用後は成功することを、
 * {@code beforeAlter} / {@code afterAlter} の 2 ケースで対比して証明する。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.event.EventCheckinTicketIdNullableIntegrationTest#isDockerAvailable")
@DisplayName("event_checkins.ticket_id NULL 許可リグレッションテスト")
class EventCheckinTicketIdNullableIntegrationTest {

    /** V3.104 が定義する event_checkins テーブル（ticket_id NOT NULL）。本番 DDL を忠実に再現する。 */
    private static final String DDL_V3_104 = """
            CREATE TABLE event_checkins (
                id                  BIGINT UNSIGNED          NOT NULL AUTO_INCREMENT,
                event_id            BIGINT UNSIGNED          NOT NULL,
                ticket_id           BIGINT UNSIGNED          NOT NULL,
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

    /** V70.001 が適用する ALTER（ticket_id を NULL 許可へ）。本番マイグレーションと同一文。 */
    private static final String DDL_V70_001 = """
            ALTER TABLE event_checkins
              MODIFY COLUMN ticket_id BIGINT UNSIGNED NULL
                COMMENT 'FK → event_tickets.id（点呼などチケットを介さない場合は NULL）'
            """;

    /** 点呼チェックインを模した INSERT（ticket_id = NULL, checkin_type = ROLL_CALL）。 */
    private static final String INSERT_ROLL_CALL = """
            INSERT INTO event_checkins (event_id, ticket_id, checkin_type, roll_call_user_id, roll_call_session_id)
            VALUES (1, NULL, 'ROLL_CALL', 9999001, 'roll-call-session-test-0001')
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

    /**
     * V70.001 適用<b>前</b>（V3.104 の ticket_id NOT NULL のみ）では、点呼チェックインの INSERT が
     * NOT NULL 制約違反で失敗することを確認する。これが根治対象の潜在バグの再現である。
     */
    @Test
    @DisplayName("V70.001適用前_点呼チェックイン_ticketIdがnull_NOT NULL制約違反で失敗する")
    void v70適用前は点呼チェックインがNOT_NULL制約で失敗する() throws SQLException {
        try (Connection conn = newConnection(); Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS event_checkins");
            st.execute(DDL_V3_104);

            // V3.104 のみ適用した状態（ticket_id NOT NULL）では INSERT が失敗するはず
            assertThatThrownBy(() -> {
                try (Statement ins = conn.createStatement()) {
                    ins.executeUpdate(INSERT_ROLL_CALL);
                }
            })
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("ticket_id");
        }
    }

    /**
     * V70.001 適用<b>後</b>（ticket_id NULL 許可）では、点呼チェックインの INSERT が成功し
     * id が採番され、ticket_id が NULL のまま保持されることを確認する。これが根治の検証である。
     */
    @Test
    @DisplayName("V70.001適用後_点呼チェックイン_ticketIdがnull_永続化に成功しidが採番される")
    void v70適用後は点呼チェックインの永続化に成功する() throws SQLException {
        try (Connection conn = newConnection(); Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS event_checkins");
            st.execute(DDL_V3_104);
            // 本番マイグレーション V70.001 を適用
            st.execute(DDL_V70_001);

            // 点呼チェックイン（ticket_id = NULL）を INSERT — 例外なく成功するはず
            try (Statement ins = conn.createStatement()) {
                int affected = ins.executeUpdate(INSERT_ROLL_CALL, Statement.RETURN_GENERATED_KEYS);
                assertThat(affected).isEqualTo(1);

                long generatedId;
                try (ResultSet keys = ins.getGeneratedKeys()) {
                    assertThat(keys.next()).isTrue();
                    generatedId = keys.getLong(1);
                }
                assertThat(generatedId).isPositive();
            }

            // 永続化された行を読み戻し、ticket_id が NULL のまま保持されていることを検証
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
}
