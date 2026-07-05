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
import com.mannschaft.app.reservation.dto.GenerateSlotsResponse;
import com.mannschaft.app.reservation.dto.ReservationSlotResponse;
import com.mannschaft.app.reservation.dto.SlotTemplateResponse;
import com.mannschaft.app.reservation.service.ReservationGridService;
import com.mannschaft.app.reservation.service.ReservationSlotService;
import com.mannschaft.app.reservation.service.ReservationSlotTemplateService;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 週間テンプレート API の契約テスト（F03.4.2 試練・実フィルタチェーン経由）。
 *
 * <p>受け入れ条件との対応:
 * <ul>
 *   <li>F-13: 非 ADMIN は 403（{@code @PreAuthorize} 実発火）・未認証は 401</li>
 *   <li>§4: {@code dayOfWeek} の正準検証 — {@code MONDAY} フルネーム/小文字は
 *       {@code ReservationDayOfWeek} の Jackson デシリアライズ失敗で 400</li>
 *   <li>§4: {@code weeks} 範囲外（5）は Bean Validation で 400</li>
 *   <li>F-12: {@code CreateSlotRequest} へ {@code recurrenceRule} を送っても unknown property として
 *       無視され 201（入力側の廃止・後方互換）</li>
 * </ul>
 * {@link ReservationAuthorizationEnforcementTest} と同じ「SecurityConfig を最小コンテキストで読み込む」方式。</p>
 */
@SpringBootTest(
        classes = ReservationSlotTemplateControllerContractTest.MinimalTemplateSecurityConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("週間テンプレートAPI 契約テスト（F03.4.2 認可・dayOfWeek 400・weeks 400・F-12）")
class ReservationSlotTemplateControllerContractTest {

    private static final Long TEAM_ID = 10L;
    private static final Long ADMIN_USER_ID = 100L;
    private static final Long MEMBER_USER_ID = 200L;

    private static final String TEMPLATES_PATH = "/api/v1/teams/" + TEAM_ID + "/reservation-slot-templates";
    private static final String GENERATE_PATH = TEMPLATES_PATH + "/generate";
    private static final String SLOTS_PATH = "/api/v1/teams/" + TEAM_ID + "/reservation-slots";

    private static final String VALID_TEMPLATE_BODY = """
            {"name":"平日午前・席1","lineId":1,"dayOfWeek":"MON","startTime":"10:00","endTime":"13:00","capacity":1}
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
            ReservationSlotTemplateController.class,
            TeamReservationSlotController.class
    })
    static class MinimalTemplateSecurityConfig {

        @Bean
        AccessGuard accessGuard(AccessControlService accessControlService) {
            return new AccessGuard(accessControlService);
        }

        // ── SecurityConfig のフィルタチェーンが要求する Filter 群（AuthorizationIntegrationTest と同一） ──

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
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AccessControlService accessControlService;

    @MockitoBean
    private ReservationSlotTemplateService templateService;

    @MockitoBean
    private ReservationSlotService slotService;

    @MockitoBean
    private ReservationGridService gridService;

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

    private SlotTemplateResponse sampleTemplateResponse() {
        return SlotTemplateResponse.builder()
                .id(UUID.randomUUID())
                .name("平日午前・席1")
                .lineId(1L)
                .lineName("席1")
                .dayOfWeek("MON")
                .startTime(LocalTime.of(10, 0))
                .endTime(LocalTime.of(13, 0))
                .capacity(1)
                .isActive(true)
                .cellCount(6)
                .createdAt(LocalDateTime.now())
                .build();
    }

    // ────────────────────────────────────────────────────────────
    // F-13: 認可（403 / 401 / 2xx）
    // ────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = "200", roles = "MEMBER")
    @DisplayName("F-13: MEMBER が POST /reservation-slot-templates → 403（管理者ゲート実発火）")
    void member_createTemplate_forbidden() throws Exception {
        stubAsMember();

        mockMvc.perform(post(TEMPLATES_PATH)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_TEMPLATE_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "200", roles = "MEMBER")
    @DisplayName("F-13: MEMBER が POST /generate → 403（管理者ゲート実発火）")
    void member_generate_forbidden() throws Exception {
        stubAsMember();

        mockMvc.perform(post(GENERATE_PATH)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"weeks\":4}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("F-13: 未認証で POST /reservation-slot-templates → 401")
    void anonymous_createTemplate_unauthorized() throws Exception {
        mockMvc.perform(post(TEMPLATES_PATH)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_TEMPLATE_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "100", roles = "MEMBER")
    @DisplayName("F-1/F-13: ADMIN（isScopeAdmin=true）が POST /reservation-slot-templates → 201（認可通過）")
    void admin_createTemplate_created() throws Exception {
        stubAsAdmin();
        given(templateService.createTemplate(eq(TEAM_ID), any(), any())).willReturn(sampleTemplateResponse());

        mockMvc.perform(post(TEMPLATES_PATH)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_TEMPLATE_BODY))
                .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser(username = "100", roles = "MEMBER")
    @DisplayName("F-13: ADMIN が POST /generate → 200（認可通過・weeks 省略可）")
    void admin_generate_ok() throws Exception {
        stubAsAdmin();
        given(templateService.generate(eq(TEAM_ID), any(), any()))
                .willReturn(GenerateSlotsResponse.builder().generatedCount(6).build());

        mockMvc.perform(post(GENERATE_PATH)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }

    // ────────────────────────────────────────────────────────────
    // §4: dayOfWeek 正準（3文字大文字）の Jackson enum 400
    // ────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = "100", roles = "MEMBER")
    @DisplayName("§4: dayOfWeek=MONDAY（フルネーム）は ReservationDayOfWeek デシリアライズ失敗で 400")
    void dayOfWeek_fullName_badRequest() throws Exception {
        stubAsAdmin();

        mockMvc.perform(post(TEMPLATES_PATH)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dayOfWeek":"MONDAY","startTime":"10:00","endTime":"13:00"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "100", roles = "MEMBER")
    @DisplayName("§4: dayOfWeek=mon（小文字）も 400（正準は3文字大文字のみ）")
    void dayOfWeek_lowerCase_badRequest() throws Exception {
        stubAsAdmin();

        mockMvc.perform(post(TEMPLATES_PATH)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dayOfWeek":"mon","startTime":"10:00","endTime":"13:00"}
                                """))
                .andExpect(status().isBadRequest());
    }

    // ────────────────────────────────────────────────────────────
    // §4: weeks の Bean Validation（1〜4）
    // ────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = "100", roles = "MEMBER")
    @DisplayName("§4: weeks=5（範囲外）は Bean Validation で 400")
    void generate_weeksOutOfRange_badRequest() throws Exception {
        stubAsAdmin();

        mockMvc.perform(post(GENERATE_PATH)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"weeks\":5}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "100", roles = "MEMBER")
    @DisplayName("§4: weeks=0（範囲外）は Bean Validation で 400")
    void generate_weeksZero_badRequest() throws Exception {
        stubAsAdmin();

        mockMvc.perform(post(GENERATE_PATH)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"weeks\":0}"))
                .andExpect(status().isBadRequest());
    }

    // ────────────────────────────────────────────────────────────
    // F-12: recurrenceRule 入力の廃止（unknown property は無視）
    // ────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = "100", roles = "MEMBER")
    @DisplayName("F-12: CreateSlotRequest に recurrenceRule を送っても無視され 201（Jackson 既定の unknown property 無視）")
    void createSlot_recurrenceRule_ignored() throws Exception {
        stubAsAdmin();
        given(slotService.createSlot(eq(TEAM_ID), any(), any()))
                .willReturn(ReservationSlotResponse.builder().id(1L).teamId(TEAM_ID).build());

        mockMvc.perform(post(SLOTS_PATH)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"slotDate":"2099-01-01","startTime":"10:00","endTime":"11:00",
                                 "recurrenceRule":"{\\"type\\":\\"WEEKLY\\"}"}
                                """))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("F-12: CreateSlotRequest から recurrenceRule フィールドが削除されている（入力側の廃止・§3.3）")
    void createSlotRequest_hasNoRecurrenceRuleField() {
        org.assertj.core.api.Assertions.assertThat(
                        java.util.Arrays.stream(
                                        com.mannschaft.app.reservation.dto.CreateSlotRequest.class.getDeclaredFields())
                                .map(java.lang.reflect.Field::getName))
                .doesNotContain("recurrenceRule")
                .contains("lineId");
    }
}
