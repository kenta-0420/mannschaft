package com.mannschaft.app.event.migration;

import org.flywaydb.core.Flyway;
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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * <b>実 Flyway マイグレーションで from-scratch 構築した DB に対し、
 * {@code EventCheckinEntity} が写像する全カラムを SELECT できること</b>を検証する番人テスト。
 *
 * <h2>このテストが守る不変条件</h2>
 * <p>本番・staging・CI・新規開発環境の初回構築は空 DB に対し Flyway をバージョン昇順で適用する。
 * {@code EventCheckinEntity}（{@code event ドメイン}）が持つカラムのいずれかに
 * 「エンティティにフィールドはあるが対応する ALTER TABLE マイグレーションが存在しない」欠落があると、
 * Hibernate が生成する全列 SELECT（例:
 * {@code GET /api/v1/events/{id}/checkins} が呼ぶ
 * {@code EventCheckinRepository.findByEventIdOrderByCheckedInAtDesc}）が
 * <b>Unknown column '...' in 'field list'</b> で 500 になる。</p>
 *
 * <h2>なぜ既存テストで検出できなかったか</h2>
 * <p>通常の統合テスト環境（{@code application-test.yml}）は
 * {@code hibernate.ddl-auto=create} + {@code flyway.enabled=false} で、
 * スキーマを Entity から生成するため Flyway 由来のカラム欠落を検知できない。
 * さらに event_checkins 系の既存統合テスト2本
 * （{@code EventCheckinTicketIdNullableIntegrationTest} /
 * {@code EventCheckinTicketCheckConstraintIntegrationTest}）は
 * 自前 CREATE TABLE に手書きで全列を含めていたため、実マイグレーションの欠落を素通りさせていた。</p>
 *
 * <p>実際に F03.12 §14 主催者点呼で追加された {@code roll_call_user_id}
 * （{@code EventCheckinEntity.rollCallUserId}）は、対応する ALTER TABLE が作られておらず、
 * このテストが <b>Unknown column 'roll_call_user_id'</b> で red になることで欠落を検出する。</p>
 *
 * <h2>本テストの方針</h2>
 * <p>{@link FlywayFromScratchMigrationTest} の流儀を踏襲し、Spring を起動せず Testcontainers の
 * 実 MySQL 8.0 に {@link Flyway} を Java API で直接（{@code outOfOrder(false)} = 本番 fresh 構築と同条件）
 * 適用したうえで、{@code EventCheckinEntity} が写像する全カラムを列挙した SELECT を実行し、
 * 例外なく完了することを検証する。Docker 未起動環境では {@code @EnabledIf} でスキップ（骨抜きにしない）。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.event.migration.FlywayFromScratchEventCheckinsRollCallUserIdColumnTest#isDockerAvailable")
@DisplayName("Flyway from-scratch event_checkins エンティティ全列SELECT番人テスト（roll_call_user_id 欠落検出）")
class FlywayFromScratchEventCheckinsRollCallUserIdColumnTest {

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_ec_rollcalluser")
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
    @DisplayName("実マイグレーションから構築したDBでevent_checkinsのエンティティ全列SELECTが例外なく通る")
    void event_checkinsのエンティティ全列SELECTが実マイグレーションDB上で成功する() throws Exception {
        // given: 本番 fresh 構築と同条件（out-of-order 無効）で全マイグレーションを適用する
        Flyway flyway = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .load();
        MigrateResult result = flyway.migrate();
        assertThat(result.success).as("全マイグレーションが成功すること").isTrue();

        // when / then: EventCheckinEntity が写像する全カラムを列挙した SELECT が Unknown column なく通ること。
        // roll_call_user_id の ALTER TABLE が欠落していると、この SELECT が
        // SQLException(Unknown column 'roll_call_user_id' in 'field list') で落ちる = red。
        final String selectAllEntityColumns = """
                SELECT id,
                       event_id,
                       ticket_id,
                       checkin_type,
                       checked_in_by,
                       checked_in_at,
                       note,
                       created_at,
                       roll_call_user_id,
                       checkout_at,
                       guardian_checkin_notified_at,
                       guardian_checkout_notified_at,
                       roll_call_session_id,
                       late_arrival_minutes,
                       absence_reason,
                       delegation_id
                FROM event_checkins
                ORDER BY checked_in_at DESC
                """;

        assertThatCode(() -> {
            try (Connection c = conn();
                 Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(selectAllEntityColumns)) {
                // 結果 0 件でも良い。列が全て解決でき Unknown column が出ないことが本質。
                assertThat(rs).isNotNull();
            }
        }).as("event_checkins のエンティティ全列 SELECT が Unknown column なく通ること").doesNotThrowAnyException();
    }
}
