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
import com.mannschaft.app.reservation.dto.ReservationGroupCancelResponse;
import com.mannschaft.app.reservation.dto.ReservationGroupResponse;
import com.mannschaft.app.reservation.service.ReservationGroupService;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 予約グループ API の認可ゲート・HTTP 契約の実発火テスト
 * （F03.4.3 §4/§6 の認可割り付け・§9 のエラーコード HTTP マッピング。実フィルタチェーン経由）。
 *
 * <p>{@code ReservationMenuAuthorizationEnforcementTest} と同型の
 * 「SecurityConfig を最小コンテキストで読み込む（DB/Redis/Flyway 不要）」方式。検証点:</p>
 * <ul>
 *   <li>ADMIN 限定は 3 本のみ（confirm/complete/no-show）— MEMBER は 403（G-12 の認可面）</li>
 *   <li>POST 作成 / GET 詳細 / cancel は {@code @PreAuthorize} なし（Service 層判定）で認証済みなら通過</li>
 *   <li>RESERVATION_039 → 409 / RESERVATION_040 → 404 / 041・042・043 → 400 の個別マッピング</li>
 * </ul>
 */
@SpringBootTest(
        classes = ReservationGroupAuthorizationEnforcementTest.MinimalGroupSecurityConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("予約グループAPI 認可ゲート・HTTP契約 実発火テスト（機能G）")
class ReservationGroupAuthorizationEnforcementTest {

    private static final Long TEAM_ID = 10L;
    private static final Long ADMIN_USER_ID = 100L;
    private static final Long MEMBER_USER_ID = 200L;
    private static final UUID GROUP_ID = UUID.randomUUID();

    private static final String GROUPS_PATH = "/api/v1/teams/" + TEAM_ID + "/reservation-groups";

    private static final String VALID_CREATE_BODY = "{\"lineId\":1,\"slotIds\":[101,102]}";

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
            ReservationGroupController.class,
            GlobalExceptionHandler.class
    })
    static class MinimalGroupSecurityConfig {

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
    private ReservationGroupService groupService;

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

    private ReservationGroupResponse sampleGroupResponse() {
        return ReservationGroupResponse.builder()
                .groupId(GROUP_ID)
                .teamId(TEAM_ID)
                .userId(MEMBER_USER_ID)
                .status("CONFIRMED")
                .lineId(1L)
                .lineName("席1")
                .slotDate(LocalDate.of(2026, 7, 10))
                .startTime(LocalTime.of(10, 0))
                .endTime(LocalTime.of(11, 0))
                .slotCount(2)
                .menuName("カット")
                .bookedAt(LocalDateTime.now())
                .reservations(List.of(
                        new ReservationGroupResponse.ReservationGroupItemDto(
                                501L, 101L, LocalTime.of(10, 0), LocalTime.of(10, 30), true),
                        new ReservationGroupResponse.ReservationGroupItemDto(
                                502L, 102L, LocalTime.of(10, 30), LocalTime.of(11, 0), false)))
                .build();
    }

    // ── ADMIN 限定 3 本（confirm/complete/no-show）: MEMBER は 403 ──

    @Test
    @WithMockUser(username = "200", roles = "MEMBER")
    @DisplayName("認可: MEMBER が POST /{groupId}/confirm → 403（isScopeAdmin 実発火）")
    void member_confirm_forbidden() throws Exception {
        stubNonAdmin(MEMBER_USER_ID);

        mockMvc.perform(post(GROUPS_PATH + "/" + GROUP_ID + "/confirm").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "200", roles = "MEMBER")
    @DisplayName("認可: MEMBER が POST /{groupId}/complete → 403")
    void member_complete_forbidden() throws Exception {
        stubNonAdmin(MEMBER_USER_ID);

        mockMvc.perform(post(GROUPS_PATH + "/" + GROUP_ID + "/complete").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "200", roles = "MEMBER")
    @DisplayName("認可: MEMBER が POST /{groupId}/no-show → 403")
    void member_noShow_forbidden() throws Exception {
        stubNonAdmin(MEMBER_USER_ID);

        mockMvc.perform(post(GROUPS_PATH + "/" + GROUP_ID + "/no-show").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "100", roles = "MEMBER")
    @DisplayName("認可: ADMIN（isScopeAdmin=true）の confirm → 200")
    void admin_confirm_ok() throws Exception {
        stubAdmin(ADMIN_USER_ID);
        given(groupService.confirmGroup(eq(TEAM_ID), any(UUID.class), anyLong()))
                .willReturn(sampleGroupResponse());

        mockMvc.perform(post(GROUPS_PATH + "/" + GROUP_ID + "/confirm").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));
    }

    // ── 未認証は 401 ─────────────────────────────────────────────

    @Test
    @DisplayName("認可: 未認証の GET /{groupId} → 401")
    void anonymous_getGroup_unauthorized() throws Exception {
        mockMvc.perform(get(GROUPS_PATH + "/" + GROUP_ID))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("認可: 未認証の POST 作成 → 401")
    void anonymous_createGroup_unauthorized() throws Exception {
        mockMvc.perform(post(GROUPS_PATH)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CREATE_BODY))
                .andExpect(status().isUnauthorized());
    }

    // ── POST 作成 / GET / cancel は @PreAuthorize なし（Service 層判定）──

    @Test
    @WithMockUser(username = "200", roles = "MEMBER")
    @DisplayName("G-1 契約: MEMBER の POST 作成 → 201・グループ契約形（groupId/slotCount/reservations）")
    void member_createGroup_created() throws Exception {
        stubNonAdmin(MEMBER_USER_ID);
        given(groupService.createGroup(eq(TEAM_ID), anyLong(), any())).willReturn(sampleGroupResponse());

        mockMvc.perform(post(GROUPS_PATH)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CREATE_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.groupId").value(GROUP_ID.toString()))
                .andExpect(jsonPath("$.data.slotCount").value(2))
                .andExpect(jsonPath("$.data.reservations[0].isGroupPrimary").value(true))
                .andExpect(jsonPath("$.data.reservations[1].isGroupPrimary").value(false));
    }

    @Test
    @WithMockUser(username = "200", roles = "MEMBER")
    @DisplayName("契約: MEMBER の cancel → 200（本人判定は Service 層）")
    void member_cancelGroup_ok() throws Exception {
        stubNonAdmin(MEMBER_USER_ID);
        given(groupService.cancelGroup(eq(TEAM_ID), any(UUID.class), anyLong(), any()))
                .willReturn(new ReservationGroupCancelResponse(
                        GROUP_ID, "CANCELLED", LocalDateTime.now(), "USER", 2));

        mockMvc.perform(post(GROUPS_PATH + "/" + GROUP_ID + "/cancel")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cancelReason\":\"予定変更\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cancelledCount").value(2))
                .andExpect(jsonPath("$.data.cancelledBy").value("USER"));
    }

    // ── エラーコード HTTP マッピング（§9）───────────────────────

    @Test
    @WithMockUser(username = "200", roles = "MEMBER")
    @DisplayName("G-2 契約: RESERVATION_039（確保失敗）→ 409 Conflict")
    void createGroup_slotUnavailable_conflict() throws Exception {
        stubNonAdmin(MEMBER_USER_ID);
        given(groupService.createGroup(eq(TEAM_ID), anyLong(), any()))
                .willThrow(new BusinessException(ReservationErrorCode.GROUP_SLOT_UNAVAILABLE));

        mockMvc.perform(post(GROUPS_PATH)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CREATE_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RESERVATION_039"));
    }

    @Test
    @WithMockUser(username = "200", roles = "MEMBER")
    @DisplayName("G-12 契約: RESERVATION_040（不存在/権限なし）→ 404 Not Found（存在秘匿）")
    void getGroup_notFound() throws Exception {
        stubNonAdmin(MEMBER_USER_ID);
        given(groupService.getGroup(eq(TEAM_ID), any(UUID.class), anyLong()))
                .willThrow(new BusinessException(ReservationErrorCode.GROUP_NOT_FOUND));

        mockMvc.perform(get(GROUPS_PATH + "/" + GROUP_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESERVATION_040"));
    }

    @Test
    @WithMockUser(username = "200", roles = "MEMBER")
    @DisplayName("G-4 契約: RESERVATION_041（17枠超過）→ 400 Bad Request")
    void createGroup_sizeExceeded_badRequest() throws Exception {
        stubNonAdmin(MEMBER_USER_ID);
        given(groupService.createGroup(eq(TEAM_ID), anyLong(), any()))
                .willThrow(new BusinessException(ReservationErrorCode.GROUP_SIZE_EXCEEDED));

        mockMvc.perform(post(GROUPS_PATH)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CREATE_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("RESERVATION_041"));
    }

    @Test
    @WithMockUser(username = "200", roles = "MEMBER")
    @DisplayName("G-5 契約: RESERVATION_043（提供不可ライン）→ 400 Bad Request")
    void createGroup_menuLineNotOffered_badRequest() throws Exception {
        stubNonAdmin(MEMBER_USER_ID);
        given(groupService.createGroup(eq(TEAM_ID), anyLong(), any()))
                .willThrow(new BusinessException(ReservationErrorCode.GROUP_MENU_LINE_NOT_OFFERED));

        mockMvc.perform(post(GROUPS_PATH)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CREATE_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("RESERVATION_043"));
    }

    @Test
    @WithMockUser(username = "200", roles = "MEMBER")
    @DisplayName("G-3 契約: RESERVATION_038（非連続等）→ 400 Bad Request")
    void createGroup_notConsecutive_badRequest() throws Exception {
        stubNonAdmin(MEMBER_USER_ID);
        given(groupService.createGroup(eq(TEAM_ID), anyLong(), any()))
                .willThrow(new BusinessException(ReservationErrorCode.SLOT_LINE_MISMATCH));

        mockMvc.perform(post(GROUPS_PATH)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CREATE_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("RESERVATION_038"));
    }
}
