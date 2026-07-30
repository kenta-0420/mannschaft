package com.mannschaft.app.social.announcement;

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
import org.springframework.http.HttpStatus;
import org.springframework.security.test.context.support.WithMockUser;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 既読 EP のレートリミットが<b>実 HTTP 経路（Servlet Filter Chain 込み）で結線されている</b>ことの
 * 統合テスト（#2530 ④）。
 *
 * <p><b>なぜ別クラスなのか</b>: 既存の {@link AnnouncementReadUnreadOnlyBulkIT} /
 * {@link SocialAnnouncementScopeContractIT} は {@code @AutoConfigureMockMvc(addFilters = false)}
 * であり、{@link AnnouncementReadRateLimitFilter} を<b>一度も通っていない</b>。
 * 既存 IT を {@code addFilters = true} に切り替えると認証・認可のフィルタが全部乗って
 * 既存ケースが壊れるため、フィルタ有効の IT は本クラスに分離する。</p>
 *
 * <p><b>本 IT が固定すること</b>（{@code AnnouncementReadRateLimitFilterTest} が
 * フィルタ単体で見ているのに対し、こちらは<b>結線</b>を見る）:</p>
 * <ol>
 *   <li>フィルタが実際にサーブレットフィルタチェーンに登録されている
 *       — 通常応答（200）にも {@code X-RateLimit-*} が載ることで裏を取る</li>
 *   <li>対象パス（{@code read-all} / 単件 {@code read}）に効き、超過で
 *       <b>429 + {@code Retry-After}</b> が実 HTTP 応答として返る</li>
 *   <li>非対象パス（お知らせ一覧 {@code GET}）には効かない（ヘッダーが 1 つも載らない）</li>
 * </ol>
 *
 * <p><b>レートリミッタの実体</b>: 本テストコンテキストでは
 * {@link AbstractMySqlIntegrationTest} が {@code StringRedisTemplate} を Mockito Bean に
 * 差し替えている。素の Mock は {@code execute(...)} が {@code null} を返すため
 * {@link com.mannschaft.app.common.ratelimit.ValkeyRateLimiter} が
 * <b>fail-open</b>（可用性優先で通す）に落ち、429 が一度も出ない
 * ＝「常に緑だが何も検証していない」テストになる。
 * {@code AnnouncementReadRateLimitFilterTest} の {@code LimiterBeanAbsent} が固定しているのは
 * まさにこの「Bean 不在なら素通し」という設計なので、本 IT では
 * <b>Lua スクリプトの戻り値をプロセス内カウンタでスタブ</b>して Valkey の代役を務めさせる。
 * これによりフィルタ → {@code ValkeyRateLimiter} → 429 書き出しまでの実経路が
 * 決定論的に通る（Valkey コンテナを増やさずに済む）。</p>
 *
 * <p>構成は既存のフィルタ有効 IT（{@code ActivityPublicContractIT} 等）に揃え、
 * {@code @SpringBootTest} / {@code @Testcontainers} / {@code @ActiveProfiles} は
 * <b>再宣言しない</b>（TestContext Cache 分裂＝OOM を避ける）。
 * {@code @EnabledIf} は {@code @Inherited} ではないため再宣言が必須。</p>
 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("お知らせ既読EP レートリミット 実HTTP経路の結線 統合テスト（#2530 ④）")
class AnnouncementReadRateLimitWiringIT extends AbstractMySqlIntegrationTest {

    /** 認証ユーザー ID。{@code SecurityUtils#getCurrentUserId} が Long にパースするため数字文字列。 */
    private static final String USER_ID = "90211";

    @Autowired
    private MockMvc mockMvc;

    @PersistenceContext
    private EntityManager em;

    /** Valkey の代役（zone+key ごとの固定ウィンドウカウンタ）。 */
    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();

    private Long teamId;

    @BeforeEach
    void setUp() {
        counters.clear();
        // StringRedisTemplate（基底クラスの @MockitoBean）に「INCR して現在値を返す」挙動を持たせる。
        // 素の Mock は null を返し ValkeyRateLimiter が fail-open するため 429 に到達できない。
        willAnswer(invocation -> {
            List<?> keys = invocation.getArgument(1);
            String redisKey = String.valueOf(keys.get(0));
            return counters.computeIfAbsent(redisKey, k -> new AtomicLong()).incrementAndGet();
        }).given(redisTemplate).execute(any(RedisScript.class), anyList(), any());

        teamId = insertTeam("RATELIMIT チーム");
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. 対象パスに効く（超過で 429 + Retry-After）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. 対象パスに効き、超過で実 HTTP 429 + Retry-After が返る")
    class TargetPaths {

        @Test
        @WithMockUser(username = USER_ID)
        @DisplayName("read-all: 上限まで 200、超過で 429 + Retry-After + X-RateLimit-*")
        void readAllは上限超過で429() throws Exception {
            for (int i = 0; i < AnnouncementReadRateLimitFilter.READ_ALL_LIMIT; i++) {
                MvcResult ok = mockMvc.perform(
                        post("/api/v1/teams/{teamId}/announcements/read-all", teamId)).andReturn();
                assertThat(ok.getResponse().getStatus())
                        .as("read-all #%d は閾値内なので通ること（非回帰）", i + 1)
                        .isEqualTo(HttpStatus.OK.value());
            }

            MvcResult overLimit = mockMvc.perform(
                    post("/api/v1/teams/{teamId}/announcements/read-all", teamId)).andReturn();

            assertThat(overLimit.getResponse().getStatus())
                    .as("フィルタが実 HTTP 経路に載っていれば 6 回目は 429 になる")
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
            // フィルタ単体テストではなく実 HTTP 応答として Retry-After が載ること
            assertThat(overLimit.getResponse().getHeader("Retry-After"))
                    .as("429 応答には Retry-After が必須（docs/security/06 §4.3）")
                    .isNotNull()
                    .matches("\\d+");
            assertThat(overLimit.getResponse().getHeader("X-RateLimit-Limit"))
                    .isEqualTo(String.valueOf(AnnouncementReadRateLimitFilter.READ_ALL_LIMIT));
            assertThat(overLimit.getResponse().getHeader("X-RateLimit-Remaining")).isEqualTo("0");
            assertThat(overLimit.getResponse().getHeader("X-RateLimit-Reset")).isNotNull();
            assertThat(overLimit.getResponse().getContentAsString()).contains("Too many requests");
        }

        @Test
        @WithMockUser(username = USER_ID)
        @DisplayName("通常応答（200）にも X-RateLimit-* が載る（フィルタ登録の裏取り）")
        void 成功応答にもレートリミットヘッダが載る() throws Exception {
            MvcResult result = mockMvc.perform(
                    post("/api/v1/teams/{teamId}/announcements/read-all", teamId)).andReturn();

            assertThat(result.getResponse().getStatus()).isEqualTo(HttpStatus.OK.value());
            assertThat(result.getResponse().getHeader("X-RateLimit-Limit"))
                    .as("フィルタがチェーンに登録されていなければヘッダーは 1 つも付かない")
                    .isEqualTo(String.valueOf(AnnouncementReadRateLimitFilter.READ_ALL_LIMIT));
            assertThat(result.getResponse().getHeader("X-RateLimit-Remaining"))
                    .isEqualTo(String.valueOf(AnnouncementReadRateLimitFilter.READ_ALL_LIMIT - 1));
        }

        @Test
        @WithMockUser(username = USER_ID)
        @DisplayName("単件既読も対象（別 zone なので read-all の枠を食わない）")
        void 単件既読も対象で別zone() throws Exception {
            // read-all の枠を使い切る
            for (int i = 0; i < AnnouncementReadRateLimitFilter.READ_ALL_LIMIT; i++) {
                mockMvc.perform(post("/api/v1/teams/{teamId}/announcements/read-all", teamId));
            }
            assertThat(mockMvc.perform(post("/api/v1/teams/{teamId}/announcements/read-all", teamId))
                    .andReturn().getResponse().getStatus())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());

            // 単件既読は別 zone。レート的には通る（存在しないお知らせなので業務上は 4xx でよい）
            MvcResult single = mockMvc.perform(
                    post("/api/v1/teams/{teamId}/announcements/{id}/read", teamId, 999_999L)).andReturn();
            assertThat(single.getResponse().getStatus())
                    .as("単件既読が read-all の枠で 429 になってはならない（zone 分離）")
                    .isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
            assertThat(single.getResponse().getHeader("X-RateLimit-Limit"))
                    .as("単件既読側の上限値でヘッダーが載る")
                    .isEqualTo(String.valueOf(AnnouncementReadRateLimitFilter.SINGLE_READ_LIMIT));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. 非対象パスには効かない
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. 非対象パスにはフィルタが効かない")
    class NonTargetPaths {

        @Test
        @WithMockUser(username = USER_ID)
        @DisplayName("お知らせ一覧（GET）には X-RateLimit-* が 1 つも載らない")
        void 一覧取得は対象外() throws Exception {
            MvcResult result = mockMvc.perform(
                    get("/api/v1/teams/{teamId}/announcements", teamId)).andReturn();

            assertThat(result.getResponse().getHeader("X-RateLimit-Limit"))
                    .as("一覧取得まで既読 EP の枠を消費してはならない")
                    .isNull();
            assertThat(result.getResponse().getHeader("X-RateLimit-Remaining")).isNull();
            assertThat(result.getResponse().getHeader("Retry-After")).isNull();
        }

        @Test
        @WithMockUser(username = USER_ID)
        @DisplayName("一覧 GET を上限超の回数叩いても既読 EP の枠は減らない")
        void 一覧取得は既読の枠を消費しない() throws Exception {
            for (int i = 0; i < AnnouncementReadRateLimitFilter.READ_ALL_LIMIT + 3; i++) {
                mockMvc.perform(get("/api/v1/teams/{teamId}/announcements", teamId));
            }

            MvcResult readAll = mockMvc.perform(
                    post("/api/v1/teams/{teamId}/announcements/read-all", teamId)).andReturn();
            assertThat(readAll.getResponse().getStatus()).isEqualTo(HttpStatus.OK.value());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private Long insertTeam(String name) {
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, 'PUBLIC', 1, 0, 0, "
                                + "CONCAT('rl-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
