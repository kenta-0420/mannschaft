package com.mannschaft.app.social.announcement;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F01.2.1 部隊 1-B — AC-H14a: 多値インデックス（JSON 配列の {@code MEMBER OF} 用）の
 * 1 レコードあたりの件数上限を、<b>実 MySQL 8.0（Testcontainers）で実測</b>して固定する。
 *
 * <h2>背景</h2>
 * <p>設計書は {@code announcement_feeds.target_team_ids}（既存・{@code UNSIGNED ARRAY}）と
 * 新設の {@code target_group_ids}（{@code CHAR(36) ARRAY}）の件数上限 N を「5,000 件」と想定していた。
 * InnoDB の多値インデックスには 1 レコードあたりのキー総バイト数の上限があり、超えると
 * INSERT / UPDATE 自体が失敗する。本テストは実際の Flyway マイグレーションが作るインデックス定義を使い、
 * 上限の境界を二分探索で測って固定する（MySQL の版が変われば境界が動き、本テストが赤くなって知らせる）。</p>
 *
 * <h2>方式</h2>
 * <p>通常の IT は Entity から Hibernate がスキーマを作る（Flyway 無効）ため、関数インデックスは存在しない。
 * そこで Spring を起動せず、{@code announcement_feeds} の最小スタブに<b>本物のマイグレーション SQL</b>
 * （V19.002 と V230 の feed 追加分）を流し、その実インデックスに対して実測する。</p>
 *
 * <h2>実測の結果（mysql:8.0 / innodb_page_size=16384）</h2>
 * <ul>
 *   <li>キー総バイト数の上限は 5,352 バイト/レコード</li>
 *   <li>{@code target_team_ids}（1 キー 8 バイト）: 最大 669 件（670 件で失敗）</li>
 *   <li>{@code target_group_ids}（1 キー 36 バイト）: 最大 148 件（149 件で失敗）</li>
 *   <li>設計書の想定 5,000 件は成立しない</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.social.announcement.AnnouncementMultiValuedIndexLimitIT#isDockerAvailable")
@DisplayName("AC-H14a 多値インデックスの件数上限（実 MySQL 実測）")
class AnnouncementMultiValuedIndexLimitIT {

    /** 実測で確定した target_team_ids の上限（UNSIGNED ARRAY・1 キー 8 バイト）。 */
    private static final int MEASURED_MAX_TEAM_IDS = 669;

    /** 実測で確定した target_group_ids の上限（CHAR(36) ARRAY・1 キー 36 バイト）。 */
    private static final int MEASURED_MAX_GROUP_IDS = 148;

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_mvi")
            .withUsername("test")
            .withPassword("test")
            .withTmpFs(Map.of("/var/lib/mysql", "rw"));

    public static boolean isDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception e) {
            return false;
        }
    }

    private Connection connection;

    @BeforeAll
    void startContainerAndApplyRealMigrations() throws Exception {
        MYSQL.start();
        connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        try (Statement st = connection.createStatement()) {
            // V19.002 が参照する visibility 列だけを持つ最小スタブ（id は本番と同じ BIGINT UNSIGNED）
            st.execute("CREATE TABLE announcement_feeds ("
                    + "id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY, "
                    + "visibility VARCHAR(30) NOT NULL DEFAULT 'MEMBERS_AND_ABOVE') "
                    + "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci");
            st.execute(readMigrationStatement("V19.002__add_target_team_ids_to_announcement_feeds.sql"));
            // 本タスクで追加するマイグレーション（グループ宛て3列と多値インデックス）
            st.execute(readMigrationStatement(findMigrationName("add_announcement_feed_group_targeting")));
        }
    }

    @AfterAll
    void stopContainer() throws Exception {
        if (connection != null) {
            connection.close();
        }
        MYSQL.stop();
    }

    @BeforeEach
    void truncate() throws Exception {
        try (Statement st = connection.createStatement()) {
            st.execute("TRUNCATE TABLE announcement_feeds");
        }
    }

    @Test
    @DisplayName("target_group_ids（CHAR(36) ARRAY）の上限は 148 件で、149 件の INSERT は DB が拒否する")
    void groupIdsLimitIs148() throws Exception {
        int measured = measureMaxAccepted(n -> insertGroupIds(uuidArray(n)));

        System.out.println("[AC-H14a 実測] target_group_ids 上限 = " + measured);
        assertThat(measured).as("CHAR(36) ARRAY の実測上限").isEqualTo(MEASURED_MAX_GROUP_IDS);
        assertThat(rejectedByMultiValuedIndex(() -> insertGroupIds(uuidArray(MEASURED_MAX_GROUP_IDS + 1))))
                .as("上限 +1 件は多値インデックスの上限で拒否される").isTrue();
    }

    @Test
    @DisplayName("target_team_ids（UNSIGNED ARRAY）の上限は 669 件で、設計書の想定 5,000 件は成立しない")
    void teamIdsLimitIs669AndFiveThousandFails() throws Exception {
        int measured = measureMaxAccepted(n -> insertTeamIds(numberArray(n)));

        System.out.println("[AC-H14a 実測] target_team_ids 上限 = " + measured);
        assertThat(measured).as("UNSIGNED ARRAY の実測上限").isEqualTo(MEASURED_MAX_TEAM_IDS);
        assertThat(rejectedByMultiValuedIndex(() -> insertTeamIds(numberArray(5_000))))
                .as("設計書が想定した 5,000 件は多値インデックスの上限を超えて拒否される").isTrue();
    }

    @Test
    @DisplayName("配列を入れたグループ ID は MEMBER OF（->'$[*]' 形）で多値インデックス経由に検索できる")
    void memberOfUsesMultiValuedIndex() throws Exception {
        // 数千件を入れてオプティマイザが全走査でなくインデックスを選ぶ状況を作る
        String probeGroup = UUID.randomUUID().toString();
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO announcement_feeds (target_group_ids, target_team_ids) VALUES (?, ?)")) {
            for (int i = 0; i < 4_000; i++) {
                ps.setString(1, "[\"" + UUID.randomUUID() + "\",\"" + UUID.randomUUID() + "\"]");
                ps.setString(2, "[" + i + "," + (i + 100_000) + "]");
                ps.addBatch();
            }
            ps.setString(1, "[\"" + probeGroup + "\"]");
            ps.setString(2, "[777777]");
            ps.addBatch();
            ps.executeBatch();
        }
        try (Statement st = connection.createStatement()) {
            st.execute("ANALYZE TABLE announcement_feeds");
        }

        // 実際に検索でき、1 件だけ当たる
        assertThat(count("SELECT COUNT(*) FROM announcement_feeds WHERE '" + probeGroup
                + "' MEMBER OF (target_group_ids->'$[*]')")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM announcement_feeds WHERE 777777 MEMBER OF (target_team_ids->'$[*]')"))
                .isEqualTo(1);

        // 索引が使われる（EXPLAIN の key）
        assertThat(explainKey("SELECT id FROM announcement_feeds WHERE '" + probeGroup
                + "' MEMBER OF (target_group_ids->'$[*]')"))
                .as("group: ->'$[*]' 形の MEMBER OF は idx_af_target_groups を使う")
                .isEqualTo("idx_af_target_groups");
        assertThat(explainKey("SELECT id FROM announcement_feeds WHERE 777777 MEMBER OF (target_team_ids->'$[*]')"))
                .as("team: ->'$[*]' 形の MEMBER OF は idx_af_target_teams を使う")
                .isEqualTo("idx_af_target_teams");

        // 実測の記録: 列名を直接渡す形（JSON_CONTAINS(col, ...) / MEMBER OF (col)）は索引を使わず全走査になる
        assertThat(explainKey("SELECT id FROM announcement_feeds WHERE '" + probeGroup
                + "' MEMBER OF (target_group_ids)"))
                .as("列名直接の MEMBER OF は索引に乗らない（式が索引定義と一致しないため）")
                .isNull();
        assertThat(explainKey("SELECT id FROM announcement_feeds WHERE JSON_CONTAINS(target_team_ids, CAST(777777 AS JSON))"))
                .as("列名直接の JSON_CONTAINS は索引に乗らない")
                .isNull();
    }

    // ---- ヘルパ ----

    /** 1..10,000 の範囲で「INSERT が成功する最大件数」を二分探索する。 */
    private int measureMaxAccepted(ThrowingIntConsumer tryInsert) throws Exception {
        int lo = 1;
        int hi = 10_000;
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (accepted(tryInsert, mid)) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    private boolean accepted(ThrowingIntConsumer tryInsert, int n) throws Exception {
        try {
            tryInsert.accept(n);
            return true;
        } catch (SQLException e) {
            if (isMultiValuedIndexLimit(e)) {
                return false;
            }
            throw e;
        }
    }

    private boolean rejectedByMultiValuedIndex(ThrowingRunnable r) throws Exception {
        try {
            r.run();
            return false;
        } catch (SQLException e) {
            return isMultiValuedIndexLimit(e);
        }
    }

    private static boolean isMultiValuedIndexLimit(SQLException e) {
        return e.getMessage() != null && e.getMessage().contains("multi-valued index");
    }

    private void insertGroupIds(String json) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO announcement_feeds (target_group_ids) VALUES (?)")) {
            ps.setString(1, json);
            ps.executeUpdate();
        }
    }

    private void insertTeamIds(String json) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO announcement_feeds (target_team_ids) VALUES (?)")) {
            ps.setString(1, json);
            ps.executeUpdate();
        }
    }

    private static String uuidArray(int n) {
        return IntStream.range(0, n).mapToObj(i -> "\"" + UUID.randomUUID() + "\"")
                .collect(Collectors.joining(",", "[", "]"));
    }

    private static String numberArray(int n) {
        return IntStream.rangeClosed(1, n).mapToObj(Integer::toString)
                .collect(Collectors.joining(",", "[", "]"));
    }

    private int count(String sql) throws SQLException {
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private String explainKey(String sql) throws SQLException {
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery("EXPLAIN " + sql)) {
            rs.next();
            return rs.getString("key");
        }
    }

    // ---- マイグレーション SQL の読み出し（実ファイルを流すことで、DDL と実測を一致させる）----

    private static Path migrationDir() {
        Path dir = Paths.get("src", "main", "resources", "db", "migration");
        assertThat(Files.isDirectory(dir)).as("マイグレーションディレクトリ: " + dir.toAbsolutePath()).isTrue();
        return dir;
    }

    private static String findMigrationName(String descriptionPart) throws IOException {
        try (Stream<Path> files = Files.list(migrationDir())) {
            List<String> names = files.map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith("V230.") && n.contains(descriptionPart))
                    .toList();
            assertThat(names).as("V230 の " + descriptionPart + " マイグレーションが1件あること").hasSize(1);
            return names.get(0);
        }
    }

    /** コメント行を除いた、単一の DDL 文を返す。 */
    private static String readMigrationStatement(String fileName) throws IOException {
        String text = Files.readString(migrationDir().resolve(fileName), StandardCharsets.UTF_8);
        String sql = text.lines()
                .filter(l -> !l.stripLeading().startsWith("--"))
                .collect(Collectors.joining("\n"))
                .strip();
        assertThat(sql).as(fileName + " は 1 文の ALTER であること").endsWith(";");
        return sql.substring(0, sql.length() - 1);
    }

    @FunctionalInterface
    private interface ThrowingIntConsumer {
        void accept(int n) throws Exception;
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
