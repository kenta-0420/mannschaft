package com.mannschaft.app.social.announcement.audience;

import com.mannschaft.app.organization.entity.OrganizationEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * F01.2.1 AC-G102（宛先プレビュー 60件/分/ユーザー。§10.10）が<b>実 HTTP 経路（Servlet Filter Chain 込み）で
 * 結線されている</b>ことの統合テスト。
 *
 * <p>基底クラスは {@code StringRedisTemplate} を Mock にしており、素のままでは Valkey の Lua 実行が null を返して
 * fail-open（429 が一度も出ない）になる。Lua の戻り値をプロセス内カウンタでスタブして Valkey の代役にする。
 * {@code servletPath} は本番と同じフルパスを明示する（MockMvc の既定は空文字で、フィルタが一致しない）。</p>
 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F01.2.1 AC-G102 宛先プレビュー レートリミット 実HTTP経路の結線")
class BroadcastAudiencePreviewRateLimitWiringIT extends AbstractBroadcastAudienceIT {

    private static final int LIMIT = 60;

    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();

    private OrganizationEntity orgX;

    @BeforeEach
    void setUp() {
        counters.clear();
        willAnswer(invocation -> {
            List<?> keys = invocation.getArgument(1);
            String redisKey = String.valueOf(keys.get(0));
            return counters.computeIfAbsent(redisKey, k -> new AtomicLong()).incrementAndGet();
        }).given(redisTemplate).execute(any(RedisScript.class), anyList(), any());

        orgX = newOrg(true);
        seedOrgPerson(XA, orgX.getId(), "ADMIN");
        seedOrgPerson(XM, orgX.getId(), "MEMBER");
        flushAndClear();
    }

    @Test
    @DisplayName("プレビューは 60 回までフィルタを通過し、61 回目は 429 + Retry-After")
    void sixtyFirstIs429() throws Exception {
        for (int i = 0; i < LIMIT; i++) {
            MvcResult result = callPreview(XA);
            assertThat(result.getResponse().getStatus()).as("プレビュー #%d", i + 1).isEqualTo(200);
            assertThat(result.getResponse().getHeader("X-RateLimit-Limit"))
                    .as("フィルタがチェーンに登録されていなければヘッダは付かない").isEqualTo(String.valueOf(LIMIT));
        }
        MvcResult over = callPreview(XA);
        assertThat(over.getResponse().getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(over.getResponse().getHeader("Retry-After")).isNotNull().matches("\\d+");
    }

    @Test
    @DisplayName("ユーザーごとに独立し、プレビューは告知送信（5件/5分）の枠を消費しない")
    void independentPerUserAndFromBroadcastQuota() throws Exception {
        for (int i = 0; i < LIMIT + 1; i++) {
            callPreview(XA);
        }
        assertThat(callPreview(XA).getResponse().getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(callPreview(XM).getResponse().getStatus()).isEqualTo(200);
        assertThat(counters.keySet()).noneMatch(k -> k.contains("broadcast:send"));
    }

    private MvcResult callPreview(Long actor) throws Exception {
        String path = "/api/v1/organizations/" + orgX.getId() + "/broadcast/audience-preview";
        return mockMvc.perform(post(path).servletPath(path)
                        .with(user(actor.toString()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bulletinBody(Map.of())))
                .andReturn();
    }
}
