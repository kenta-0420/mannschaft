package com.mannschaft.app.social.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-15: canonical V4.006/V9.072と実追加SQLをMySQL8へ適用し受信索引を検証する。
 * 金型はBlogMediaScopeMigrationIT。Spring/JPAのddl-auto生成を使わない。
 * SQLはFollowRepository.findIncomingTeamFollows/countIncomingTeamFollowsの実Hibernate出力
 * （CMP-261024-2051 green1 incoming-performance.json）で、aliasだけ短縮した。
 * 入力100行は既存性能ITと同じ上限ページ、他宛10000行は受信先絞込みを検証する対照である。
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("FR-15 受信申請のcanonical MySQL索引と自然実行計画")
class FriendIncomingIndexMigrationIT {
    private static final String INDEX = "idx_follows_incoming_page";
    private static final String MIGRATION = "db/migration/V252.20261025024939__add_follows_incoming_page_index.sql";
    private static final long TARGET = 10L;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String LIST_SQL = "select f.id,f.follower_id,case when exists(select r.id from follows r where r.follower_type='TEAM' and r.followed_type='TEAM' and r.followed_id=f.follower_id and r.follower_id=?) then 1 else 0 end,f.created_at from follows f where f.follower_type='TEAM' and f.followed_type='TEAM' and f.followed_id=? and not exists(select tf.id from team_friends tf where tf.team_a_id=case when ?<f.follower_id then ? else f.follower_id end and tf.team_b_id=case when ?<f.follower_id then f.follower_id else ? end) order by f.created_at desc,f.id desc limit ?";
    private static final String COUNT_SQL = "select count(f.id) from follows f where f.follower_type='TEAM' and f.followed_type='TEAM' and f.followed_id=? and not exists(select tf.id from team_friends tf where tf.team_a_id=case when ?<f.follower_id then ? else f.follower_id end and tf.team_b_id=case when ?<f.follower_id then f.follower_id else ? end)";

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @BeforeAll
    static void canonical旧スキーマと実追加SQLを適用する() throws Exception {
        try (var connection = connection(); var statement = connection.createStatement()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V4.006__create_follows_table.sql"));
            // teamsは関係表のFK先だけに必要で、受信queryの検索対象ではない。
            statement.execute("CREATE TABLE teams (id BIGINT UNSIGNED NOT NULL PRIMARY KEY) ENGINE=InnoDB");
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V9.072__create_team_friends_table.sql"));
            var migration = new ClassPathResource(MIGRATION);
            // redでは旧DDLで起動成功し、索引の実assertionを失敗させる。SQL不在をsetup失敗にしない。
            if (migration.exists()) ScriptUtils.executeSqlScript(connection, migration);
            try (var insert = connection.prepareStatement("INSERT INTO follows(follower_type,follower_id,followed_type,followed_id,created_at) VALUES('TEAM',?,'TEAM',?,'2026-10-25 00:00:00')")) {
                for (int i = 1; i <= 100; i++) {
                    insert.setLong(1, 100 + i); insert.setLong(2, TARGET); insert.addBatch();
                }
                for (int i = 1; i <= 10000; i++) {
                    insert.setLong(1, 1000 + i); insert.setLong(2, 20000 + i); insert.addBatch();
                }
                insert.setLong(1, TARGET); insert.setLong(2, 101); insert.addBatch();
                insert.executeBatch();
            }
            statement.execute("ANALYZE TABLE follows, team_friends");
        }
    }

    @Test
    void 前方三列と降順二列と申請元の被覆を実DDLで保持する() throws Exception {
        try (var connection = connection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COLUMN_NAME,COLLATION FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='follows' AND index_name='" + INDEX + "' ORDER BY SEQ_IN_INDEX")) {
            var columns = new ArrayList<String>(); var directions = new ArrayList<String>();
            while (rows.next()) { columns.add(rows.getString(1)); directions.add(rows.getString(2)); }
            assertThat(columns).as("受信先等値prefix・ページ順・申請元coveringが実スキーマに必要")
                    .containsExactly("followed_type", "followed_id", "follower_type", "created_at", "id", "follower_id");
            assertThat(directions).containsExactly("A", "A", "A", "D", "D", "A");
            try (var unique = statement.executeQuery("SELECT COLUMN_NAME FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='follows' AND index_name='uk_follows' ORDER BY SEQ_IN_INDEX")) {
                var original = new ArrayList<String>(); while (unique.next()) original.add(unique.getString(1));
                assertThat(original).containsExactly("follower_type", "follower_id", "followed_type", "followed_id");
            }
        }
    }

    @Test
    void 他宛一万行でも受信百件の一覧COUNTを自然索引で絞り同時刻ID降順を保つ() throws Exception {
        try (var connection = connection()) {
            try (var query = prepared(connection, LIST_SQL); var rows = query.executeQuery()) {
                var ids = new ArrayList<Long>(); var sources = new ArrayList<Long>(); var reverse = new ArrayList<Boolean>();
                while (rows.next()) { ids.add(rows.getLong(1)); sources.add(rows.getLong(2)); reverse.add(rows.getBoolean(3)); }
                assertThat(ids).containsExactlyElementsOf(LongStream.iterate(100, n -> n - 1).limit(100).boxed().toList());
                assertThat(sources).containsExactlyElementsOf(LongStream.iterate(200, n -> n - 1).limit(100).boxed().toList());
                assertThat(reverse.subList(0, 99)).containsOnly(false);
                assertThat(reverse.get(99)).isTrue();
            }
            try (var query = prepared(connection, COUNT_SQL); var count = query.executeQuery()) {
                assertThat(count.next()).isTrue(); assertThat(count.getLong(1)).isEqualTo(100);
            }
            var plans = new ArrayList<JsonNode>();
            for (String sql : List.of(LIST_SQL, COUNT_SQL)) {
                try (var query = prepared(connection, "EXPLAIN FORMAT=JSON " + sql); var rows = query.executeQuery()) {
                    assertThat(rows.next()).isTrue(); plans.add(MAPPER.readTree(rows.getString(1)));
                }
            }
            var report = Path.of("build/reports/friend-request/incoming-index-plan.json");
            Files.createDirectories(report.getParent());
            Files.writeString(report, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                    "migration", MIGRATION, "targetRows", 100, "decoyRows", 10000, "sql", List.of(LIST_SQL, COUNT_SQL), "plans", plans)));
            for (JsonNode plan : plans) {
                JsonNode outer = plan.findValues("table").stream().filter(t -> t.path("table_name").asText().equals("f")).findFirst().orElseThrow();
                assertThat(outer.path("key").asText()).as("FORCE INDEXなしで受信先索引を選ぶ").isEqualTo(INDEX);
                assertThat(outer.path("access_type").asText()).as("全follows走査は禁止").isIn("ref", "range");
                var parts = new ArrayList<String>(); outer.path("used_key_parts").forEach(p -> parts.add(p.asText()));
                assertThat(parts).startsWith("followed_type", "followed_id", "follower_type");
                assertThat(outer.path("rows_examined_per_scan").asLong()).as("他宛一万行を走査しない").isLessThanOrEqualTo(200);
            }
            assertThat(plans.get(0).findValues("using_filesort")).noneMatch(JsonNode::asBoolean);
        }
    }

    private static java.sql.PreparedStatement prepared(Connection connection, String sql) throws Exception {
        var statement = connection.prepareStatement(sql);
        int parameters = (int) sql.chars().filter(c -> c == '?').count();
        for (int i = 1; i <= parameters; i++) statement.setLong(i, TARGET);
        if (sql.endsWith("limit ?")) statement.setInt(parameters, 100);
        return statement;
    }

    private static Connection connection() throws Exception {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }
}
