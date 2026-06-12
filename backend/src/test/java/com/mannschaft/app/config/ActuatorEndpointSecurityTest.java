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

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F10.5 Phase 10-α §5.1.1 / §6.1: Actuator エンドポイントのセキュリティテスト。
 *
 * <p>{@link SecurityConfig} の改修により以下を担保する:</p>
 * <ul>
 *   <li>{@code /actuator/health} は匿名で 200 (Liveness/Readiness 用)</li>
 *   <li>{@code /actuator/metrics} は匿名で 401 / 403</li>
 *   <li>{@code /actuator/prometheus} は匿名で 401 / 403</li>
 *   <li>SYSTEM_ADMIN ロール保持者であれば上記 metrics / prometheus も 200</li>
 * </ul>
 *
 * <p><b>テスト境界の方針</b>: 本テストは Spring Security のロジック検証なので
 * Mock 認証 ({@code @WithMockUser}) を使用し、JWT 発行や DB 認証は対象外。
 * F10.5 Phase 10-α 検分指摘 ① の根治治療として、Docker / DB / Redis / Testcontainers に
 * 依存しない構成へ書き換えた。これによりローカル Docker の起動有無に関係なく
 * 常時実行可能となる。</p>
 *
 * <p><b>ロード戦略</b>: 業務 {@code @ComponentScan} を回避するため、本テスト専用の
 * 最小 {@code @Configuration} ({@link MinimalActuatorTestConfig}) を {@code classes} に渡す。
 * {@code @EnableAutoConfiguration} で Spring Boot の通常の自動構成を有効化しつつ、
 * Datasource / JPA / Flyway / Redis 系を {@code exclude} で除外することで Docker 不要にする。
 * SecurityConfig が必要とする {@code JwtAuthenticationFilter} / {@code ProxyInputContextFilter} は
 * 本物のインスタンスを構築し、その内部依存だけ Mockito でモック化する（フィルタ自身の
 * doFilter 実装が chain.doFilter を呼んで AuthorizationFilter まで到達できるようにする）。</p>
 *
 * <p><b>application-test.yml</b>: {@code management.health.db.enabled=false} /
 * {@code management.health.redis.enabled=false} 等の既存設定により、Health Indicator は
 * アプリ自身の死活のみ評価する。</p>
 */
@SpringBootTest(
        classes = ActuatorEndpointSecurityTest.MinimalActuatorTestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Actuator エンドポイントのセキュリティ (F10.5 Phase 10-α)")
class ActuatorEndpointSecurityTest {

    /**
     * 本テスト用の最小コンテキスト構成。MannschaftApplication の {@code @ComponentScan} を
     * 経由せず、SecurityConfig と Spring Boot 自動構成のみを取り込む。
     * Datasource / JPA / Flyway / Redis 系の自動構成は明示的に除外する
     * （Docker 起動を不要にし常時実行可能とする）。Actuator / Web MVC / Security の
     * 自動構成は通常通り効くため、{@code EndpointRequest.toAnyEndpoint()} 等の
     * 標準 API もそのまま動作する。
     *
     * <p>SecurityConfig が依存する 2 つのフィルタは本構成で本物のインスタンスを供給する。
     * フィルタ内部の依存（{@code AuthTokenService} 等）はモック化するため、
     * Authorization ヘッダーが空（テストの匿名・WithMockUser）であれば各フィルタは
     * 単純に chain.doFilter に通す挙動になり、AuthorizationFilter まで処理が到達する。</p>
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
    static class MinimalActuatorTestConfig {

        /**
         * JwtAuthenticationFilter の本物インスタンス。{@code AuthTokenService} はモック化。
         * Authorization ヘッダーが無いリクエスト（本テストの全ケース）では何もせず chain に通す。
         */
        @Bean
        JwtAuthenticationFilter jwtAuthenticationFilter() {
            return new JwtAuthenticationFilter(mock(AuthTokenService.class));
        }

        /**
         * ProxyInputContextFilter の本物インスタンス。内部依存はモック化。
         * X-Proxy-For-User-Id ヘッダーが無いリクエストでは何もせず chain に通す。
         */
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


        /**
         * F15.4: SecurityConfig が依存する PublicTeamApiRateLimitFilter の
         * 本物インスタンス。AuditLogService は ObjectProvider 経由で弱結合化されているため
         * 空の ObjectProvider モックを渡す。
         * 本テストは /actuator/** のみを叩くため、対象パス
         * （/api/v1/organizations/{orgId}/teams/search / /api/v1/public/teams/{id}）の
         * 正規表現に一致せず、何もせず chain.doFilter に通す挙動になる
         * （AuditLogService.record は呼ばれない）。
         *
         * <p>※ クラス名遷移:
         *   F15.4 Phase 1: {@code OrganizationTeamSearchRateLimitFilter}
         *   → F15.4 Phase 5-α: {@code PublicTeamApiRateLimitFilter}
         *   → F19.1 Phase 1: {@link PublicApiRateLimitFilter}（リネーム + 拡張）。
         */
        @Bean
        @SuppressWarnings("unchecked")
        PublicApiRateLimitFilter publicApiRateLimitFilter() {
            // Valkey 化第一陣: ValkeyRateLimiter は空 Provider（getIfAvailable()=null）→ フィルタは素通し
            org.springframework.beans.factory.ObjectProvider<com.mannschaft.app.common.ratelimit.ValkeyRateLimiter> rateLimiterProvider =
                    mock(org.springframework.beans.factory.ObjectProvider.class);
            org.springframework.beans.factory.ObjectProvider<com.mannschaft.app.auth.service.AuditLogService> auditProvider =
                    mock(org.springframework.beans.factory.ObjectProvider.class);
            // F19.1 Phase 5: MeterRegistry ObjectProvider も渡す（何もしない Empty Provider）
            org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meterProvider =
                    mock(org.springframework.beans.factory.ObjectProvider.class);
            return new PublicApiRateLimitFilter(rateLimiterProvider, auditProvider, meterProvider);
        }

        /**
         * Valkey 化第二陣B: AdPublicEndpointRateLimitFilter の本物インスタンス。
         * ValkeyRateLimiter は空 Provider（getIfAvailable()=null）→ フィルタは素通し。
         * 本テストは /actuator/** のみを叩くため、対象パス
         * （/api/v1/ads/unsubscribe, /api/v1/ads/pixels/open）と一致せず素通しする。
         */
        @Bean
        @SuppressWarnings("unchecked")
        AdPublicEndpointRateLimitFilter adPublicEndpointRateLimitFilter() {
            org.springframework.beans.factory.ObjectProvider<com.mannschaft.app.common.ratelimit.ValkeyRateLimiter> rateLimiterProvider =
                    mock(org.springframework.beans.factory.ObjectProvider.class);
            return new AdPublicEndpointRateLimitFilter(rateLimiterProvider);
        }

        /**
         * Valkey 化第二陣B: ScheduleDelegationRateLimitFilter の本物インスタンス。
         * ValkeyRateLimiter は空 Provider（素通し）。本テストは /actuator/** のみを
         * 叩くため、対象パス（POST /api/v1/schedules/{id}/delegations）に一致せず素通しする。
         */
        @Bean
        @SuppressWarnings("unchecked")
        com.mannschaft.app.schedule.ScheduleDelegationRateLimitFilter scheduleDelegationRateLimitFilter() {
            org.springframework.beans.factory.ObjectProvider<com.mannschaft.app.common.ratelimit.ValkeyRateLimiter> rateLimiterProvider =
                    mock(org.springframework.beans.factory.ObjectProvider.class);
            return new com.mannschaft.app.schedule.ScheduleDelegationRateLimitFilter(rateLimiterProvider);
        }

        /**
         * Valkey 化第二陣B: EventDelegationRateLimitFilter の本物インスタンス。
         * ValkeyRateLimiter は空 Provider（素通し）。本テストは対象パス
         * （POST /api/v1/events/{id}/delegations）に一致せず素通しする。
         */
        @Bean
        @SuppressWarnings("unchecked")
        com.mannschaft.app.event.EventDelegationRateLimitFilter eventDelegationRateLimitFilter() {
            org.springframework.beans.factory.ObjectProvider<com.mannschaft.app.common.ratelimit.ValkeyRateLimiter> rateLimiterProvider =
                    mock(org.springframework.beans.factory.ObjectProvider.class);
            return new com.mannschaft.app.event.EventDelegationRateLimitFilter(rateLimiterProvider);
        }

        /**
         * Valkey 化第二陣B: DashboardScopeTabRateLimitFilter の本物インスタンス。
         * ValkeyRateLimiter は空 Provider（素通し）。本テストは対象パス
         * （PUT /api/v1/dashboard/scope-tabs/order）に一致せず素通しする。
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

    /**
     * SecurityConfig は不要。{@link MinimalActuatorTestConfig} 内の {@code @Bean} で
     * 本物のフィルタが供給されるため、Authorization 評価が中断されることなく
     * AuthorizationFilter まで処理が到達する。フィルタ自身の挙動は別テスト
     * ({@code JwtAuthenticationFilterTest} 等) で担保する。
     */
    @MockitoBean
    @SuppressWarnings("unused")
    private ProxyInputConsentRepository proxyInputConsentRepository;

    @Test
    @WithAnonymousUser
    @DisplayName("匿名: /actuator/health は 200")
    void anonymous_can_access_health() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("匿名: /actuator/metrics は 401 または 403")
    void anonymous_cannot_access_metrics() throws Exception {
        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    if (status != 401 && status != 403) {
                        throw new AssertionError("expected 401 or 403, got " + status);
                    }
                });
    }

    @Test
    @WithAnonymousUser
    @DisplayName("匿名: /actuator/prometheus は 401 または 403")
    void anonymous_cannot_access_prometheus() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    if (status != 401 && status != 403) {
                        throw new AssertionError("expected 401 or 403, got " + status);
                    }
                });
    }

    @Test
    @WithMockUser(roles = "SYSTEM_ADMIN")
    @DisplayName("SYSTEM_ADMIN: /actuator/metrics は 200")
    void system_admin_can_access_metrics() throws Exception {
        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "SYSTEM_ADMIN")
    @DisplayName("SYSTEM_ADMIN: /actuator/prometheus は 200")
    void system_admin_can_access_prometheus() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk());
    }
}
