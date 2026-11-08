package com.mannschaft.app.dashboard.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.schedule.EventType;
import com.mannschaft.app.schedule.MinViewRole;
import com.mannschaft.app.schedule.ScheduleStatus;
import com.mannschaft.app.schedule.ScheduleVisibility;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.N1SeedSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 個人ダッシュボード unread-threads / upcoming-events の N+1 解消を固定する試練（CMP-261103-1225 / #4270）。
 *
 * <p>SQL 数を {@link Statistics#getPrepareStatementCount()} で計測し、所属チーム数・スレッド数・
 * 予定数・days を独立に増やしても一定であること（AC-1）と、応答が修正前と等価であること（AC-2）を固定する。</p>
 */
@AutoConfigureMockMvc
@Transactional
@DisplayName("DashboardController unread-threads / upcoming-events の N+1 解消")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class DashboardControllerN1IT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ScheduleRepository scheduleRepository;
    @PersistenceContext
    private EntityManager em;

    private N1SeedSupport seed;

    @BeforeEach
    void setUp() {
        seed = new N1SeedSupport(em);
    }

    private JsonNode call(String path, Long userId, String... params) throws Exception {
        var req = get(path).with(user(String.valueOf(userId)));
        for (int i = 0; i + 1 < params.length; i += 2) {
            req = req.param(params[i], params[i + 1]);
        }
        String body = mockMvc.perform(req).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("data");
    }

    private long measure(Statistics s, ThrowingRunnable r) throws Exception {
        em.clear();
        s.clear();
        r.run();
        return s.getPrepareStatementCount();
    }

    @FunctionalInterface
    interface ThrowingRunnable {
        void run() throws Exception;
    }

    // ─────────────────────────────────────────────
    // unread-threads
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/v1/dashboard/unread-threads")
    class UnreadThreads {

        @Test
        @DisplayName("AC-1/AC-2/AC-7: 所属チーム数・スレッド数を増やしても SQL 数が一定で、未参加チームの未読は混入しない")
        void sqlCountIndependentOfTeamsAndThreads() throws Exception {
            LocalDateTime base = LocalDateTime.of(2026, 1, 1, 0, 0);
            // small: 1 チーム × 1 スレッド（未読）
            Long small = seed.user("small");
            Long t0 = seed.team("N1-unread-small", null);
            seed.joinTeam(small, t0, "MEMBER");
            seed.bulletinThread("TEAM", t0, small, "s", false, base);

            // large: 3 チーム × 3 スレッド（各チーム 1 件既読）→ 未読 6
            Long large = seed.user("large");
            for (int t = 0; t < 3; t++) {
                Long team = seed.team("N1-unread-large-" + t, null);
                seed.joinTeam(large, team, "MEMBER");
                for (int i = 0; i < 3; i++) {
                    Long th = seed.bulletinThread("TEAM", team, large, "l" + t + i, false, base.plusHours(i));
                    if (i == 0) {
                        seed.readThread(th, large);
                    }
                }
            }
            // 未参加チームのスレッド（混入してはならない）
            Long foreign = seed.team("N1-unread-foreign", null);
            seed.bulletinThread("TEAM", foreign, small, "f1", false, base);
            seed.bulletinThread("TEAM", foreign, small, "f2", false, base);
            // 他人の既読は自分の既読にならない
            Long otherTeamThread = seed.bulletinThread("TEAM", t0, small, "s2", false, base.plusHours(1));
            seed.readThread(otherTeamThread, large);
            seed.flushAndClear();

            Statistics s = seed.stats();
            JsonNode[] smallRes = new JsonNode[1];
            JsonNode[] largeRes = new JsonNode[1];
            long smallCount = measure(s, () -> smallRes[0] = call("/api/v1/dashboard/unread-threads", small));
            long largeCount = measure(s, () -> largeRes[0] = call("/api/v1/dashboard/unread-threads", large));
            System.out.println("N1 unread-threads SQL small=" + smallCount + " large=" + largeCount);

            assertThat(smallRes[0].get("total_unread_bulletin").asLong()).isEqualTo(2);
            assertThat(largeRes[0].get("total_unread_bulletin").asLong()).isEqualTo(6);
            assertThat(largeCount).as("チーム数・スレッド数に依存しない SQL 数").isEqualTo(smallCount);
        }

        @Test
        @DisplayName("AC-2/AC-10: チームごとの走査上限 100 件（ピン留め優先→更新日時降順）を修正前と同じく守る")
        void perTeamScanLimitIsPreserved() throws Exception {
            LocalDateTime base = LocalDateTime.of(2026, 1, 1, 0, 0);
            Long me = seed.user("cap");
            // チームA: 101 件。最古（非ピン留め）だけ未読 → 上限外なので 0 件
            Long a = seed.team("N1-cap-a", null);
            seed.joinTeam(me, a, "MEMBER");
            for (int i = 0; i < 101; i++) {
                Long th = seed.bulletinThread("TEAM", a, me, "a" + i, false, base.plusMinutes(i));
                if (i != 0) {
                    seed.readThread(th, me);
                }
            }
            // チームB: 100 件。最古だけ未読 → 上限内なので 1 件
            Long b = seed.team("N1-cap-b", null);
            seed.joinTeam(me, b, "MEMBER");
            for (int i = 0; i < 100; i++) {
                Long th = seed.bulletinThread("TEAM", b, me, "b" + i, false, base.plusMinutes(i));
                if (i != 0) {
                    seed.readThread(th, me);
                }
            }
            // チームC: 101 件。最古だがピン留めのものが未読 → ピン留め優先で上限内に入るので 1 件
            Long c = seed.team("N1-cap-c", null);
            seed.joinTeam(me, c, "MEMBER");
            for (int i = 0; i < 101; i++) {
                Long th = seed.bulletinThread("TEAM", c, me, "c" + i, i == 0, base.plusMinutes(i));
                if (i != 0) {
                    seed.readThread(th, me);
                }
            }
            seed.flushAndClear();

            JsonNode res = call("/api/v1/dashboard/unread-threads", me);
            assertThat(res.get("total_unread_bulletin").asLong()).isEqualTo(2);
        }

        @Test
        @DisplayName("AC-3: 所属なし・チャンネルなしでも空 IN でエラーにならず 0 件を返す")
        void emptyMembership() throws Exception {
            Long me = seed.user("empty");
            seed.flushAndClear();
            JsonNode res = call("/api/v1/dashboard/unread-threads", me);
            assertThat(res.get("total_unread_bulletin").asLong()).isZero();
            assertThat(res.get("total_unread_chat").asLong()).isZero();
        }
    }

    // ─────────────────────────────────────────────
    // upcoming-events
    // ─────────────────────────────────────────────

    private ScheduleEntity schedule(Long teamId, Long orgId, Long userId, String title, LocalDateTime startAt) {
        return scheduleRepository.save(ScheduleEntity.builder()
                .teamId(teamId).organizationId(orgId).userId(userId)
                .title(title).startAt(startAt)
                .eventType(EventType.EVENT)
                .visibility(ScheduleVisibility.MEMBERS_ONLY)
                .minViewRole(MinViewRole.MEMBER_PLUS)
                .status(ScheduleStatus.SCHEDULED)
                .build());
    }

    @Nested
    @DisplayName("GET /api/v1/dashboard/upcoming-events")
    class UpcomingEvents {

        @Test
        @DisplayName("AC-1/AC-2/AC-5: チーム数・組織数・予定数・days を増やしても SQL 数が一定で、名前・アイコン・順序が等価")
        void sqlCountIndependentOfScopesAndSchedules() throws Exception {
            LocalDateTime tomorrow = LocalDate.now().plusDays(1).atTime(10, 0);

            // small: 1 チーム × 1 予定 + 1 組織 × 1 予定、days=7
            //（0→1 の差ではなく、件数の増加で SQL が増えないことを比べるため、small にも各種を 1 件ずつ置く）
            Long small = seed.user("up-small");
            Long st = seed.team("N1-up-small", "icon/small.png");
            seed.joinTeam(small, st, "MEMBER");
            schedule(st, null, null, "small-ev", tomorrow);
            Long so = seed.organization("N1-up-small-org", "icon/so.png");
            seed.joinOrganization(small, so, "MEMBER");
            schedule(null, so, null, "small-org-ev", tomorrow.plusHours(1));

            // large: 3 チーム（うち 1 つは論理削除）× 2 予定 + 2 組織 × 2 予定 + 個人予定、days=30
            Long large = seed.user("up-large");
            List<Long> teams = new ArrayList<>();
            for (int t = 0; t < 3; t++) {
                Long team = seed.team("N1-up-team-" + t, "icon/t" + t + ".png");
                seed.joinTeam(large, team, "MEMBER");
                teams.add(team);
                schedule(team, null, null, "team" + t + "-a", tomorrow.plusHours(t));
                schedule(team, null, null, "team" + t + "-b", tomorrow.plusDays(10).plusHours(t));
            }
            List<Long> orgs = new ArrayList<>();
            for (int o = 0; o < 2; o++) {
                Long org = seed.organization("N1-up-org-" + o, "icon/o" + o + ".png");
                seed.joinOrganization(large, org, "MEMBER");
                orgs.add(org);
                schedule(null, org, null, "org" + o + "-a", tomorrow.plusMinutes(30 + o));
                schedule(null, org, null, "org" + o + "-b", tomorrow.plusDays(20).plusMinutes(o));
            }
            schedule(null, null, large, "personal", tomorrow.plusMinutes(5));
            seed.softDeleteTeam(teams.get(2));
            seed.flushAndClear();

            Statistics s = seed.stats();
            JsonNode[] smallRes = new JsonNode[1];
            JsonNode[] largeRes = new JsonNode[1];
            long smallCount = measure(s, () -> smallRes[0] =
                    call("/api/v1/dashboard/upcoming-events", small, "days", "7"));
            long largeCount = measure(s, () -> largeRes[0] =
                    call("/api/v1/dashboard/upcoming-events", large, "days", "30"));
            System.out.println("N1 upcoming-events SQL small=" + smallCount + " large=" + largeCount);

            assertThat(smallRes[0]).hasSize(2);
            assertThat(smallRes[0].get(0).get("scope_name").asText()).isEqualTo("N1-up-small");
            assertThat(smallRes[0].get(0).get("scope_icon_url").asText()).isEqualTo("icon/small.png");
            assertThat(smallRes[0].get(1).get("scope_name").asText()).isEqualTo("N1-up-small-org");

            List<String> titles = new ArrayList<>();
            largeRes[0].forEach(n -> titles.add(n.get("title").asText()));
            assertThat(titles).containsExactly(
                    "team0-a", "personal", "org0-a", "org1-a", "team1-a", "team2-a",
                    "team0-b", "team1-b", "team2-b", "org0-b", "org1-b");
            for (JsonNode n : largeRes[0]) {
                String title = n.get("title").asText();
                String scopeType = n.get("scope_type").asText();
                if (title.startsWith("team2")) {
                    // 論理削除済みチーム: 修正前（findById が @SQLRestriction で空）と同じく名前・アイコンは null
                    assertThat(scopeType).isEqualTo("TEAM");
                    assertThat(n.get("scope_name").isNull()).isTrue();
                    assertThat(n.get("scope_icon_url").isNull()).isTrue();
                } else if (title.startsWith("team")) {
                    int t = title.charAt(4) - '0';
                    assertThat(scopeType).isEqualTo("TEAM");
                    assertThat(n.get("scope_name").asText()).isEqualTo("N1-up-team-" + t);
                    assertThat(n.get("scope_icon_url").asText()).isEqualTo("icon/t" + t + ".png");
                } else if (title.startsWith("org")) {
                    int o = title.charAt(3) - '0';
                    assertThat(scopeType).isEqualTo("ORG");
                    assertThat(n.get("scope_name").asText()).isEqualTo("N1-up-org-" + o);
                    assertThat(n.get("scope_icon_url").asText()).isEqualTo("icon/o" + o + ".png");
                } else {
                    assertThat(scopeType).isEqualTo("PERSONAL");
                    assertThat(n.get("scope_name").isNull()).isTrue();
                }
            }
            assertThat(largeCount).as("チーム数・組織数・予定数・days に依存しない SQL 数").isEqualTo(smallCount);
        }

        @Test
        @DisplayName("AC-3: 所属なし・予定なしでも空 IN でエラーにならず空配列を返す")
        void emptyMembership() throws Exception {
            Long me = seed.user("up-empty");
            seed.flushAndClear();
            JsonNode res = call("/api/v1/dashboard/upcoming-events", me, "days", "7");
            assertThat(res).isEmpty();
        }
    }
}
