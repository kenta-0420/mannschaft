package com.mannschaft.app.reservation.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.admin.filter.AdminImpersonationFilter;
import com.mannschaft.app.advertising.campaign.filter.AdPublicEndpointRateLimitFilter;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.security.AccessGuard;
import com.mannschaft.app.config.JwtAuthenticationFilter;
import com.mannschaft.app.config.SecurityConfig;
import com.mannschaft.app.dashboard.DashboardScopeTabRateLimitFilter;
import com.mannschaft.app.event.EventDelegationRateLimitFilter;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.ProxyInputContextFilter;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.publicview.filter.PublicApiRateLimitFilter;
import com.mannschaft.app.reservation.dto.RecurringBlockedTimeResponse;
import com.mannschaft.app.reservation.service.ReservationRecurringBlockedTimeService;
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
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 定期予約不可枠 API の契約テスト（F03.4.5 §4 W2-2 試練・実フィルタチェーン経由）。
 *
 * <p>受け入れ条件との対応:
 * <ul>
 *   <li><b>R-6（全日型拒否）</b>: {@code startTime}/{@code endTime} 欠落は {@code @NotNull} の
 *       Bean Validation で 400（「終日休みは営業時間の定休日で」の棲み分け・§4.3）</li>
 *   <li><b>R-6付随</b>: {@code reason} 欠落は {@code @NotBlank} で 400</li>
 *   <li><b>曜日正準</b>: {@code dayOfWeek=MONDAY}（フルネーム）は {@code ReservationDayOfWeek} の
 *       Jackson デシリアライズ失敗で 400</li>
 *   <li><b>R-11（認可）</b>: 非 ADMIN は 403・未認証は 401（{@code @PreAuthorize} 実発火）</li>
 *   <li>正常系: ADMIN の有効ボディは 201</li>
 * </ul>
 * {@link ReservationSlotTemplateControllerContractTest} と同じ最小セキュリティコンテキスト方式。</p>
 */
@SpringBootTest(
        classes = ReservationRecurringBlockedTimeControllerContractTest.MinimalRecurringSecurityConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("定期予約不可枠API 契約テスト（F03.4.5 §4 W2-2・R-6全日型拒否/曜日400/認可）")
class ReservationRecurringBlockedTimeControllerContractTest {

    private static final Long TEAM_ID = 10L;
    private static final Long ADMIN_USER_ID = 100L;
    private static final Long MEMBER_USER_ID = 200L;

    private static final String RULES_PATH =
            "/api/v1/teams/" + TEAM_ID + "/reservation-recurring-blocked-times";

    private static final String VALID_BODY = """
            {"lineId":null,"dayOfWeek":"TUE","startTime":"19:00","endTime":"20:00","reason":"研修","isPublic":true}
            """;

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
    @Import({
            SecurityConfig.class,
            ReservationRecurringBlockedTimeController.class
    })
    static class MinimalRecurringSecurityConfig {

        @Bean
        AccessGuard accessGuard(AccessControlService accessControlService) {
            return new AccessGuard(accessControlService);
        }

        @Bean
        JwtAuthenticationFilter jwtAuthenticationFilter() {
            return new JwtAuthenticationFilter(org.mockito.Mockito.mock(AuthTokenService.class));
        }

        @Bean
        AdminImpersonationFilter adminImpersonationFilter() {
            return new AdminImpersonationFilter(org.mockito.Mockito.mock(ObjectMapper.class));
        }

        @Bean
        ProxyInputContextFilter proxyInputContextFilter() {
            return new ProxyInputContextFilter(
                    org.mockito.Mockito.mock(ProxyInputConsentRepository.class),
                    org.mockito.Mockito.mock(ProxyInputContext.class),
                    org.mockito.Mockito.mock(ObjectMapper.class),
                    mockGuardianshipSwitchServiceProvider());
        }

        @SuppressWarnings("unchecked")
        private static org.springframework.beans.factory.ObjectProvider<com.mannschaft.app.auth.guardianship.GuardianshipSwitchService>
                mockGuardianshipSwitchServiceProvider() {
            return org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        }

        @Bean
        @SuppressWarnings("unchecked")
        PublicApiRateLimitFilter publicApiRateLimitFilter() {
            org.springframework.beans.factory.ObjectProvider<com.mannschaft.app.common.ratelimit.ValkeyRateLimiter> rateLimiterProvider =
                    org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
            org.springframework.beans.factory.ObjectProvider<com.mannschaft.app.auth.service.AuditLogService> auditProvider =
                    org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
            org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meterProvider =
                    org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
            return new PublicApiRateLimitFilter(rateLimiterProvider, auditProvider, meterProvider);
        }

        @Bean
        @SuppressWarnings("unchecked")
        AdPublicEndpointRateLimitFilter adPublicEndpointRateLimitFilter() {
            org.springframework.beans.factory.ObjectProvider<com.mannschaft.app.common.ratelimit.ValkeyRateLimiter> rateLimiterProvider =
                    org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
            return new AdPublicEndpointRateLimitFilter(rateLimiterProvider);
        }

        @Bean
        @SuppressWarnings("unchecked")
        com.mannschaft.app.analytics.filter.PageViewBeaconRateLimitFilter pageViewBeaconRateLimitFilter() {
            org.springframework.beans.factory.ObjectProvider<com.mannschaft.app.common.ratelimit.ValkeyRateLimiter> rateLimiterProvider =
                    org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
            return new com.mannschaft.app.analytics.filter.PageViewBeaconRateLimitFilter(rateLimiterProvider);
        }

        @Bean
        @SuppressWarnings("unchecked")
        ScheduleDelegationRateLimitFilter scheduleDelegationRateLimitFilter() {
            org.springframework.beans.factory.ObjectProvider<com.mannschaft.app.common.ratelimit.ValkeyRateLimiter> rateLimiterProvider =
                    org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
            return new ScheduleDelegationRateLimitFilter(rateLimiterProvider);
        }

        @Bean
        @SuppressWarnings("unchecked")
        EventDelegationRateLimitFilter eventDelegationRateLimitFilter() {
            org.springframework.beans.factory.ObjectProvider<com.mannschaft.app.common.ratelimit.ValkeyRateLimiter> rateLimiterProvider =
                    org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
            return new EventDelegationRateLimitFilter(rateLimiterProvider);
        }

        @Bean
        @SuppressWarnings("unchecked")
        DashboardScopeTabRateLimitFilter dashboardScopeTabRateLimitFilter() {
            org.springframework.beans.factory.ObjectProvider<com.mannschaft.app.common.ratelimit.ValkeyRateLimiter> rateLimiterProvider =
                    org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
            return new DashboardScopeTabRateLimitFilter(rateLimiterProvider);
        }

        @Bean
        @SuppressWarnings("unchecked")
        com.mannschaft.app.village.VillageAffinityRateLimitFilter villageAffinityRateLimitFilter() {
            org.springframework.beans.factory.ObjectProvider<com.mannschaft.app.common.ratelimit.ValkeyRateLimiter> rateLimiterProvider =
                    org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
            return new com.mannschaft.app.village.VillageAffinityRateLimitFilter(rateLimiterProvider);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AccessControlService accessControlService;

    @MockitoBean
    private ReservationRecurringBlockedTimeService ruleService;

    /** ProxyInputContextFilter 依存の JPA ロード防止。 */
    @MockitoBean
    @SuppressWarnings("unused")
    private ProxyInputConsentRepository proxyInputConsentRepository;

    private void stubAsMember() {
        given(accessControlService.isSystemAdmin(MEMBER_USER_ID)).willReturn(false);
        given(accessControlService.isAdminOrAbove(MEMBER_USER_ID, TEAM_ID, "TEAM")).willReturn(false);
    }

    private void stubAsAdmin() {
        given(accessControlService.isSystemAdmin(ADMIN_USER_ID)).willReturn(false);
        given(accessControlService.isAdminOrAbove(ADMIN_USER_ID, TEAM_ID, "TEAM")).willReturn(true);
    }

    private RecurringBlockedTimeResponse sampleResponse() {
        return RecurringBlockedTimeResponse.builder()
                .id(UUID.randomUUID())
                .teamId(TEAM_ID)
                .dayOfWeek("TUE")
                .startTime(LocalTime.of(19, 0))
                .endTime(LocalTime.of(20, 0))
                .reason("研修")
                .isPublic(true)
                .isActive(true)
                .createdAt(LocalDateTime.now())
                .build();
    }

    // ────────────────────────────────────────────────────────────
    // R-6: 全日型拒否（start/end 欠落は 400）
    // ────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = "100", roles = "MEMBER")
    @DisplayName("R-6: startTime 欠落は Bean Validation(@NotNull) で 400（全日型は作らない）")
    void R6_startTime欠落は400() throws Exception {
        stubAsAdmin();

        mockMvc.perform(post(RULES_PATH)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dayOfWeek":"TUE","endTime":"20:00","reason":"研修","isPublic":true}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "100", roles = "MEMBER")
    @DisplayName("R-6: endTime 欠落は Bean Validation(@NotNull) で 400（全日型は作らない）")
    void R6_endTime欠落は400() throws Exception {
        stubAsAdmin();

        mockMvc.perform(post(RULES_PATH)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dayOfWeek":"TUE","startTime":"19:00","reason":"研修","isPublic":true}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "100", roles = "MEMBER")
    @DisplayName("R-6: start/end 両方欠落（全日型）は 400")
    void R6_全日型は400() throws Exception {
        stubAsAdmin();

        mockMvc.perform(post(RULES_PATH)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dayOfWeek":"TUE","reason":"研修","isPublic":true}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "100", roles = "MEMBER")
    @DisplayName("reason 欠落は Bean Validation(@NotBlank) で 400（事由は必須）")
    void reason欠落は400() throws Exception {
        stubAsAdmin();

        mockMvc.perform(post(RULES_PATH)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dayOfWeek":"TUE","startTime":"19:00","endTime":"20:00","isPublic":true}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "100", roles = "MEMBER")
    @DisplayName("dayOfWeek=MONDAY（フルネーム）は ReservationDayOfWeek デシリアライズ失敗で 400")
    void dayOfWeekフルネームは400() throws Exception {
        stubAsAdmin();

        mockMvc.perform(post(RULES_PATH)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dayOfWeek":"MONDAY","startTime":"19:00","endTime":"20:00","reason":"研修"}
                                """))
                .andExpect(status().isBadRequest());
    }

    // ────────────────────────────────────────────────────────────
    // R-11: 認可（403 / 401 / 201）
    // ────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = "200", roles = "MEMBER")
    @DisplayName("R-11: MEMBER が POST → 403（管理者ゲート実発火）")
    void member_create_forbidden() throws Exception {
        stubAsMember();

        mockMvc.perform(post(RULES_PATH)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("R-11: 未認証で POST → 401")
    void anonymous_create_unauthorized() throws Exception {
        mockMvc.perform(post(RULES_PATH)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "100", roles = "MEMBER")
    @DisplayName("R-1/R-11: ADMIN（isScopeAdmin=true）の有効ボディは 201")
    void admin_create_created() throws Exception {
        stubAsAdmin();
        given(ruleService.createRule(eq(TEAM_ID), any(), any())).willReturn(sampleResponse());

        mockMvc.perform(post(RULES_PATH)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.dayOfWeek").value("TUE"))
                .andExpect(jsonPath("$.data.reason").value("研修"))
                .andExpect(jsonPath("$.data.isPublic").value(true));
    }
}
