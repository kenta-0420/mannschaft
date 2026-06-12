package com.mannschaft.app.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.advertising.campaign.filter.AdPublicEndpointRateLimitFilter;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.ProxyInputContextFilter;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.publicview.filter.PublicApiRateLimitFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Security Hardening Phase 1: {@link SecurityConfig} の deny-by-default 認可方針テスト。
 *
 * <p>設計書: docs/security/01_authorization_baseline.md §6.2。以下を担保する:</p>
 * <ul>
 *   <li>外部 webhook 4 系統（stripe / ses / line / incoming）が <b>未認証で 401/403 にならない</b>
 *       （permitAll が効いている。本テストには対象 Controller を載せないため到達結果は 404 等になるが、
 *       それは「認証で弾かれていない」証左であり permitAll の検証として十分）</li>
 *   <li>WebSocket ハンドシェイク（/ws/**）が <b>未認証で 401/403 にならない</b></li>
 *   <li>許可リストに無い一般 API（例 /api/v1/users/me）が <b>未認証で 401/403</b>
 *       （deny-by-default の反転が効いている）</li>
 *   <li>/api/v1/system-admin/** が一般ユーザー権限で <b>403</b></li>
 * </ul>
 *
 * <p><b>テスト境界</b>: {@link ActuatorEndpointSecurityTest} と同じく Spring Security の
 * フィルタチェーン挙動のみを検証する。業務 Controller / DB / Redis を載せないため、
 * permitAll で通過したリクエストは存在しないハンドラに当たり 404 になる。
 * 「401/403 でないこと」を permitAll 成立の判定基準とする（認証起因の拒否のみを問題視する）。</p>
 */
@SpringBootTest(
        classes = SecurityConfigAuthorizationTest.MinimalSecurityTestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("認可 deny-by-default 方針 (Security Hardening Phase 1)")
class SecurityConfigAuthorizationTest {

    /**
     * {@link ActuatorEndpointSecurityTest.MinimalActuatorTestConfig} と同方針の最小コンテキスト。
     * MannschaftApplication の @ComponentScan を経由せず SecurityConfig と Spring Boot 自動構成のみを
     * 取り込み、Datasource / JPA / Flyway / Redis 系を除外して Docker 不要にする。
     * SecurityConfig が依存する 4 フィルタは本物インスタンスを供給し内部依存をモック化する。
     */
    @Configuration
    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class,
            JpaRepositoriesAutoConfiguration.class,
            FlywayAutoConfiguration.class,
            RedisAutoConfiguration.class,
            RedisRepositoriesAutoConfiguration.class
    })
    @Import(SecurityConfig.class)
    static class MinimalSecurityTestConfig {

        @Bean
        JwtAuthenticationFilter jwtAuthenticationFilter() {
            return new JwtAuthenticationFilter(mock(AuthTokenService.class));
        }

        @Bean
        ProxyInputContextFilter proxyInputContextFilter() {
            return new ProxyInputContextFilter(
                    mock(ProxyInputConsentRepository.class),
                    mock(ProxyInputContext.class),
                    mock(ObjectMapper.class),
                    mockGuardianshipSwitchServiceProvider());
        }

        /** F08.9 P3c: フィルタの ObjectProvider 遅延解決依存（後見切替経路を踏まないためモックで足りる）。 */
        @SuppressWarnings("unchecked")
        private static org.springframework.beans.factory.ObjectProvider<com.mannschaft.app.auth.guardianship.GuardianshipSwitchService>
                mockGuardianshipSwitchServiceProvider() {
            return mock(org.springframework.beans.factory.ObjectProvider.class);
        }


        @Bean
        @SuppressWarnings("unchecked")
        PublicApiRateLimitFilter publicApiRateLimitFilter() {
            // Valkey 化第一陣: ValkeyRateLimiter は空 Provider（getIfAvailable()=null）→ フィルタは素通し
            org.springframework.beans.factory.ObjectProvider<com.mannschaft.app.common.ratelimit.ValkeyRateLimiter> rateLimiterProvider =
                    mock(org.springframework.beans.factory.ObjectProvider.class);
            org.springframework.beans.factory.ObjectProvider<com.mannschaft.app.auth.service.AuditLogService> auditProvider =
                    mock(org.springframework.beans.factory.ObjectProvider.class);
            org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meterProvider =
                    mock(org.springframework.beans.factory.ObjectProvider.class);
            return new PublicApiRateLimitFilter(rateLimiterProvider, auditProvider, meterProvider);
        }

        @Bean
        AdPublicEndpointRateLimitFilter adPublicEndpointRateLimitFilter() {
            return new AdPublicEndpointRateLimitFilter();
        }

        /**
         * F03.10 第三陣: SecurityConfig が依存する ScheduleDelegationRateLimitFilter の
         * 本物インスタンス。本テストは対象パス（POST /api/v1/schedules/{id}/delegations）を
         * 叩かないため、何もせず chain.doFilter に通す挙動になる。
         */
        @Bean
        com.mannschaft.app.schedule.ScheduleDelegationRateLimitFilter scheduleDelegationRateLimitFilter() {
            return new com.mannschaft.app.schedule.ScheduleDelegationRateLimitFilter();
        }

        /**
         * F03.10 第三陣: SecurityConfig が依存する EventDelegationRateLimitFilter の
         * 本物インスタンス。本テストは対象パス（POST /api/v1/events/{id}/delegations）を
         * 叩かないため、何もせず chain.doFilter に通す挙動になる。
         */
        @Bean
        com.mannschaft.app.event.EventDelegationRateLimitFilter eventDelegationRateLimitFilter() {
            return new com.mannschaft.app.event.EventDelegationRateLimitFilter();
        }

        /**
         * F22.1: SecurityConfig が依存する DashboardScopeTabRateLimitFilter の
         * 本物インスタンス。本テストは対象パス（PUT /api/v1/dashboard/scope-tabs/order）を
         * 叩かないため、何もせず chain.doFilter に通す挙動になる。
         */
        @Bean
        com.mannschaft.app.dashboard.DashboardScopeTabRateLimitFilter dashboardScopeTabRateLimitFilter() {
            return new com.mannschaft.app.dashboard.DashboardScopeTabRateLimitFilter();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    @SuppressWarnings("unused")
    private ProxyInputConsentRepository proxyInputConsentRepository;

    /**
     * 認証起因の拒否（401/403）でないことを確認する。permitAll が効いている証左。
     */
    private void expectNotAuthRejected(ResultActions actions, String label) throws Exception {
        actions.andExpect(result -> {
            int status = result.getResponse().getStatus();
            if (status == 401 || status == 403) {
                throw new AssertionError(
                        label + " は permitAll のはずだが認証で弾かれた (status=" + status + ")");
            }
        });
    }

    /**
     * 未認証で 401 または 403 になることを確認する。deny-by-default が効いている証左。
     */
    private void expectAuthRejected(ResultActions actions, String label) throws Exception {
        actions.andExpect(result -> {
            int status = result.getResponse().getStatus();
            if (status != 401 && status != 403) {
                throw new AssertionError(
                        label + " は認証必須のはずだが 401/403 にならなかった (status=" + status + ")");
            }
        });
    }

    // ---- webhook 4 系統: 未認証で 401/403 にならない（permitAll） ----

    @Test
    @WithAnonymousUser
    @DisplayName("匿名: POST /api/v1/webhooks/stripe は認証で弾かれない")
    void anonymous_stripe_webhook_not_auth_rejected() throws Exception {
        expectNotAuthRejected(mockMvc.perform(post("/api/v1/webhooks/stripe")),
                "POST /api/v1/webhooks/stripe");
    }

    @Test
    @WithAnonymousUser
    @DisplayName("匿名: POST /api/v1/webhooks/ses は認証で弾かれない")
    void anonymous_ses_webhook_not_auth_rejected() throws Exception {
        expectNotAuthRejected(mockMvc.perform(post("/api/v1/webhooks/ses")),
                "POST /api/v1/webhooks/ses");
    }

    @Test
    @WithAnonymousUser
    @DisplayName("匿名: POST /api/v1/line/webhook/{secret} は認証で弾かれない")
    void anonymous_line_webhook_not_auth_rejected() throws Exception {
        expectNotAuthRejected(mockMvc.perform(post("/api/v1/line/webhook/test-secret")),
                "POST /api/v1/line/webhook/{secret}");
    }

    @Test
    @WithAnonymousUser
    @DisplayName("匿名: POST /incoming/{token} は認証で弾かれない")
    void anonymous_incoming_webhook_not_auth_rejected() throws Exception {
        expectNotAuthRejected(mockMvc.perform(post("/incoming/test-token")),
                "POST /incoming/{token}");
    }

    @Test
    @WithAnonymousUser
    @DisplayName("匿名: GET /ws/info（SockJS ハンドシェイク）は認証で弾かれない")
    void anonymous_ws_handshake_not_auth_rejected() throws Exception {
        expectNotAuthRejected(mockMvc.perform(get("/ws/info")),
                "GET /ws/info");
    }

    // ---- 認証必須エンドポイント: 未認証で 401/403（deny-by-default） ----

    @Test
    @WithAnonymousUser
    @DisplayName("匿名: GET /api/v1/users/me は 401/403（deny-by-default）")
    void anonymous_users_me_is_auth_rejected() throws Exception {
        expectAuthRejected(mockMvc.perform(get("/api/v1/users/me")),
                "GET /api/v1/users/me");
    }

    @Test
    @WithAnonymousUser
    @DisplayName("匿名: 許可リストに無い任意の API は 401/403（deny-by-default）")
    void anonymous_unknown_api_is_auth_rejected() throws Exception {
        expectAuthRejected(mockMvc.perform(get("/api/v1/some/unmapped/endpoint")),
                "GET /api/v1/some/unmapped/endpoint");
    }

    // ---- ロール必須: 一般ユーザーで 403 ----

    @Test
    @WithMockUser(roles = "MEMBER")
    @DisplayName("一般ユーザー: /api/v1/system-admin/** は 403")
    void member_system_admin_is_forbidden() throws Exception {
        mockMvc.perform(get("/api/v1/system-admin/email-outbox"))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    if (status != 403) {
                        throw new AssertionError(
                                "/api/v1/system-admin/** は一般ユーザーで 403 のはずだが status=" + status);
                    }
                });
    }

    /**
     * 認可基盤完全根治 Phase 1（docs/security/03_role_authority_model.md §3.2）の要石検証。
     *
     * <p>ROLE_SYSTEM_ADMIN authority を持つ主体は {@code /api/v1/system-admin/**} で
     * <b>403 にならない</b>こと。Phase 1 で JWT の roles に SYSTEM_ADMIN を載せ、
     * {@link JwtAuthenticationFilter} が ROLE_SYSTEM_ADMIN authority を構築することで、
     * SecurityConfig フィルタ層の {@code hasRole("SYSTEM_ADMIN")} が機能回復する。本テストは
     * その「authority が付いていれば通過する」性質を {@code @WithMockUser(roles="SYSTEM_ADMIN")}
     * で直接検証する（フィルタチェーンのみ・Docker 不要）。</p>
     *
     * <p>対象 Controller は本最小コンテキストに載らないため、認可通過後はハンドラ不在で 404 等になる。
     * 「403 でないこと」を hasRole 通過の判定基準とする（ActuatorEndpointSecurityTest と同方針）。</p>
     */
    @Test
    @WithMockUser(roles = "SYSTEM_ADMIN")
    @DisplayName("SYSTEM_ADMIN: /api/v1/system-admin/** は 403 にならない（hasRole 機能回復）")
    void systemAdmin_system_admin_endpoint_not_forbidden() throws Exception {
        mockMvc.perform(get("/api/v1/system-admin/email-outbox"))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    if (status == 403) {
                        throw new AssertionError(
                                "/api/v1/system-admin/** は SYSTEM_ADMIN 権限で 403 になってはならない "
                                        + "(hasRole が機能していない) status=" + status);
                    }
                });
    }

    // ---- F22.1 R2 手数料パターン管理 (/api/v1/system-admin/fee-policies) ----

    @Test
    @WithAnonymousUser
    @DisplayName("匿名: GET /api/v1/system-admin/fee-policies は 401/403（認証必須）")
    void anonymous_fee_policies_is_auth_rejected() throws Exception {
        expectAuthRejected(mockMvc.perform(get("/api/v1/system-admin/fee-policies")),
                "GET /api/v1/system-admin/fee-policies");
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    @DisplayName("一般ユーザー: /api/v1/system-admin/fee-policies は 403")
    void member_fee_policies_is_forbidden() throws Exception {
        mockMvc.perform(get("/api/v1/system-admin/fee-policies"))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    if (status != 403) {
                        throw new AssertionError(
                                "/api/v1/system-admin/fee-policies は一般ユーザーで 403 のはずだが status=" + status);
                    }
                });
    }

    @Test
    @WithMockUser(roles = "SYSTEM_ADMIN")
    @DisplayName("SYSTEM_ADMIN: /api/v1/system-admin/fee-policies は 403 にならない")
    void systemAdmin_fee_policies_not_forbidden() throws Exception {
        mockMvc.perform(get("/api/v1/system-admin/fee-policies"))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    if (status == 403) {
                        throw new AssertionError(
                                "/api/v1/system-admin/fee-policies は SYSTEM_ADMIN 権限で 403 になってはならない status=" + status);
                    }
                });
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    @DisplayName("一般ユーザー: /api/v1/system-admin/fee-policy-assignments は 403")
    void member_fee_policy_assignments_is_forbidden() throws Exception {
        mockMvc.perform(get("/api/v1/system-admin/fee-policy-assignments"))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    if (status != 403) {
                        throw new AssertionError(
                                "/api/v1/system-admin/fee-policy-assignments は一般ユーザーで 403 のはずだが status=" + status);
                    }
                });
    }

    // ---- 公開エンドポイント（既存許可リスト）が反転後も維持されること ----

    @Test
    @WithAnonymousUser
    @DisplayName("匿名: POST /api/v1/auth/login は認証で弾かれない")
    void anonymous_login_not_auth_rejected() throws Exception {
        expectNotAuthRejected(mockMvc.perform(post("/api/v1/auth/login")),
                "POST /api/v1/auth/login");
    }

    // ---- F08.7 項目① 公開大会参照 API（PublicTournamentController）が permitAll であること ----

    @Test
    @WithAnonymousUser
    @DisplayName("匿名: GET /api/v1/public/organizations/{id}/tournaments は認証で弾かれない")
    void anonymous_public_tournament_list_not_auth_rejected() throws Exception {
        expectNotAuthRejected(
                mockMvc.perform(get("/api/v1/public/organizations/1/tournaments")),
                "GET /api/v1/public/organizations/{id}/tournaments");
    }

    @Test
    @WithAnonymousUser
    @DisplayName("匿名: GET /api/v1/public/organizations/{id}/tournaments/{tId} は認証で弾かれない")
    void anonymous_public_tournament_detail_not_auth_rejected() throws Exception {
        expectNotAuthRejected(
                mockMvc.perform(get("/api/v1/public/organizations/1/tournaments/100")),
                "GET /api/v1/public/organizations/{id}/tournaments/{tId}");
    }

    @Test
    @WithAnonymousUser
    @DisplayName("匿名: GET 公開順位表は認証で弾かれない")
    void anonymous_public_standings_not_auth_rejected() throws Exception {
        expectNotAuthRejected(
                mockMvc.perform(get(
                        "/api/v1/public/organizations/1/tournaments/100/divisions/2/standings")),
                "GET 公開順位表");
    }

    @Test
    @WithAnonymousUser
    @DisplayName("匿名: GET 公開対戦マトリクスは認証で弾かれない")
    void anonymous_public_matrix_not_auth_rejected() throws Exception {
        expectNotAuthRejected(
                mockMvc.perform(get(
                        "/api/v1/public/organizations/1/tournaments/100/divisions/2/matrix")),
                "GET 公開対戦マトリクス");
    }

    @Test
    @WithAnonymousUser
    @DisplayName("匿名: GET 公開個人ランキングは認証で弾かれない")
    void anonymous_public_rankings_not_auth_rejected() throws Exception {
        expectNotAuthRejected(
                mockMvc.perform(get(
                        "/api/v1/public/organizations/1/tournaments/100/rankings/goals")),
                "GET 公開個人ランキング");
    }

    @Test
    @WithAnonymousUser
    @DisplayName("匿名: GET 公開トーナメント表(bracket)は認証で弾かれない")
    void anonymous_public_bracket_not_auth_rejected() throws Exception {
        expectNotAuthRejected(
                mockMvc.perform(get(
                        "/api/v1/public/organizations/1/tournaments/100/bracket")),
                "GET 公開トーナメント表(bracket)");
    }

    // ---- F08.7 項目① 埋め込みウィジェット（EmbedController）が permitAll であること ----

    @Test
    @WithAnonymousUser
    @DisplayName("匿名: GET 埋め込み順位表は認証で弾かれない")
    void anonymous_embed_standings_not_auth_rejected() throws Exception {
        expectNotAuthRejected(
                mockMvc.perform(get(
                        "/api/v1/embed/organizations/1/tournaments/100/standings/2")),
                "GET 埋め込み順位表");
    }

    @Test
    @WithAnonymousUser
    @DisplayName("匿名: GET 埋め込みトーナメント表は認証で弾かれない")
    void anonymous_embed_bracket_not_auth_rejected() throws Exception {
        expectNotAuthRejected(
                mockMvc.perform(get(
                        "/api/v1/embed/organizations/1/tournaments/100/bracket")),
                "GET 埋め込みトーナメント表");
    }

    @Test
    @WithAnonymousUser
    @DisplayName("匿名: GET 埋め込み個人ランキングは認証で弾かれない")
    void anonymous_embed_rankings_not_auth_rejected() throws Exception {
        expectNotAuthRejected(
                mockMvc.perform(get(
                        "/api/v1/embed/organizations/1/tournaments/100/rankings/goals")),
                "GET 埋め込み個人ランキング");
    }

    // ---- 漏洩面の精査: 書込/非public 系は permitAll で開いていないこと ----

    @Test
    @WithAnonymousUser
    @DisplayName("匿名: POST 公開大会再計算系パスは認証必須（書込は permitAll しない）")
    void anonymous_public_tournament_post_is_auth_rejected() throws Exception {
        // 公開 GET と同じ前置詞でも POST は permitAll の HttpMethod.GET に含まれないため
        // deny-by-default で 401/403 になること（書込面を開いていない証左）。
        expectAuthRejected(
                mockMvc.perform(post(
                        "/api/v1/public/organizations/1/tournaments/100/bracket")),
                "POST /api/v1/public/organizations/{id}/tournaments/{tId}/bracket");
    }

    @Test
    @WithAnonymousUser
    @DisplayName("匿名: GET 認証必須(非public) 大会順位表は 401/403（公開閲覧は /public/ 経由限定）")
    void anonymous_authenticated_standings_is_auth_rejected() throws Exception {
        // StandingsController の /api/v1/organizations/... 系は permitAll しておらず、
        // 公開閲覧は /public/ 経由に限定する設計。未認証は deny-by-default で弾かれること。
        expectAuthRejected(
                mockMvc.perform(get(
                        "/api/v1/organizations/1/tournaments/100/divisions/2/standings")),
                "GET /api/v1/organizations/{id}/tournaments/{tId}/divisions/{divId}/standings");
    }

    @Test
    @WithAnonymousUser
    @DisplayName("匿名: GET 認証必須(非public) 個人ランキングは 401/403")
    void anonymous_authenticated_rankings_is_auth_rejected() throws Exception {
        expectAuthRejected(
                mockMvc.perform(get(
                        "/api/v1/organizations/1/tournaments/100/rankings/goals")),
                "GET /api/v1/organizations/{id}/tournaments/{tId}/rankings/{statKey}");
    }

    @Test
    @WithAnonymousUser
    @DisplayName("匿名: GET 公開大会パスの 2 階層余分は permitAll に含まれない（* は 1 階層厳格）")
    void anonymous_public_tournament_extra_segment_is_auth_rejected() throws Exception {
        // `*` は 1 階層厳格のため、想定外の深いパスは permitAll にマッチせず deny-by-default。
        expectAuthRejected(
                mockMvc.perform(get(
                        "/api/v1/public/organizations/1/tournaments/100/divisions/2/standings/extra")),
                "GET 公開順位表 + 余分階層");
    }
}
