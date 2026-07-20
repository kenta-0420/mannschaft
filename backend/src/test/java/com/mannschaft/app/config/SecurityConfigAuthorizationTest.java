package com.mannschaft.app.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.admin.filter.AdminImpersonationFilter;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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

        /**
         * F10.1: AdminImpersonationFilter は ObjectMapper のみに依存するため、
         * モック ObjectMapper を渡して本物インスタンスを供給する。
         * ヘッダーなしリクエストは即 chain.doFilter するため副作用なし。
         */
        @Bean
        AdminImpersonationFilter adminImpersonationFilter() {
            return new AdminImpersonationFilter(mock(ObjectMapper.class));
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

        /**
         * Valkey 化第二陣B: ValkeyRateLimiter は空 Provider（getIfAvailable()=null）→ フィルタは素通し。
         * IP のみキー / addFilterBefore 登録方式は不変。
         */
        @Bean
        @SuppressWarnings("unchecked")
        AdPublicEndpointRateLimitFilter adPublicEndpointRateLimitFilter() {
            org.springframework.beans.factory.ObjectProvider<com.mannschaft.app.common.ratelimit.ValkeyRateLimiter> rateLimiterProvider =
                    mock(org.springframework.beans.factory.ObjectProvider.class);
            return new AdPublicEndpointRateLimitFilter(rateLimiterProvider);
        }
        /**
         * F10.8: SecurityConfig が要求する {@link com.mannschaft.app.analytics.filter.PageViewBeaconRateLimitFilter} の
         * 本物インスタンス（判定に使う ValkeyRateLimiter は mock 供給）。既存 AdPublicEndpointRateLimitFilter と同型。
         */
        @Bean
        @SuppressWarnings("unchecked")
        com.mannschaft.app.analytics.filter.PageViewBeaconRateLimitFilter pageViewBeaconRateLimitFilter() {
            org.springframework.beans.factory.ObjectProvider<com.mannschaft.app.common.ratelimit.ValkeyRateLimiter> rateLimiterProvider =
                    org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
            return new com.mannschaft.app.analytics.filter.PageViewBeaconRateLimitFilter(rateLimiterProvider);
        }


        /**
         * F03.10 第三陣 / Valkey 化第二陣B: SecurityConfig が依存する ScheduleDelegationRateLimitFilter の
         * 本物インスタンス。ValkeyRateLimiter は空 Provider（素通し）。
         * 本テストは対象パス（POST /api/v1/schedules/{id}/delegations）を
         * 叩かないため、何もせず chain.doFilter に通す挙動になる。
         */
        @Bean
        @SuppressWarnings("unchecked")
        com.mannschaft.app.schedule.ScheduleDelegationRateLimitFilter scheduleDelegationRateLimitFilter() {
            org.springframework.beans.factory.ObjectProvider<com.mannschaft.app.common.ratelimit.ValkeyRateLimiter> rateLimiterProvider =
                    mock(org.springframework.beans.factory.ObjectProvider.class);
            return new com.mannschaft.app.schedule.ScheduleDelegationRateLimitFilter(rateLimiterProvider);
        }

        /**
         * F03.10 第三陣 / Valkey 化第二陣B: SecurityConfig が依存する EventDelegationRateLimitFilter の
         * 本物インスタンス。ValkeyRateLimiter は空 Provider（素通し）。
         * 本テストは対象パス（POST /api/v1/events/{id}/delegations）を
         * 叩かないため、何もせず chain.doFilter に通す挙動になる。
         */
        @Bean
        @SuppressWarnings("unchecked")
        com.mannschaft.app.event.EventDelegationRateLimitFilter eventDelegationRateLimitFilter() {
            org.springframework.beans.factory.ObjectProvider<com.mannschaft.app.common.ratelimit.ValkeyRateLimiter> rateLimiterProvider =
                    mock(org.springframework.beans.factory.ObjectProvider.class);
            return new com.mannschaft.app.event.EventDelegationRateLimitFilter(rateLimiterProvider);
        }

        /**
         * F22.1 / Valkey 化第二陣B: SecurityConfig が依存する DashboardScopeTabRateLimitFilter の
         * 本物インスタンス。ValkeyRateLimiter は空 Provider（素通し）。
         * 本テストは対象パス（PUT /api/v1/dashboard/scope-tabs/order）を
         * 叩かないため、何もせず chain.doFilter に通す挙動になる。
         */
        @Bean
        @SuppressWarnings("unchecked")
        com.mannschaft.app.dashboard.DashboardScopeTabRateLimitFilter dashboardScopeTabRateLimitFilter() {
            org.springframework.beans.factory.ObjectProvider<com.mannschaft.app.common.ratelimit.ValkeyRateLimiter> rateLimiterProvider =
                    mock(org.springframework.beans.factory.ObjectProvider.class);
            return new com.mannschaft.app.dashboard.DashboardScopeTabRateLimitFilter(rateLimiterProvider);
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

    // ---- webhook 3 系統: 未認証で 401/403 にならない（permitAll） ----
    // ※ SES バウンス/苦情通知は F09.6 Phase 8a で SQS リスナー方式へ移行し HTTP 受け口を廃止した。
    //    /api/v1/webhooks/ses は permitAll から外れ deny-by-default 対象に戻る（下記の専用テストで検証）。

    @Test
    @WithAnonymousUser
    @DisplayName("匿名: POST /api/v1/webhooks/stripe は認証で弾かれない")
    void anonymous_stripe_webhook_not_auth_rejected() throws Exception {
        expectNotAuthRejected(mockMvc.perform(post("/api/v1/webhooks/stripe")),
                "POST /api/v1/webhooks/stripe");
    }

    @Test
    @WithAnonymousUser
    @DisplayName("匿名: POST /api/v1/webhooks/ses は廃止済みで permitAll されない（SQS 移行・F09.6 Phase 8a）")
    void anonymous_ses_webhook_no_longer_permit_all() throws Exception {
        // SQS 方式へ移行し HTTP 受け口を撤去したため、deny-by-default で 401/403 に戻ること。
        expectAuthRejected(mockMvc.perform(post("/api/v1/webhooks/ses")),
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

    // ---- 早馬: ランディングページ公開統計 API（GET /api/v1/public/stats）が permitAll であること ----
    // PublicStatsController は「認証不要エンドポイント」と明記されているが、SecurityConfig の
    // permitAll 一覧に登録漏れがあり未認証 401 になっていた（未ログイン訪問者がトップページ / で
    // 401 → useApi.ts の 401 ハンドラにより /login へ強制遷移させられる致命的バグ）。

    @Test
    @WithAnonymousUser
    @DisplayName("匿名: GET /api/v1/public/stats は認証で弾かれない（ランディングページ公開統計）")
    void anonymous_public_stats_not_auth_rejected() throws Exception {
        expectNotAuthRejected(mockMvc.perform(get("/api/v1/public/stats")),
                "GET /api/v1/public/stats");
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

    // ============================================================================
    // 認可根治戦役 束A（Wave 0）: /api/v1/admin/** の SYSTEM_ADMIN 予約
    //   docs/security/01 §4。SecurityConfig の requestMatcher 登録漏れにより
    //   stripe/reports/moderation/warning-re-reviews/users/onboarding-presets 系が
    //   認証済みであれば誰でも到達できていた（deny-by-default は「未認証」しか弾かない）。
    //   6 系統を hasRole("SYSTEM_ADMIN") に格上げし、非 SYSTEM_ADMIN は 403 とする。
    // ============================================================================

    /** 認可通過（403 でない）を確認する共通ヘルパ。ハンドラ不在で 404 等になるのは想定内。 */
    private void expectNotForbidden(ResultActions actions, String label) throws Exception {
        actions.andExpect(result -> {
            int status = result.getResponse().getStatus();
            if (status == 403) {
                throw new AssertionError(label + " は認可通過のはずだが 403 になった");
            }
        });
    }

    /** ロール不足で 403 になることを確認する共通ヘルパ。 */
    private void expectForbidden(ResultActions actions, String label) throws Exception {
        actions.andExpect(result -> {
            int status = result.getResponse().getStatus();
            if (status != 403) {
                throw new AssertionError(label + " は SYSTEM_ADMIN 限定のはずだが 403 にならなかった (status=" + status + ")");
            }
        });
    }

    // ---- AC-0-1: 非 SYSTEM_ADMIN（MEMBER）が admin 系を叩くと 403 ----

    @Test
    @WithMockUser(roles = "MEMBER")
    @DisplayName("AC-0-1 一般ユーザー: POST /api/v1/admin/stripe/reconcile/{id} は 403")
    void member_admin_stripe_is_forbidden() throws Exception {
        expectForbidden(mockMvc.perform(post("/api/v1/admin/stripe/reconcile/1")),
                "POST /api/v1/admin/stripe/reconcile/{id}");
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    @DisplayName("AC-0-1 一般ユーザー: PATCH /api/v1/admin/reports/{id}/review は 403")
    void member_admin_reports_is_forbidden() throws Exception {
        expectForbidden(mockMvc.perform(patch("/api/v1/admin/reports/1/review")),
                "PATCH /api/v1/admin/reports/{id}/review");
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    @DisplayName("AC-0-1 一般ユーザー: GET /api/v1/admin/moderation/templates は 403")
    void member_admin_moderation_is_forbidden() throws Exception {
        expectForbidden(mockMvc.perform(get("/api/v1/admin/moderation/templates")),
                "GET /api/v1/admin/moderation/templates");
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    @DisplayName("AC-0-1 一般ユーザー: GET /api/v1/admin/warning-re-reviews は 403")
    void member_admin_warning_re_reviews_is_forbidden() throws Exception {
        expectForbidden(mockMvc.perform(get("/api/v1/admin/warning-re-reviews")),
                "GET /api/v1/admin/warning-re-reviews");
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    @DisplayName("AC-0-1 一般ユーザー: GET /api/v1/admin/users/{id}/violations は 403")
    void member_admin_users_is_forbidden() throws Exception {
        expectForbidden(mockMvc.perform(get("/api/v1/admin/users/1/violations")),
                "GET /api/v1/admin/users/{id}/violations");
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    @DisplayName("AC-0-1 一般ユーザー: GET /api/v1/admin/onboarding/presets は 403")
    void member_admin_onboarding_presets_is_forbidden() throws Exception {
        expectForbidden(mockMvc.perform(get("/api/v1/admin/onboarding/presets")),
                "GET /api/v1/admin/onboarding/presets");
    }

    // 認可根治戦役 Wave3 トランシェB4: F05.7 forms の SYSTEM_ADMIN 専用プリセット管理も
    // Wave0 と同じ requestMatcher 登録漏れだったため追加格上げ（SecurityConfig 参照）。
    @Test
    @WithMockUser(roles = "MEMBER")
    @DisplayName("AC-0-1 一般ユーザー: GET /api/v1/admin/form-presets は 403")
    void member_admin_form_presets_is_forbidden() throws Exception {
        expectForbidden(mockMvc.perform(get("/api/v1/admin/form-presets")),
                "GET /api/v1/admin/form-presets");
    }

    // ---- AC-0-1: SYSTEM_ADMIN は 403 にならない（過剰ロックでない証左） ----

    @Test
    @WithMockUser(roles = "SYSTEM_ADMIN")
    @DisplayName("AC-0-1 SYSTEM_ADMIN: admin 系 7 パスは 403 にならない")
    void systemAdmin_admin_paths_not_forbidden() throws Exception {
        expectNotForbidden(mockMvc.perform(post("/api/v1/admin/stripe/reconcile/1")),
                "POST /api/v1/admin/stripe/reconcile/{id}");
        expectNotForbidden(mockMvc.perform(patch("/api/v1/admin/reports/1/review")),
                "PATCH /api/v1/admin/reports/{id}/review");
        expectNotForbidden(mockMvc.perform(get("/api/v1/admin/moderation/templates")),
                "GET /api/v1/admin/moderation/templates");
        expectNotForbidden(mockMvc.perform(get("/api/v1/admin/warning-re-reviews")),
                "GET /api/v1/admin/warning-re-reviews");
        expectNotForbidden(mockMvc.perform(get("/api/v1/admin/users/1/violations")),
                "GET /api/v1/admin/users/{id}/violations");
        expectNotForbidden(mockMvc.perform(get("/api/v1/admin/onboarding/presets")),
                "GET /api/v1/admin/onboarding/presets");
        expectNotForbidden(mockMvc.perform(get("/api/v1/admin/form-presets")),
                "GET /api/v1/admin/form-presets");
    }

    // ---- AC-0-2（回帰防止）: per-scope admin 配下は一律 SYSTEM_ADMIN 化しない ----
    // dashboard / permission-groups / business-alerts はスコープ内 ADMIN が使うため
    // フィルタ層では authenticated() を維持し、認可は Controller/Service 層（Wave1）で行う。
    // 認証済み MEMBER がフィルタ層で 403 にならない（＝一律 SYSTEM_ADMIN 化していない）ことを担保する。

    @Test
    @WithMockUser(roles = "MEMBER")
    @DisplayName("AC-0-2 認証ユーザー: GET /api/v1/admin/dashboard はフィルタ層で 403 にならない")
    void member_admin_dashboard_not_forbidden_at_filter() throws Exception {
        expectNotForbidden(mockMvc.perform(get("/api/v1/admin/dashboard?scopeType=TEAM&scopeId=1")),
                "GET /api/v1/admin/dashboard");
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    @DisplayName("AC-0-2 認証ユーザー: GET /api/v1/admin/permission-groups はフィルタ層で 403 にならない")
    void member_admin_permission_groups_not_forbidden_at_filter() throws Exception {
        expectNotForbidden(mockMvc.perform(get("/api/v1/admin/permission-groups?scopeType=TEAM&scopeId=1")),
                "GET /api/v1/admin/permission-groups");
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    @DisplayName("AC-0-2 認証ユーザー: GET /api/v1/admin/business-alerts はフィルタ層で 403 にならない")
    void member_admin_business_alerts_not_forbidden_at_filter() throws Exception {
        expectNotForbidden(mockMvc.perform(get("/api/v1/admin/business-alerts?scopeType=TEAM&scopeId=1")),
                "GET /api/v1/admin/business-alerts");
    }

    // ---- AC-0-3（part1）: facility / ticket / my-tickets は未認証で 401/403（deny-by-default） ----

    @Test
    @WithAnonymousUser
    @DisplayName("AC-0-3 匿名: GET チーム施設予約は 401/403")
    void anonymous_facility_booking_is_auth_rejected() throws Exception {
        expectAuthRejected(mockMvc.perform(get("/api/v1/teams/1/facilities/bookings")),
                "GET /api/v1/teams/{teamId}/facilities/bookings");
    }

    @Test
    @WithAnonymousUser
    @DisplayName("AC-0-3 匿名: GET 回数券管理は 401/403")
    void anonymous_ticket_books_is_auth_rejected() throws Exception {
        expectAuthRejected(mockMvc.perform(get("/api/v1/teams/1/ticket-books")),
                "GET /api/v1/teams/{teamId}/ticket-books");
    }

    @Test
    @WithAnonymousUser
    @DisplayName("AC-0-3 匿名: GET マイチケットは 401/403")
    void anonymous_my_tickets_is_auth_rejected() throws Exception {
        expectAuthRejected(mockMvc.perform(get("/api/v1/teams/1/my-tickets")),
                "GET /api/v1/teams/{teamId}/my-tickets");
    }

    // ============================================================================
    // 認可根治戦役 Wave5 追込: PR #2373 で格上げした 3 系統の SecurityConfig 側検証
    //
    //   SecurityConfig.java:390-392 で以下を hasRole("SYSTEM_ADMIN") に格上げした:
    //     - /api/v1/admin/seals/**            全ユーザーの電子印鑑の一覧・一括再生成
    //     - /api/v1/admin/action-templates/** 全体共通のモデレーション用アクションテンプレート CRUD
    //     - /api/v1/admin/notifications/**    全テナント横断の通知配信
    //
    //   #2373 の契約 IT は @AutoConfigureMockMvc(addFilters = false) で動くため
    //   フィルタチェーンを通らず、二重防御の片翼（SecurityConfig 側）が未検証だった。
    //   本クラスは addFilters 既定（= true）でフィルタチェーンを実際に通すため、
    //   格上げが「フィルタ層で効いていること」をここで担保する。
    //
    //   判定基準は本クラス既定の方針を踏襲する（対象 Controller を最小コンテキストに
    //   載せないため、認可通過後はハンドラ不在で 404 等になる。よって
    //   「非 SYSTEM_ADMIN は 403」「SYSTEM_ADMIN は 403 でない」を基準とする）。
    // ============================================================================

    // ---- 非 SYSTEM_ADMIN（認証済み MEMBER）は 403 ----

    @Test
    @WithMockUser(roles = "MEMBER")
    @DisplayName("Wave5 一般ユーザー: GET /api/v1/admin/seals は 403（#2373 格上げ）")
    void member_admin_seals_is_forbidden() throws Exception {
        expectForbidden(mockMvc.perform(get("/api/v1/admin/seals")),
                "GET /api/v1/admin/seals");
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    @DisplayName("Wave5 一般ユーザー: GET /api/v1/admin/action-templates は 403（#2373 格上げ）")
    void member_admin_action_templates_is_forbidden() throws Exception {
        expectForbidden(mockMvc.perform(get("/api/v1/admin/action-templates")),
                "GET /api/v1/admin/action-templates");
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    @DisplayName("Wave5 一般ユーザー: GET /api/v1/admin/notifications は 403（#2373 格上げ）")
    void member_admin_notifications_is_forbidden() throws Exception {
        expectForbidden(mockMvc.perform(get("/api/v1/admin/notifications")),
                "GET /api/v1/admin/notifications");
    }

    // ---- 書込系も同様に 403（GET だけの格上げになっていない証左） ----

    @Test
    @WithMockUser(roles = "MEMBER")
    @DisplayName("Wave5 一般ユーザー: POST /api/v1/admin/seals/regenerate は 403（書込面も格上げ済み）")
    void member_admin_seals_write_is_forbidden() throws Exception {
        expectForbidden(mockMvc.perform(post("/api/v1/admin/seals/regenerate")),
                "POST /api/v1/admin/seals/regenerate");
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    @DisplayName("Wave5 一般ユーザー: POST /api/v1/admin/action-templates は 403（書込面も格上げ済み）")
    void member_admin_action_templates_write_is_forbidden() throws Exception {
        expectForbidden(mockMvc.perform(post("/api/v1/admin/action-templates")),
                "POST /api/v1/admin/action-templates");
    }

    // ---- SYSTEM_ADMIN は 403 にならない（過剰ロックでない証左＝到達する） ----

    @Test
    @WithMockUser(roles = "SYSTEM_ADMIN")
    @DisplayName("Wave5 SYSTEM_ADMIN: 格上げ 3 系統は 403 にならない（#2373）")
    void systemAdmin_wave5_upgraded_paths_not_forbidden() throws Exception {
        expectNotForbidden(mockMvc.perform(get("/api/v1/admin/seals")),
                "GET /api/v1/admin/seals");
        expectNotForbidden(mockMvc.perform(get("/api/v1/admin/action-templates")),
                "GET /api/v1/admin/action-templates");
        expectNotForbidden(mockMvc.perform(get("/api/v1/admin/notifications")),
                "GET /api/v1/admin/notifications");
        expectNotForbidden(mockMvc.perform(post("/api/v1/admin/seals/regenerate")),
                "POST /api/v1/admin/seals/regenerate");
        expectNotForbidden(mockMvc.perform(post("/api/v1/admin/action-templates")),
                "POST /api/v1/admin/action-templates");
    }

    // ---- 未認証は 401/403（deny-by-default。error.code を持たないため jsonPath は使わない） ----

    @Test
    @WithAnonymousUser
    @DisplayName("Wave5 匿名: 格上げ 3 系統は 401/403（未認証で到達しない）")
    void anonymous_wave5_upgraded_paths_are_auth_rejected() throws Exception {
        expectAuthRejected(mockMvc.perform(get("/api/v1/admin/seals")),
                "GET /api/v1/admin/seals");
        expectAuthRejected(mockMvc.perform(get("/api/v1/admin/action-templates")),
                "GET /api/v1/admin/action-templates");
        expectAuthRejected(mockMvc.perform(get("/api/v1/admin/notifications")),
                "GET /api/v1/admin/notifications");
    }
}
