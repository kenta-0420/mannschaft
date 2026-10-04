package com.mannschaft.app.organization.teamgroup;

import com.mannschaft.app.common.ratelimit.ValkeyRateLimiter;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.teamgroup.entity.OrgTeamGroupEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.willAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * F01.2.1 AC-G102（一括割当 20件/分/ユーザー）が<b>実 HTTP 経路（Servlet Filter Chain 込み）で結線されている</b>
 * ことの統合テスト。
 *
 * <p>基底クラスは {@code StringRedisTemplate} を Mock にしており、素のままでは Valkey の Lua 実行が null を返して
 * fail-open（429 が一度も出ない＝常に緑で何も検証しないテスト）になる。そのため Lua スクリプトの戻り値を
 * プロセス内カウンタでスタブして Valkey の代役にする。{@code servletPath} は本番（組み込み Tomcat）と同じフルパスを
 * 明示する（MockMvc は既定で空文字のままにし、何もしないとフィルタが一件も一致しない）。</p>
 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F01.2.1 AC-G102 一括割当 レートリミット 実HTTP経路の結線")
class OrgTeamGroupAssignmentRateLimitWiringIT extends AbstractOrgTeamGroupAssignmentIT {

    private static final int LIMIT = 20;

    /** Valkey の代役（zone+key ごとの固定ウィンドウカウンタ）。 */
    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();

    private OrganizationEntity org;
    private OrgTeamGroupEntity group;
    private String bulkPath;

    @Autowired
    private ValkeyRateLimiter rateLimiter;

    private Clock originalClock;

    @BeforeEach
    void setUp() {
        originalClock = (Clock) ReflectionTestUtils.getField(rateLimiter, "clock");
        // 結線・主体別カウンタの試験中に実時刻の分境界で別ウィンドウへ移ることを防ぐ。
        ReflectionTestUtils.setField(rateLimiter, "clock",
                Clock.fixed(Instant.parse("2026-03-03T00:00:30Z"), ZoneOffset.UTC));
        counters.clear();
        willAnswer(invocation -> {
            List<?> keys = invocation.getArgument(1);
            String redisKey = String.valueOf(keys.get(0));
            return counters.computeIfAbsent(redisKey, k -> new AtomicLong()).incrementAndGet();
        }).given(redisTemplate).execute(any(RedisScript.class), anyList(), any());

        org = newOrg(true);
        group = newGroup(org.getId(), "A班", 0);
        seedOrgPerson(AXA, org.getId(), "ADMIN");
        seedOrgPerson(AYA, newOrg(true).getId(), "ADMIN");
        em.flush();
        em.clear();
        bulkPath = "/api/v1/organizations/" + org.getSlug() + "/team-group-assignments";
    }

    @AfterEach
    void restoreClock() {
        ReflectionTestUtils.setField(rateLimiter, "clock", originalClock);
    }

    @Test
    @DisplayName("一括割当は 20 回までフィルタを通過し、21 回目は 429 + Retry-After + X-RateLimit-*（通過分にもヘッダが載る）")
    void twentyFirstIs429() throws Exception {
        for (int i = 0; i < LIMIT; i++) {
            MvcResult result = bulk(AXA);
            assertThat(result.getResponse().getStatus())
                    .as("一括割当 #%d はレートリミットでは弾かれない（業務上は 400 ORG_069）", i + 1)
                    .isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
            assertThat(result.getResponse().getHeader("X-RateLimit-Limit"))
                    .as("フィルタがチェーンに登録されていなければヘッダは1つも付かない")
                    .isEqualTo(String.valueOf(LIMIT));
            assertThat(result.getResponse().getHeader("X-RateLimit-Remaining"))
                    .isEqualTo(String.valueOf(LIMIT - 1 - i));
        }

        MvcResult over = bulk(AXA);

        assertThat(over.getResponse().getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(over.getResponse().getHeader("Retry-After")).isNotNull().matches("\\d+");
        assertThat(over.getResponse().getHeader("X-RateLimit-Remaining")).isEqualTo("0");
    }

    @Test
    @DisplayName("別ユーザーのカウンタは独立している（組織が違っても、別の操作者は 429 にならない）")
    void otherUsersHaveTheirOwnCounter() throws Exception {
        for (int i = 0; i < LIMIT + 1; i++) {
            bulk(AXA);
        }
        assertThat(bulk(AXA).getResponse().getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());

        assertThat(bulk(AYA).getResponse().getStatus()).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
    }

    @Test
    @DisplayName("一括割当だけが対象。単体割当の PUT・加盟チーム一覧の GET は数えない（ヘッダが載らず、枠も消費しない）")
    void onlyTheBulkEndpointIsCounted() throws Exception {
        String singlePath = "/api/v1/organizations/" + org.getSlug() + "/teams/some-team/team-group";
        String listPath = "/api/v1/organizations/" + org.getSlug() + "/teams";
        for (int i = 0; i < LIMIT + 3; i++) {
            MvcResult single = mockMvc.perform(put(singlePath).servletPath(singlePath)
                            .with(user(String.valueOf(AXA)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"groupId\":\"" + group.getId() + "\"}"))
                    .andReturn();
            assertThat(single.getResponse().getHeader("X-RateLimit-Limit")).isNull();
            MvcResult list = mockMvc.perform(get(listPath).servletPath(listPath).with(user(String.valueOf(AXA))))
                    .andReturn();
            assertThat(list.getResponse().getHeader("X-RateLimit-Limit")).isNull();
        }

        assertThat(bulk(AXA).getResponse().getHeader("X-RateLimit-Remaining"))
                .as("一括割当の枠は他の操作で1つも減っていない").isEqualTo(String.valueOf(LIMIT - 1));
    }

    private MvcResult bulk(long actor) throws Exception {
        return mockMvc.perform(put(bulkPath).servletPath(bulkPath)
                        .with(user(String.valueOf(actor)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupId\":\"" + group.getId() + "\",\"teamSlugs\":[\"no-such-team\"]}"))
                .andReturn();
    }
}
