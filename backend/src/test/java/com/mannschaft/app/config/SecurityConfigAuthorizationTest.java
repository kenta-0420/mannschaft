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
                    mock(ObjectMapper.class));
        }

        @Bean
        @SuppressWarnings("unchecked")
        PublicApiRateLimitFilter publicApiRateLimitFilter() {
            org.springframework.beans.factory.ObjectProvider<com.mannschaft.app.auth.service.AuditLogService> auditProvider =
                    mock(org.springframework.beans.factory.ObjectProvider.class);
            org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meterProvider =
                    mock(org.springframework.beans.factory.ObjectProvider.class);
            return new PublicApiRateLimitFilter(auditProvider, meterProvider);
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

    // ---- 公開エンドポイント（既存許可リスト）が反転後も維持されること ----

    @Test
    @WithAnonymousUser
    @DisplayName("匿名: POST /api/v1/auth/login は認証で弾かれない")
    void anonymous_login_not_auth_rejected() throws Exception {
        expectNotAuthRejected(mockMvc.perform(post("/api/v1/auth/login")),
                "POST /api/v1/auth/login");
    }
}
