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
import com.mannschaft.app.reservation.dto.ReservationLineResponse;
import com.mannschaft.app.reservation.service.EmergencyClosureService;
import com.mannschaft.app.reservation.service.ReservationLineService;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 予約管理エンドポイントの認可ゲート（{@code @PreAuthorize}）が
 * <b>実 HTTP 経路で発火する</b>ことを担保する結合テスト（F03.4 認可漏れ根治・実効性の裏取り）。
 *
 * <p>{@link ReservationAuthorizationDeclarationTest}（宣言 reflection）＋
 * {@code AccessGuardTest}（isScopeAdmin の強制挙動）の二段は「宣言」と「判定ロジック」を担保するが、
 * 「{@code @EnableMethodSecurity} が実際に点火して 403 を返す」配線までは踏まない。
 * 本テストはその最後の一歩を、{@code AuthorizationIntegrationTest} と同じ
 * 「SecurityConfig を最小コンテキストで読み込む（DB/Redis/Flyway 不要）」方式で実 MockMvc 経由で検証する。</p>
 *
 * <p>検証:
 * <ul>
 *   <li>MEMBER（当該チーム非管理者）が管理エンドポイントを叩く → <b>実際に 403</b>
 *       （{@code @PreAuthorize} → {@code AccessGuard.isScopeAdmin} が false → ExceptionTranslationFilter が 403）</li>
 *   <li>ADMIN（isScopeAdmin=true）が叩く → 認可通過（2xx）</li>
 * </ul>
 * 代表として {@code POST /reservation-lines}（ライン追加）と {@code POST /emergency-closures}（緊急休業一括送信）を用いる。</p>
 */
@SpringBootTest(
        classes = ReservationAuthorizationEnforcementTest.MinimalReservationSecurityConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("予約管理API 認可ゲート実発火テスト（@EnableMethodSecurity 点火）")
class ReservationAuthorizationEnforcementTest {

    private static final Long TEAM_ID = 10L;
    /** F03.4.5 §6.3 AC-6-15: 当該管理者が権限を持たない別チーム（IDOR 検証用）。 */
    private static final Long OTHER_TEAM_ID = 99L;
    private static final Long ADMIN_USER_ID = 100L;
    private static final Long MEMBER_USER_ID = 200L;

    private static final String LINES_PATH = "/api/v1/teams/" + TEAM_ID + "/reservation-lines";
    private static final String CLOSURES_PATH = "/api/v1/teams/" + TEAM_ID + "/emergency-closures";
    private static final String SETTINGS_PATH = "/api/v1/teams/" + TEAM_ID + "/reservation-settings";
    private static final String OTHER_TEAM_SETTINGS_PATH =
            "/api/v1/teams/" + OTHER_TEAM_ID + "/reservation-settings";

    private static final String VALID_LINE_BODY = "{\"name\":\"一般予約\",\"displayOrder\":1}";
    private static final String VALID_CLOSURE_BODY = """
            {"startDate":"2026-08-01","endDate":"2026-08-02","reason":"設備点検",
             "subject":"臨時休業のお知らせ","messageBody":"本文です","cancelReservations":false}
            """;

    /**
     * SecurityConfig（{@code @EnableMethodSecurity} を内包）＋予約 2 コントローラのみを最小コンテキストで読み込む。
     * DB / Redis / Flyway / JPA の自動構成を除外し Docker 不要にする。{@code AuthorizationIntegrationTest} と同型。
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
            TeamReservationLineController.class,
            TeamEmergencyClosureController.class,
            // F03.4.5 §6.3 AC-6-15: 予約設定 PATCH（pendingExpireHours）の管理者ゲート実発火検証用。
            ReservationBusinessHourController.class
    })
    static class MinimalReservationSecurityConfig {

        /** SpEL {@code @accessGuard.isScopeAdmin(...)} の解決に必要な実 AccessGuard（判定は mock の AccessControlService に委譲）。 */
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
    private ReservationLineService reservationLineService;

    @MockitoBean
    private EmergencyClosureService emergencyClosureService;

    // ── ReservationBusinessHourController の依存（F03.4.5 §6.3 AC-6-15） ──
    @MockitoBean
    private com.mannschaft.app.reservation.service.ReservationBusinessHourService reservationBusinessHourService;

    @MockitoBean
    private com.mannschaft.app.reservation.service.ReservationTeamSettingService reservationTeamSettingService;

    @MockitoBean
    private com.mannschaft.app.reservation.service.ReservationPolicyService reservationPolicyService;

    @MockitoBean
    private com.mannschaft.app.reservation.service.ReservationSlotTemplateService reservationSlotTemplateService;

    @MockitoBean
    private com.mannschaft.app.auth.service.AuditLogService auditLogService;

    /** ProxyInputContextFilter 依存の JPA ロード防止（AuthorizationIntegrationTest と同様）。 */
    @MockitoBean
    @SuppressWarnings("unused")
    private ProxyInputConsentRepository proxyInputConsentRepository;

    // ────────────────────────────────────────────────────────────
    // MEMBER（非管理者）は 403 — @PreAuthorize が実発火する
    // ────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = "200", roles = "MEMBER")
    @DisplayName("MEMBER が POST /reservation-lines → 403（管理者ゲート発火）")
    void member_createLine_forbidden() throws Exception {
        given(accessControlService.isSystemAdmin(MEMBER_USER_ID)).willReturn(false);
        given(accessControlService.isAdminOrAbove(MEMBER_USER_ID, TEAM_ID, "TEAM")).willReturn(false);

        mockMvc.perform(post(LINES_PATH)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_LINE_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "200", roles = "MEMBER")
    @DisplayName("MEMBER が POST /emergency-closures → 403（管理者ゲート発火）")
    void member_sendClosure_forbidden() throws Exception {
        given(accessControlService.isSystemAdmin(MEMBER_USER_ID)).willReturn(false);
        given(accessControlService.isAdminOrAbove(MEMBER_USER_ID, TEAM_ID, "TEAM")).willReturn(false);

        mockMvc.perform(post(CLOSURES_PATH)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CLOSURE_BODY))
                .andExpect(status().isForbidden());
    }

    // ────────────────────────────────────────────────────────────
    // ADMIN は認可通過（2xx）
    // ────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = "100", roles = "MEMBER")
    @DisplayName("ADMIN（isScopeAdmin=true）が POST /reservation-lines → 201（認可通過）")
    void admin_createLine_created() throws Exception {
        given(accessControlService.isSystemAdmin(ADMIN_USER_ID)).willReturn(false);
        given(accessControlService.isAdminOrAbove(ADMIN_USER_ID, TEAM_ID, "TEAM")).willReturn(true);
        given(reservationLineService.createLine(eq(TEAM_ID), any())).willReturn(sampleLineResponse());

        mockMvc.perform(post(LINES_PATH)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_LINE_BODY))
                .andExpect(status().isCreated());
    }

    // ────────────────────────────────────────────────────────────
    // AC-6-15: 仮押さえ自動失効の設定変更は既存の管理者認可を継承する（IDOR）
    // ────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = "200", roles = "MEMBER")
    @DisplayName("AC-6-15: 非管理者が PATCH /reservation-settings（pendingExpireHours）→ 403")
    void member_updatePendingExpire_forbidden() throws Exception {
        given(accessControlService.isSystemAdmin(MEMBER_USER_ID)).willReturn(false);
        given(accessControlService.isAdminOrAbove(MEMBER_USER_ID, TEAM_ID, "TEAM")).willReturn(false);

        mockMvc.perform(patch(SETTINGS_PATH)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pendingExpireHours\":48}"))
                .andExpect(status().isForbidden());

        // 403 で止まっているので Service まで到達していない（設定は書き換わらない）。
        org.mockito.Mockito.verify(reservationPolicyService, org.mockito.Mockito.never())
                .updatePolicy(any(), any(), any(), any(), any(), any());
    }

    @Test
    @WithMockUser(username = "100", roles = "MEMBER")
    @DisplayName("AC-6-15(IDOR): 自チームの管理者でも他チームの pendingExpireHours は変更できない → 403")
    void admin_ofOtherTeam_cannotUpdatePendingExpire() throws Exception {
        // ADMIN_USER_ID は TEAM_ID の管理者だが、OTHER_TEAM_ID の管理者ではない。
        given(accessControlService.isSystemAdmin(ADMIN_USER_ID)).willReturn(false);
        given(accessControlService.isAdminOrAbove(ADMIN_USER_ID, TEAM_ID, "TEAM")).willReturn(true);
        given(accessControlService.isAdminOrAbove(ADMIN_USER_ID, OTHER_TEAM_ID, "TEAM")).willReturn(false);

        mockMvc.perform(patch(OTHER_TEAM_SETTINGS_PATH)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pendingExpireHours\":1}"))
                .andExpect(status().isForbidden());

        org.mockito.Mockito.verify(reservationPolicyService, org.mockito.Mockito.never())
                .updatePolicy(any(), any(), any(), any(), any(), any());
    }

    @Test
    @WithMockUser(username = "100", roles = "MEMBER")
    @DisplayName("AC-6-15: 当該チームの管理者は pendingExpireHours を変更できる → 200")
    void admin_updatePendingExpire_ok() throws Exception {
        given(accessControlService.isSystemAdmin(ADMIN_USER_ID)).willReturn(false);
        given(accessControlService.isAdminOrAbove(ADMIN_USER_ID, TEAM_ID, "TEAM")).willReturn(true);
        given(reservationPolicyService.updatePolicy(eq(TEAM_ID), any(), any(), any(), any(), any()))
                .willReturn(com.mannschaft.app.reservation.entity.ReservationPolicyEntity.builder()
                        .teamId(TEAM_ID).pendingExpireHours(48).build());
        given(reservationPolicyService.getOrDefault(TEAM_ID))
                .willReturn(com.mannschaft.app.reservation.entity.ReservationPolicyEntity.builder()
                        .teamId(TEAM_ID).pendingExpireHours(48).build());
        given(reservationTeamSettingService.getOrDefault(TEAM_ID))
                .willReturn(com.mannschaft.app.reservation.entity.ReservationTeamSettingEntity.builder()
                        .teamId(TEAM_ID).allowPublicReservation(false).build());
        given(reservationBusinessHourService.hasBusinessHours(TEAM_ID)).willReturn(true);

        mockMvc.perform(patch(SETTINGS_PATH)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pendingExpireHours\":48}"))
                .andExpect(status().isOk());
    }

    private ReservationLineResponse sampleLineResponse() {
        return ReservationLineResponse.builder()
                .id(300L)
                .teamId(TEAM_ID)
                .meta(new ReservationLineResponse.LineMetaDto("一般予約", null, 1, true, null))
                .audit(new ReservationLineResponse.ReservationLineAuditDto(
                        LocalDateTime.now(), LocalDateTime.now()))
                .build();
    }
}
