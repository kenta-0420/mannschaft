package com.mannschaft.app.reservation.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.admin.filter.AdminImpersonationFilter;
import com.mannschaft.app.advertising.campaign.filter.AdPublicEndpointRateLimitFilter;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.GlobalExceptionHandler;
import com.mannschaft.app.common.security.AccessGuard;
import com.mannschaft.app.config.JwtAuthenticationFilter;
import com.mannschaft.app.config.SecurityConfig;
import com.mannschaft.app.dashboard.DashboardScopeTabRateLimitFilter;
import com.mannschaft.app.event.EventDelegationRateLimitFilter;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.ProxyInputContextFilter;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.publicview.filter.PublicApiRateLimitFilter;
import com.mannschaft.app.reservation.ReservationErrorCode;
import com.mannschaft.app.reservation.dto.ReservationMenuResponse;
import com.mannschaft.app.reservation.service.ReservationMenuService;
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
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 予約メニュー API の認可ゲート・HTTP 契約の実発火テスト
 * （F03.4.1 §8 E-7 の認可部分・E-9 の 404 契約。実フィルタチェーン経由）。
 *
 * <p>{@code ReservationAuthorizationEnforcementTest} と同型の
 * 「SecurityConfig を最小コンテキストで読み込む（DB/Redis/Flyway 不要）」方式。
 * {@code @PreAuthorize("@accessGuard.isScopeAdmin(...)")} が実 HTTP 経路で 403 を返すこと、
 * {@code GlobalExceptionHandler} の個別マッピングで RESERVATION_032 が 404 になることを検証する。</p>
 */
@SpringBootTest(
        classes = ReservationMenuAuthorizationEnforcementTest.MinimalMenuSecurityConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("予約メニューAPI 認可ゲート・HTTP契約 実発火テスト（E-7 認可 / E-9 404）")
class ReservationMenuAuthorizationEnforcementTest {

    private static final Long TEAM_ID = 10L;
    private static final Long ADMIN_USER_ID = 100L;
    private static final Long MEMBER_USER_ID = 200L;

    private static final String MENUS_PATH = "/api/v1/teams/" + TEAM_ID + "/reservation-menus";

    private static final String VALID_MENU_BODY = "{\"name\":\"カット\",\"durationMinutes\":60}";

    /**
     * SecurityConfig（{@code @EnableMethodSecurity} を内包）＋メニューコントローラ＋
     * GlobalExceptionHandler のみを最小コンテキストで読み込む。
     * {@code ReservationAuthorizationEnforcementTest.MinimalReservationSecurityConfig} と同型。
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
    @Import({
            SecurityConfig.class,
            ReservationMenuController.class,
            GlobalExceptionHandler.class
    })
    static class MinimalMenuSecurityConfig {

        /** SpEL {@code @accessGuard.isScopeAdmin(...)} の解決に必要な実 AccessGuard。 */
        @Bean
        AccessGuard accessGuard(AccessControlService accessControlService) {
            return new AccessGuard(accessControlService);
        }

        // ── SecurityConfig のフィルタチェーンが要求する Filter 群（既存テストと同一） ──

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
    private ReservationMenuService menuService;

    /** ProxyInputContextFilter 依存の JPA ロード防止（既存テストと同様）。 */
    @MockitoBean
    @SuppressWarnings("unused")
    private ProxyInputConsentRepository proxyInputConsentRepository;

    private void stubNonAdmin(Long userId) {
        given(accessControlService.isSystemAdmin(userId)).willReturn(false);
        given(accessControlService.isAdminOrAbove(userId, TEAM_ID, "TEAM")).willReturn(false);
    }

    private void stubAdmin(Long userId) {
        given(accessControlService.isSystemAdmin(userId)).willReturn(false);
        given(accessControlService.isAdminOrAbove(userId, TEAM_ID, "TEAM")).willReturn(true);
    }

    private ReservationMenuResponse sampleMenuResponse() {
        return ReservationMenuResponse.builder()
                .id(UUID.randomUUID())
                .name("カット")
                .durationMinutes(60)
                .requiredSlotCount(2)
                .displayOrder(1)
                .isActive(true)
                .lineIds(List.of())
                .createdAt(LocalDateTime.now())
                .build();
    }

    // ── E-7: 管理系は非 ADMIN で 403（@PreAuthorize 実発火）──────

    @Test
    @WithMockUser(username = "200", roles = "MEMBER")
    @DisplayName("E-7: MEMBER が POST /reservation-menus → 403（管理者ゲート発火）")
    void member_createMenu_forbidden() throws Exception {
        stubNonAdmin(MEMBER_USER_ID);

        mockMvc.perform(post(MENUS_PATH)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_MENU_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "200", roles = "MEMBER")
    @DisplayName("E-7: MEMBER が PATCH /reservation-menus/{menuId} → 403")
    void member_updateMenu_forbidden() throws Exception {
        stubNonAdmin(MEMBER_USER_ID);

        mockMvc.perform(patch(MENUS_PATH + "/" + UUID.randomUUID())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"新名称\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "200", roles = "MEMBER")
    @DisplayName("E-7: MEMBER が DELETE /reservation-menus/{menuId} → 403")
    void member_deleteMenu_forbidden() throws Exception {
        stubNonAdmin(MEMBER_USER_ID);

        mockMvc.perform(delete(MENUS_PATH + "/" + UUID.randomUUID())
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    // ── E-7: 未認証は 401 ───────────────────────────────────────

    @Test
    @DisplayName("E-7: 未認証の GET /reservation-menus → 401")
    void anonymous_listMenus_unauthorized() throws Exception {
        mockMvc.perform(get(MENUS_PATH))
                .andExpect(status().isUnauthorized());
    }

    // ── ADMIN は認可通過（2xx）──────────────────────────────────

    @Test
    @WithMockUser(username = "100", roles = "MEMBER")
    @DisplayName("E-1/E-7: ADMIN（isScopeAdmin=true）が POST → 201・requiredSlotCount/lineIds を含む契約形")
    void admin_createMenu_created() throws Exception {
        stubAdmin(ADMIN_USER_ID);
        given(menuService.createMenu(eq(TEAM_ID), any(), anyLong())).willReturn(sampleMenuResponse());

        mockMvc.perform(post(MENUS_PATH)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_MENU_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.requiredSlotCount").value(2))
                .andExpect(jsonPath("$.data.lineIds").isArray());
    }

    @Test
    @WithMockUser(username = "100", roles = "MEMBER")
    @DisplayName("E-7: ADMIN の GET → 200（view ゲートは Service 層委譲・@PreAuthorize なし）")
    void admin_listMenus_ok() throws Exception {
        given(menuService.listMenus(eq(TEAM_ID), anyLong())).willReturn(List.of(sampleMenuResponse()));

        mockMvc.perform(get(MENUS_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("カット"));
    }

    // ── E-9: RESERVATION_032 は HTTP 404 に個別マッピングされる ──

    @Test
    @WithMockUser(username = "100", roles = "MEMBER")
    @DisplayName("E-9: 不存在メニューへの PATCH → 404（RESERVATION_032 個別マッピング・存在秘匿）")
    void admin_updateMissingMenu_notFound() throws Exception {
        stubAdmin(ADMIN_USER_ID);
        given(menuService.updateMenu(eq(TEAM_ID), any(UUID.class), any(), anyLong()))
                .willThrow(new BusinessException(ReservationErrorCode.MENU_NOT_FOUND));

        mockMvc.perform(patch(MENUS_PATH + "/" + UUID.randomUUID())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "100", roles = "MEMBER")
    @DisplayName("E-9: 不存在メニューへの DELETE → 404（RESERVATION_032 個別マッピング）")
    void admin_deleteMissingMenu_notFound() throws Exception {
        stubAdmin(ADMIN_USER_ID);
        given(menuService.deleteMenu(eq(TEAM_ID), any(UUID.class), anyLong()))
                .willThrow(new BusinessException(ReservationErrorCode.MENU_NOT_FOUND));

        mockMvc.perform(delete(MENUS_PATH + "/" + UUID.randomUUID())
                        .with(csrf()))
                .andExpect(status().isNotFound());
    }
}
