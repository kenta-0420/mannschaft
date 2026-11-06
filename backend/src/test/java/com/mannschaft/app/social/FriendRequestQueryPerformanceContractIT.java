package com.mannschaft.app.social;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.FriendRequestSourceTeamRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.slf4j.LoggerFactory;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** FR-15: 実MySQL・実HTTPで100行のSQL本数、EXPLAIN、p95を記録する。 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class FriendRequestQueryPerformanceContractIT extends FriendRequestContractFixture {
    private static final int ROWS = 100;
    private static final int SAMPLES = 20;

    @Test
    void FR15_候補百件の実SQLは一覧COUNT二本で全所属ロードをしない() throws Exception {
        for (int i = 1; i < ROWS; i++) {
            var next = team();
            membership(admin, next.getId(), RoleKind.MEMBER);
            userRoles.save(UserRoleEntity.builder().userId(admin).teamId(next.getId()).roleId(role("ADMIN").getId()).build());
        }
        em.flush(); em.clear();
        var result = capture(() -> ok(auth(get("/api/v1/me/friend-request-source-teams")
                .param("targetTeamSlug", target.getSlug()).param("size", "100"), admin)));
        var relevant = result.sql().stream().filter(sql -> compact(sql).contains("from memberships")
                && compact(sql).contains("manage_friend_teams")).toList();
        assertThat(relevant).as("Hibernateのnative実SQLが実際に捕捉できること").hasSize(SAMPLES * 2);
        var plans = new ArrayList<Map<String, Object>>();
        for (String sql : List.of(FriendRequestSourceTeamRepository.LIST_SOURCES,
                FriendRequestSourceTeamRepository.COUNT_SOURCES)) {
            var query = em.createNativeQuery("EXPLAIN " + sql).setParameter("userId", admin)
                    .setParameter("targetSlug", target.getSlug());
            if (sql.equals(FriendRequestSourceTeamRepository.LIST_SOURCES)) {
                query.setParameter("limit", ROWS).setParameter("offset", 0L);
            }
            @SuppressWarnings("unchecked") List<Object[]> rows = query.getResultList();
            plans.add(Map.of("sql", sql, "explain", rows.stream().map(Arrays::asList).toList()));
        }
        report("source-performance", result, relevant, plans, 2);
    }

    @Test
    void FR15_受信百件も一覧COUNTと公開概要一括の三本で取得する() throws Exception {
        for (int i = 0; i < ROWS; i++) followRow(FollowerType.TEAM, team().getId(), FollowerType.TEAM, own.getId());
        em.flush(); em.clear();
        Callable<JsonNode> request = () -> ok(auth(get(BASE + "/incoming", own.getId()).param("size", "100"), admin));
        request.call(); // 認可キャッシュのcold/warm差を、行数に依存するquery計数から分離する。
        var result = capture(request);
        var followSql = result.sql().stream().filter(sql -> compact(sql).contains("from follows")
                && compact(sql).contains("team_friends")).toList();
        var summarySql = result.sql().stream().filter(sql -> compact(sql).contains("from teams")).toList();
        assertThat(followSql).hasSize(SAMPLES * 2);
        assertThat(summarySql).as("公開概要は各ページ一括一回").hasSize(SAMPLES);
        var plans = new ArrayList<Map<String, Object>>();
        for (String sql : followSql.stream().distinct().toList()) {
            var query = em.createNativeQuery("EXPLAIN " + sql);
            int count = (int) sql.chars().filter(c -> c == '?').count();
            for (int i = 1; i <= count; i++) query.setParameter(i, own.getId());
            String noSpaces = sql.replaceAll("\\s+", "");
            if (noSpaces.endsWith("limit?,?")) {
                query.setParameter(count - 1, 0).setParameter(count, ROWS);
            } else if (noSpaces.endsWith("limit?")) query.setParameter(count, ROWS);
            @SuppressWarnings("unchecked") List<Object[]> rows = query.getResultList();
            plans.add(Map.of("sql", sql, "explain", rows.stream().map(Arrays::asList).toList()));
        }
        var relevant = new ArrayList<>(followSql); relevant.addAll(summarySql);
        report("incoming-performance", result, relevant, plans, 3);
    }

    private Capture capture(Callable<JsonNode> request) throws Exception {
        var logger = (Logger) LoggerFactory.getLogger("org.hibernate.SQL");
        var previous = logger.getLevel();
        var appender = new ListAppender<ILoggingEvent>(); appender.start(); logger.addAppender(appender); logger.setLevel(Level.DEBUG);
        var elapsed = new ArrayList<Double>();
        try {
            for (int i = 0; i < SAMPLES; i++) {
                long started = System.nanoTime(); JsonNode response = request.call();
                elapsed.add((System.nanoTime() - started) / 1_000_000.0);
                assertThat(response.path("data").size()).isEqualTo(ROWS);
                assertThat(response.path("pagination").path("totalElements").asLong()).isEqualTo(ROWS);
            }
            return new Capture(appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList(), elapsed);
        } finally { logger.detachAppender(appender); appender.stop(); logger.setLevel(previous); }
    }

    private void report(String name, Capture capture, List<String> sql, List<Map<String, Object>> plans, int perPage) throws Exception {
        var sorted = capture.elapsedMs().stream().sorted().toList();
        var path = Path.of("build/reports/friend-request/" + name + ".json"); Files.createDirectories(path.getParent());
        Files.writeString(path, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("rows", ROWS,
                "samples", SAMPLES, "pageSize", ROWS, "queriesPerPage", perPage, "observedSqlCount", sql.size(),
                "p95Ms", sorted.get((int) Math.ceil(SAMPLES * 0.95) - 1), "elapsedMs", capture.elapsedMs(), "queries", plans)));
    }

    private String compact(String sql) { return sql.toLowerCase(Locale.ROOT).replaceAll("\\s+", " "); }
    /** 実行中に捕捉したSQLとAPI所要時間。 */
    private record Capture(List<String> sql, List<Double> elapsedMs) { }
}
