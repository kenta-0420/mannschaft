package com.mannschaft.app.organization.teamaffiliation;

import com.mannschaft.app.organization.filter.OrgAffiliationEligibilityRateLimitFilter;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static com.mannschaft.app.organization.teamaffiliation.TeamAffiliationApiFixture.N;
import static com.mannschaft.app.organization.teamaffiliation.TeamAffiliationApiFixture.TA;
import static com.mannschaft.app.organization.teamaffiliation.TeamAffiliationApiFixture.TD;
import static com.mannschaft.app.organization.teamaffiliation.TeamAffiliationApiFixture.TG;
import static com.mannschaft.app.organization.teamaffiliation.TeamAffiliationApiFixture.TM;
import static com.mannschaft.app.organization.teamaffiliation.TeamAffiliationApiFixture.XA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.willAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F01.2.1 部隊 2-A — 申請ボタン判定 API（{@code GET /api/v1/me/org-affiliation-eligibility?organizationSlug=}）の
 * 受け入れテスト（試練）。
 *
 * <p>正本: {@code docs/features/F01.2.1_org_team_groups.md} §10.3（マスター確定 M3）・§10.10・§16 A。
 * 実 Security フィルタ（レートリミットのフィルタも含む）・Testcontainers MySQL・実 Service を通す。</p>
 *
 * <p>担当 AC: A12（存在しない・非公開・受付 off の3つとも 200 {@code canApply:false} で本文が同一）・
 * G102（eligibility 60件/分/ユーザーを超えると 429）。canApply が true になるのは
 * 「見えて・受付中で・加盟操作権限のあるチームが1つ以上」のときだけであることも固定する（A09・A11 の BE 判定）。</p>
 *
 * <p>レートリミットの実体: 基底クラスが {@code StringRedisTemplate} を Mock に差し替えており、素の Mock は
 * {@code ValkeyRateLimiter} を fail-open にするため、429 を見るケースだけ Lua 実行の戻り値をプロセス内カウンタで
 * スタブする（{@code AnnouncementReadRateLimitWiringIT} と同じ作法）。フィルタはサーブレットパスで対象を判定するので、
 * {@code servletPath} を本番と同じフルパスに設定する。</p>
 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F01.2.1 2-A 申請ボタン判定（eligibility）API 受け入れテスト")
class OrgAffiliationEligibilityApiIT extends AbstractMySqlIntegrationTest {

    private static final String PATH = "/api/v1/me/org-affiliation-eligibility";

    @Autowired
    private MockMvc mockMvc;

    @PersistenceContext
    private EntityManager em;

    private TeamAffiliationApiFixture fx;

    @BeforeEach
    void setUp() {
        fx = new TeamAffiliationApiFixture(em).seed();
    }

    private MvcResult eligibility(Long userId, String slug) throws Exception {
        return mockMvc.perform(get(PATH)
                        .servletPath(PATH)
                        .param("organizationSlug", slug)
                        .with(user(userId.toString())))
                .andReturn();
    }

    private void assertCanApply(Long userId, String slug, boolean expected) throws Exception {
        MvcResult r = eligibility(userId, slug);
        assertThat(r.getResponse().getStatus()).as("eligibility は常に 200").isEqualTo(200);
        assertThat(r.getResponse().getContentAsString())
                .as("userId=%s slug=%s", userId, slug)
                .isEqualTo("{\"data\":{\"canApply\":" + expected + "}}");
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-A12
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-A12: 存在しない slug・非公開組織の slug・受付 off の組織の slug は、3つとも 200 {canApply:false} で本文が同一")
    void falseCasesAreIdentical() throws Exception {
        // 非公開組織は受付 on にしておく（受付状態が漏れないことも見る）
        fx.setOrgSettings(fx.orgPId, true, false, "OFF");

        MvcResult absent = eligibility(TA, fx.absentSlug);
        MvcResult priv = eligibility(TA, fx.orgPSlug);
        MvcResult closed = eligibility(TA, fx.orgXSlug);

        for (MvcResult r : List.of(absent, priv, closed)) {
            assertThat(r.getResponse().getStatus()).isEqualTo(200);
        }
        String expected = "{\"data\":{\"canApply\":false}}";
        assertThat(absent.getResponse().getContentAsString()).isEqualTo(expected);
        assertThat(priv.getResponse().getContentAsString()).isEqualTo(expected);
        assertThat(closed.getResponse().getContentAsString()).isEqualTo(expected);
    }

    @Test
    @DisplayName("§10.3: 受付 on の公開組織では、加盟操作権限を持つ TA・TG は true、持たない TM・TD・N・組織 ADMIN の XA は false")
    void trueOnlyForOperators() throws Exception {
        fx.setOrgSettings(fx.orgXId, true, false, "OFF");

        assertCanApply(TA, fx.orgXSlug, true);
        assertCanApply(TG, fx.orgXSlug, true);
        for (Long actor : new Long[] {TM, TD, N, XA}) {
            assertCanApply(actor, fx.orgXSlug, false);
        }
    }

    @Test
    @DisplayName("§10.3: 受付 on でもアーカイブ済みの組織は false（不在と同じ本文）")
    void archivedOrgIsFalse() throws Exception {
        fx.setOrgSettings(fx.orgXId, true, false, "OFF");
        em.createNativeQuery("UPDATE organizations SET archived_at = UTC_TIMESTAMP() WHERE id = :id")
                .setParameter("id", fx.orgXId)
                .executeUpdate();
        em.flush();
        em.clear();

        assertCanApply(TA, fx.orgXSlug, false);
    }

    @Test
    @DisplayName("§10.1: 未認証で呼ぶと 401")
    void unauthenticated() throws Exception {
        mockMvc.perform(get(PATH).servletPath(PATH).param("organizationSlug", fx.orgXSlug))
                .andExpect(status().isUnauthorized());
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-G102（eligibility 60件/分）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-G102 レートリミット（eligibility 60件/分/ユーザー）")
    class RateLimit {

        private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();

        @BeforeEach
        void stubValkey() {
            counters.clear();
            willAnswer(invocation -> {
                List<?> keys = invocation.getArgument(1);
                String redisKey = String.valueOf(keys.get(0));
                return counters.computeIfAbsent(redisKey, k -> new AtomicLong()).incrementAndGet();
            }).given(redisTemplate).execute(any(RedisScript.class), anyList(), any());
        }

        @Test
        @DisplayName("AC-G102: 60回目までは 200、61回目は 429 + Retry-After。別ユーザーは影響を受けない")
        void exceedingLimitReturns429() throws Exception {
            assertThat(OrgAffiliationEligibilityRateLimitFilter.LIMIT_PER_MINUTE).isEqualTo(60);
            for (int i = 0; i < 60; i++) {
                MvcResult ok = eligibility(TA, fx.orgXSlug);
                assertThat(ok.getResponse().getStatus()).as("#%d", i + 1).isEqualTo(200);
                assertThat(ok.getResponse().getHeader("X-RateLimit-Limit")).isEqualTo("60");
            }
            MvcResult over = eligibility(TA, fx.orgXSlug);
            assertThat(over.getResponse().getStatus()).isEqualTo(429);
            assertThat(over.getResponse().getHeader("Retry-After")).isNotBlank();

            MvcResult other = eligibility(TM, fx.orgXSlug);
            assertThat(other.getResponse().getStatus()).isEqualTo(200);
        }
    }
}
