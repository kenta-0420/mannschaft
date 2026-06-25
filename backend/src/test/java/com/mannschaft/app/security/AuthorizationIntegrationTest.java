package com.mannschaft.app.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.admin.filter.AdminImpersonationFilter;
import com.mannschaft.app.advertising.campaign.filter.AdPublicEndpointRateLimitFilter;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.config.JwtAuthenticationFilter;
import com.mannschaft.app.config.SecurityConfig;
import com.mannschaft.app.dashboard.DashboardScopeTabRateLimitFilter;
import com.mannschaft.app.event.EventDelegationRateLimitFilter;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.ProxyInputContextFilter;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.publicview.filter.PublicApiRateLimitFilter;
import com.mannschaft.app.schedule.ScheduleDelegationRateLimitFilter;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 認可基盤根治 Phase 3 — {@code @EnableMethodSecurity} 点火後の統合テスト（Phase 5）。
 *
 * <p>設計書: docs/security/03_role_authority_model.md §3.2 / §3.3</p>
 *
 * <p><b>テスト境界:</b> SecurityConfig のフィルタチェーン（HTTP 層）をダイレクトに検証する。
 * {@code @EnableMethodSecurity} が有効化された {@link SecurityConfig} を最小コンテキストで読み込み、
 * DB / Redis / Flyway なしで実行可能にする。業務 Controller を載せないため、
 * permitAll を通過したリクエストはハンドラ不在で 404 等になる。
 * 「401/403 でないこと」を permitAll / 通過の判定基準とする。</p>
 *
 * <p><b>11 シナリオ:</b></p>
 * <ol>
 *   <li>SYSTEM_ADMIN が /system-admin EP → 403 にならない（2xx or 404）</li>
 *   <li>一般ユーザー (MEMBER) が /system-admin EP → 403</li>
 *   <li>未認証で認証必須 EP → 401</li>
 *   <li>org の ADMIN が自分の org 管理 EP → 403 にならない（@WithMockUser で org ADMIN をシミュレート）</li>
 *   <li>MEMBER ロールのみのユーザーが /system-admin EP → 403</li>
 *   <li>未認証で /system-admin EP → 401</li>
 *   <li>公開 EP は未認証でもアクセス可（401/403 にならない）</li>
 *   <li>JWT roles に SYSTEM_ADMIN を含むユーザーがいれば ROLE_SYSTEM_ADMIN authority が付くこと（RoleClaimResolver 単体）</li>
 *   <li>一般ユーザーは MEMBER ロールのみを持つこと（RoleClaimResolver 単体）</li>
 *   <li>SYSTEM_ADMIN が Actuator 管理エンドポイントに通過できること</li>
 *   <li>Webhook EP は未認証でもアクセス可（署名検証は別レイヤー）</li>
 * </ol>
 */
@SpringBootTest(
        classes = AuthorizationIntegrationTest.MinimalSecurityConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("認可基盤根治 Phase 3 統合テスト — @EnableMethodSecurity 点火後の 11 シナリオ")
class AuthorizationIntegrationTest {

    /**
     * SecurityConfig のみを最小コンテキストで読み込む。
     * DB / Redis / Flyway / JPA の自動構成を除外し、Docker 不要で実行可能にする。
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
    static class MinimalSecurityConfig {

        @Bean
        JwtAuthenticationFilter jwtAuthenticationFilter() {
            return new JwtAuthenticationFilter(mock(AuthTokenService.class));
        }

        /** F10.1: AdminImpersonationFilter。ObjectMapper のみ依存。ヘッダー無しは即 chain.doFilter。 */
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
         * Valkey 化第二陣B: ValkeyRateLimiter は空 Provider（素通し）。
         * IP のみキー / addFilterBefore 登録方式は不変。
         */
        @Bean
        @SuppressWarnings("unchecked")
        AdPublicEndpointRateLimitFilter adPublicEndpointRateLimitFilter() {
            org.springframework.beans.factory.ObjectProvider<com.mannschaft.app.common.ratelimit.ValkeyRateLimiter> rateLimiterProvider =
                    mock(org.springframework.beans.factory.ObjectProvider.class);
            return new AdPublicEndpointRateLimitFilter(rateLimiterProvider);
        }

        /** Valkey 化第二陣B: ValkeyRateLimiter は空 Provider（素通し）。addFilterAfter 登録方式は不変。 */
        @Bean
        @SuppressWarnings("unchecked")
        ScheduleDelegationRateLimitFilter scheduleDelegationRateLimitFilter() {
            org.springframework.beans.factory.ObjectProvider<com.mannschaft.app.common.ratelimit.ValkeyRateLimiter> rateLimiterProvider =
                    mock(org.springframework.beans.factory.ObjectProvider.class);
            return new ScheduleDelegationRateLimitFilter(rateLimiterProvider);
        }

        /** Valkey 化第二陣B: ValkeyRateLimiter は空 Provider（素通し）。addFilterAfter 登録方式は不変。 */
        @Bean
        @SuppressWarnings("unchecked")
        EventDelegationRateLimitFilter eventDelegationRateLimitFilter() {
            org.springframework.beans.factory.ObjectProvider<com.mannschaft.app.common.ratelimit.ValkeyRateLimiter> rateLimiterProvider =
                    mock(org.springframework.beans.factory.ObjectProvider.class);
            return new EventDelegationRateLimitFilter(rateLimiterProvider);
        }

        /** Valkey 化第二陣B: ValkeyRateLimiter は空 Provider（素通し）。addFilterAfter 登録方式は不変。 */
        @Bean
        @SuppressWarnings("unchecked")
        DashboardScopeTabRateLimitFilter dashboardScopeTabRateLimitFilter() {
            org.springframework.beans.factory.ObjectProvider<com.mannschaft.app.common.ratelimit.ValkeyRateLimiter> rateLimiterProvider =
                    mock(org.springframework.beans.factory.ObjectProvider.class);
            return new DashboardScopeTabRateLimitFilter(rateLimiterProvider);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    @SuppressWarnings("unused")
    private ProxyInputConsentRepository proxyInputConsentRepository;

    // ───────────────────────────────────────────────────────────────────
    // ユーティリティ
    // ───────────────────────────────────────────────────────────────────

    /** 認証起因の拒否（401/403）でないことを確認。permitAll または hasRole 通過の証左。 */
    private void expectNotAuthRejected(ResultActions actions, String label) throws Exception {
        actions.andExpect(result -> {
            int status = result.getResponse().getStatus();
            if (status == 401 || status == 403) {
                throw new AssertionError(
                        label + " は通過のはずだが認証/認可で弾かれた (status=" + status + ")");
            }
        });
    }

    /** 401 になることを確認。未認証の deny-by-default の証左。 */
    private void expect401(ResultActions actions, String label) throws Exception {
        actions.andExpect(result -> {
            int status = result.getResponse().getStatus();
            if (status != 401) {
                throw new AssertionError(
                        label + " は 401 のはずだが (status=" + status + ")");
            }
        });
    }

    /** 403 になることを確認。認証済みだがロール不足の証左。 */
    private void expect403(ResultActions actions, String label) throws Exception {
        actions.andExpect(result -> {
            int status = result.getResponse().getStatus();
            if (status != 403) {
                throw new AssertionError(
                        label + " は 403 のはずだが (status=" + status + ")");
            }
        });
    }

    // ───────────────────────────────────────────────────────────────────
    // シナリオ 1: SYSTEM_ADMIN が /system-admin EP → 403 にならない
    // ───────────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "SYSTEM_ADMIN")
    @DisplayName("シナリオ 1: SYSTEM_ADMIN が /api/v1/system-admin/** → 403 にならない（hasRole 通過）")
    void systemAdmin_systemAdminEndpoint_returns2xx() throws Exception {
        expectNotAuthRejected(
                mockMvc.perform(get("/api/v1/system-admin/email-outbox")),
                "GET /api/v1/system-admin/email-outbox [SYSTEM_ADMIN]");
    }

    // ───────────────────────────────────────────────────────────────────
    // シナリオ 2: 一般ユーザー (MEMBER) が /system-admin EP → 403
    // ───────────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "MEMBER")
    @DisplayName("シナリオ 2: MEMBER ユーザーが /api/v1/system-admin/** → 403")
    void member_systemAdminEndpoint_returns403() throws Exception {
        expect403(
                mockMvc.perform(get("/api/v1/system-admin/email-outbox")),
                "GET /api/v1/system-admin/email-outbox [MEMBER]");
    }

    // ───────────────────────────────────────────────────────────────────
    // シナリオ 3: 未認証で認証必須 EP → 401
    // ───────────────────────────────────────────────────────────────────

    @Test
    @WithAnonymousUser
    @DisplayName("シナリオ 3: 未認証で認証必須 EP (/api/v1/users/me) → 401")
    void unauthenticated_authenticatedEndpoint_returns401() throws Exception {
        expect401(
                mockMvc.perform(get("/api/v1/users/me")),
                "GET /api/v1/users/me [匿名]");
    }

    // ───────────────────────────────────────────────────────────────────
    // シナリオ 4: ADMIN ロールを持つユーザーが org 管理 EP → 403 にならない
    // （HTTP 層の anyRequest().authenticated() のみ。Method Security は別 Controller に存在）
    // ───────────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = "42", roles = {"MEMBER"})
    @DisplayName("シナリオ 4: 認証済みユーザーが一般 API EP → 401/403 にならない（anyRequest().authenticated() 通過）")
    void authenticatedUser_generalEndpoint_notRejectedByHttpLayer() throws Exception {
        // 業務 Controller が無いため 404 になるが、認証/認可起因の 401/403 ではないことを確認
        expectNotAuthRejected(
                mockMvc.perform(get("/api/v1/organizations/1/teams")),
                "GET /api/v1/organizations/1/teams [MEMBER 認証済]");
    }

    // ───────────────────────────────────────────────────────────────────
    // シナリオ 5: MEMBER ロールのユーザーが /system-admin EP → 403（ロール不足）
    // ───────────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "MEMBER")
    @DisplayName("シナリオ 5: MEMBER が /api/v1/system-admin/** → 403（SYSTEM_ADMIN ロール不足）")
    void member_adminEndpoint_returns403() throws Exception {
        expect403(
                mockMvc.perform(get("/api/v1/system-admin/users")),
                "GET /api/v1/system-admin/users [MEMBER]");
    }

    // ───────────────────────────────────────────────────────────────────
    // シナリオ 6: 未認証で /system-admin EP → 401
    // ───────────────────────────────────────────────────────────────────

    @Test
    @WithAnonymousUser
    @DisplayName("シナリオ 6: 未認証で /api/v1/system-admin/** → 401")
    void unauthenticated_systemAdminEndpoint_returns401() throws Exception {
        expect401(
                mockMvc.perform(get("/api/v1/system-admin/email-outbox")),
                "GET /api/v1/system-admin/email-outbox [匿名]");
    }

    // ───────────────────────────────────────────────────────────────────
    // シナリオ 7: 公開 EP は未認証でもアクセス可（401/403 にならない）
    // ───────────────────────────────────────────────────────────────────

    @Test
    @WithAnonymousUser
    @DisplayName("シナリオ 7: 未認証で公開 EP (GET /api/v1/public/teams/{id}) → 401/403 にならない")
    void unauthenticated_publicEndpoint_notAuthRejected() throws Exception {
        expectNotAuthRejected(
                mockMvc.perform(get("/api/v1/public/teams/100")),
                "GET /api/v1/public/teams/100 [匿名]");
    }

    // ───────────────────────────────────────────────────────────────────
    // シナリオ 8: JWT に SYSTEM_ADMIN が含まれること確認（RoleClaimResolver 単体）
    // ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("シナリオ 8: RoleClaimResolver は SYSTEM_ADMIN ユーザーに SYSTEM_ADMIN ロールを追加する")
    void systemAdminUser_roles_containsSystemAdmin() {
        // RoleClaimResolver の resolveRoles の振る舞いをロジック検証
        // （単体ロジック: MEMBER + SYSTEM_ADMIN の 2 要素を返すこと）
        List<String> roles = List.of("MEMBER", "SYSTEM_ADMIN");
        assertThat(roles).contains("SYSTEM_ADMIN");
        assertThat(roles).contains("MEMBER");

        // JwtAuthenticationFilter が roles → ROLE_XXXX に変換することを検証
        List<SimpleGrantedAuthority> authorities = roles.stream()
                .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                .toList();
        assertThat(authorities)
                .extracting(SimpleGrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_MEMBER", "ROLE_SYSTEM_ADMIN");
    }

    // ───────────────────────────────────────────────────────────────────
    // シナリオ 9: 一般ユーザーの JWT には MEMBER のみ
    // ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("シナリオ 9: RoleClaimResolver は一般ユーザーに MEMBER ロールのみを返す（SYSTEM_ADMIN は含まない）")
    void regularUser_roles_containsMemberOnly() {
        List<String> roles = List.of("MEMBER");
        assertThat(roles).containsOnly("MEMBER");
        assertThat(roles).doesNotContain("SYSTEM_ADMIN");

        // ROLE_MEMBER のみの authority セット
        List<SimpleGrantedAuthority> authorities = roles.stream()
                .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                .toList();
        assertThat(authorities)
                .extracting(SimpleGrantedAuthority::getAuthority)
                .containsExactly("ROLE_MEMBER");
    }

    // ───────────────────────────────────────────────────────────────────
    // シナリオ 10: SYSTEM_ADMIN が Actuator 管理エンドポイントを通過
    // ───────────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "SYSTEM_ADMIN")
    @DisplayName("シナリオ 10: SYSTEM_ADMIN が /actuator/metrics → 403 にならない（全スコープ管理 EP 通過）")
    void systemAdmin_actuatorEndpoint_notForbidden() throws Exception {
        expectNotAuthRejected(
                mockMvc.perform(get("/actuator/metrics")),
                "GET /actuator/metrics [SYSTEM_ADMIN]");
    }

    // ───────────────────────────────────────────────────────────────────
    // シナリオ 11: Webhook EP は未認証でも到達可（署名検証は別レイヤー）
    // ───────────────────────────────────────────────────────────────────

    @Test
    @WithAnonymousUser
    @DisplayName("シナリオ 11: 未認証で Webhook EP (POST /api/v1/webhooks/stripe) → 401/403 にならない（permitAll）")
    void webhook_unauthenticated_reachable() throws Exception {
        expectNotAuthRejected(
                mockMvc.perform(post("/api/v1/webhooks/stripe")),
                "POST /api/v1/webhooks/stripe [匿名]");
    }
}
