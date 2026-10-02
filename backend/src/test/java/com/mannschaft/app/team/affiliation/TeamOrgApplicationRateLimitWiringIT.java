package com.mannschaft.app.team.affiliation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * F01.2.1 AC-G102（チームの加盟申請 10件/時/ユーザー）が<b>実 HTTP 経路（Servlet Filter Chain 込み）で結線されている</b>
 * ことの統合テスト。
 *
 * <p>フィルタ単体の境界は {@code TeamOrgApplicationRateLimitFilterTest} が見る。こちらは、フィルタが実際に
 * チェーンに登録され、申請の POST に効き、超過で 429 + {@code Retry-After} が返り、一覧の GET には効かないことを見る。
 * 基底クラスは {@code StringRedisTemplate} を Mock にしており、素のままでは Valkey の Lua 実行が null を返して
 * fail-open（429 が一度も出ない＝常に緑で何も検証しないテスト）になる。そのため Lua スクリプトの戻り値を
 * プロセス内カウンタでスタブして Valkey の代役にする。{@code servletPath} は本番（組み込み Tomcat）と同じフルパスを
 * 明示する（MockMvc は既定で空文字のままにし、何もしないとフィルタが一件も一致しない）。</p>
 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F01.2.1 AC-G102 チームの加盟申請 レートリミット 実HTTP経路の結線")
class TeamOrgApplicationRateLimitWiringIT extends TeamAffiliationItSupport {

    private static final int LIMIT = 10;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    /** Valkey の代役（zone+key ごとの固定ウィンドウカウンタ）。 */
    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();

    private TeamFx team;
    private OrgFx org;
    private long operator;
    private long anotherOperator;

    @BeforeEach
    void setUp() {
        counters.clear();
        willAnswer(invocation -> {
            List<?> keys = invocation.getArgument(1);
            String redisKey = String.valueOf(keys.get(0));
            return counters.computeIfAbsent(redisKey, k -> new AtomicLong()).incrementAndGet();
        }).given(redisTemplate).execute(any(RedisScript.class), anyList(), any());

        seedAffiliationPermission();
        team = newTeam();
        org = newOrg();
        operator = newUser();
        anotherOperator = newUser();
        makeTeamAdmin(operator, team.id());
        makeTeamAdmin(anotherOperator, team.id());
        em.flush();
        em.clear();
    }

    @Test
    @DisplayName("申請は 10 回までフィルタを通過し、11 回目は 429 + Retry-After + X-RateLimit-*（通過分にもヘッダが載る）")
    void 十一回目は429() throws Exception {
        for (int i = 0; i < LIMIT; i++) {
            MvcResult result = apply(operator);
            assertThat(result.getResponse().getStatus())
                    .as("申請 #%d はレートリミットでは弾かれない（業務上は 201 または 409）", i + 1)
                    .isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
            assertThat(result.getResponse().getHeader("X-RateLimit-Limit"))
                    .as("フィルタがチェーンに登録されていなければヘッダは1つも付かない")
                    .isEqualTo(String.valueOf(LIMIT));
            assertThat(result.getResponse().getHeader("X-RateLimit-Remaining"))
                    .isEqualTo(String.valueOf(LIMIT - 1 - i));
        }

        MvcResult over = apply(operator);

        assertThat(over.getResponse().getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(over.getResponse().getHeader("Retry-After")).isNotNull().matches("\\d+");
        assertThat(over.getResponse().getHeader("X-RateLimit-Remaining")).isEqualTo("0");
        assertThat(over.getResponse().getContentAsString()).contains("Too many requests");
    }

    @Test
    @DisplayName("別ユーザーのカウンタは独立している（同じチームの別の操作者は 429 にならない）")
    void 別ユーザーは独立() throws Exception {
        for (int i = 0; i < LIMIT + 1; i++) {
            apply(operator);
        }
        assertThat(apply(operator).getResponse().getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());

        assertThat(apply(anotherOperator).getResponse().getStatus())
                .isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
    }

    @Test
    @DisplayName("申請中一覧の GET には効かない（ヘッダが載らず、申請の枠も消費しない）")
    void 一覧のGETは対象外() throws Exception {
        String path = "/api/v1/teams/" + team.slug() + "/org-applications";
        for (int i = 0; i < LIMIT + 3; i++) {
            MvcResult list = mockMvc.perform(get(path).servletPath(path).with(user(String.valueOf(operator))))
                    .andReturn();
            assertThat(list.getResponse().getHeader("X-RateLimit-Limit")).isNull();
        }

        MvcResult first = apply(operator);
        assertThat(first.getResponse().getHeader("X-RateLimit-Remaining"))
                .as("申請の枠は一覧の GET で1つも減っていない").isEqualTo(String.valueOf(LIMIT - 1));
    }

    private MvcResult apply(long actor) throws Exception {
        String path = "/api/v1/teams/" + team.slug() + "/org-applications";
        return mockMvc.perform(post(path).servletPath(path)
                        .with(user(String.valueOf(actor)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("organizationSlug", org.slug()))))
                .andReturn();
    }
}
