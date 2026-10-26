package com.mannschaft.app.billing.beta.migration;

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
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>既存の運用値が入っている {@code beta_perk_criteria} に対して
 * V169.20260728050056（{@code min_active_days} の初期値投入）を流しても、
 * 運用値が巻き戻らないこと</b>を検証する番人テスト（Issue #2487 項目 6）。
 *
 * <h2>このテストが守る不変条件 / 背景</h2>
 * <p>V169 は「F10.8 実装前は NULL 運用」としていた初期値に対する<b>一度きりの値投入</b>であり、
 * {@code WHERE grant_kind = 'INDIVIDUAL' AND min_active_days IS NULL} という条件で
 * 「シスアドが運用 API で既に変更済みの行は上書きしない」設計になっている。</p>
 *
 * <p>しかし、この非破壊性を担保していたのは<b>検分での目視だけ</b>だった。{@code AND min_active_days IS NULL}
 * が将来のリファクタで落ちても、{@link com.mannschaft.app.common.migration.FlywayFromScratchMigrationTest}
 * （空 DB からの from-scratch 番人）では <b>V162 のシードが全行 NULL</b> であるため差が出ず、素通りしてしまう。
 * すなわち「既存データを持つ環境でのみ破綻する」典型的な盲点である。</p>
 *
 * <p>本テストは <b>V168 まで適用 → INDIVIDUAL の 1 行に運用値（21）を入れる → 残りのマイグレーション
 * （V169 含む）を適用</b> という既存データ経路を再現し、
 * 「運用値 21 は 21 のまま・NULL だった行だけが 14 になる・TEAM_ORG は NULL のまま」を課す。</p>
 *
 * <h2>方針</h2>
 * <p>金型は {@code FlywayExistingDataTeamVisibilityMigrationTest}。Spring コンテキストを起動せず
 * Testcontainers の実 MySQL 8.0 に対して {@link Flyway} を Java API で直接実行する
 * （{@code application-test.yml} の {@code flyway.enabled=false} を避けるため）。
 * Docker 未起動環境では {@code @EnabledIf} によりスキップされる。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.billing.beta.migration."
        + "FlywayExistingDataBetaPerkCriteriaMinActiveDaysMigrationTest#isDockerAvailable")
@DisplayName("Flyway 既存データ beta_perk_criteria.min_active_days 非破壊性（V169）番人テスト")
class FlywayExistingDataBetaPerkCriteriaMinActiveDaysMigrationTest {

    /** V169.20260728050056 の直前バージョン。ここまで適用してから運用値をシードする。 */
    private static final String PRE_V169_TARGET = "168.20260710112751";

    /** シスアドが運用 API で設定済みの値（V169 の投入値 14 とは異なる値にする）。 */
    private static final int OPERATIONAL_VALUE = 21;

    /** V169 が NULL 行に投入する値。 */
    private static final int SEEDED_VALUE = 14;

    /** 運用値を入れておく対象フェーズ（他フェーズは NULL のままにして両方の挙動を同時に観測する）。 */
    private static final int OPERATED_PHASE = 1;

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_betaperkcriteria")
            .withUsername("test")
            .withPassword("test")
            .withTmpFs(java.util.Map.of("/var/lib/mysql", "rw"))
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

    @Test
    @DisplayName("運用値が入った行はV169適用後も巻き戻らず、NULLだったINDIVIDUAL行だけが14になる")
    void 既存の運用値がV169で巻き戻らない() throws Exception {
        // given: V168 まで適用（＝V162 のシード状態。全行 min_active_days IS NULL）
        Flyway pre = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .target(MigrationVersion.fromVersion(PRE_V169_TARGET))
                .load();
        MigrateResult preResult = pre.migrate();
        assertThat(preResult.success).as("V168 までの適用が成功すること").isTrue();

        try (Connection conn = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             Statement st = conn.createStatement()) {

            // sanity: この時点では全行 NULL（V169 未適用であることの担保）
            assertThat(count(conn, "SELECT COUNT(*) FROM beta_perk_criteria WHERE min_active_days IS NOT NULL"))
                    .as("V168 時点では min_active_days に値が入っている行が無いこと")
                    .isZero();

            // シスアドが運用 API で phase=1 の INDIVIDUAL を 21 に変更済み、という既存データを作る
            st.executeUpdate("UPDATE beta_perk_criteria SET min_active_days = " + OPERATIONAL_VALUE
                    + " WHERE beta_phase = " + OPERATED_PHASE + " AND grant_kind = 'INDIVIDUAL'");
            assertThat(minActiveDays(conn, OPERATED_PHASE, "INDIVIDUAL"))
                    .as("運用値のシードが効いていること")
                    .isEqualTo(OPERATIONAL_VALUE);
        }

        // when: 残りのマイグレーション（V169 含む）を適用する
        Flyway rest = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .load();
        MigrateResult restResult = rest.migrate();

        // then
        assertThat(restResult.success).as("V169 を含む残りのマイグレーションが成功すること").isTrue();

        try (Connection conn = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {

            // 1. 運用値は巻き戻らない（WHERE min_active_days IS NULL が落ちるとここが 14 になって落ちる）
            assertThat(minActiveDays(conn, OPERATED_PHASE, "INDIVIDUAL"))
                    .as("シスアドが設定済みの運用値 %d は V169 で上書きされないこと", OPERATIONAL_VALUE)
                    .isEqualTo(OPERATIONAL_VALUE);

            // 2. NULL だった INDIVIDUAL 行には 14 が入る（本来の目的が果たされていること）
            for (int phase = 2; phase <= 4; phase++) {
                assertThat(minActiveDays(conn, phase, "INDIVIDUAL"))
                        .as("NULL だった phase=%d の INDIVIDUAL には %d が投入されること", phase, SEEDED_VALUE)
                        .isEqualTo(SEEDED_VALUE);
            }

            // 3. TEAM_ORG は対象外（activeDays は個人指標のため今後も NULL 運用）
            assertThat(count(conn,
                    "SELECT COUNT(*) FROM beta_perk_criteria "
                            + "WHERE grant_kind = 'TEAM_ORG' AND min_active_days IS NOT NULL"))
                    .as("TEAM_ORG の min_active_days は NULL のままであること")
                    .isZero();
        }
    }

    /** {@code (betaPhase, grantKind)} の {@code min_active_days} を返す（NULL なら null）。 */
    private static Integer minActiveDays(Connection conn, int betaPhase, String grantKind) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT min_active_days FROM beta_perk_criteria "
                             + "WHERE beta_phase = " + betaPhase + " AND grant_kind = '" + grantKind + "'")) {
            assertThat(rs.next())
                    .as("beta_perk_criteria(%d, %s) の行が存在すること", betaPhase, grantKind)
                    .isTrue();
            int value = rs.getInt(1);
            return rs.wasNull() ? null : value;
        }
    }

    /** COUNT クエリを 1 件の long として取り出す。 */
    private static long count(Connection conn, String sql) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
