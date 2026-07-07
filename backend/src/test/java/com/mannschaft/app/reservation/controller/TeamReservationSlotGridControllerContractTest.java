package com.mannschaft.app.reservation.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.admin.filter.AdminImpersonationFilter;
import com.mannschaft.app.advertising.campaign.filter.AdPublicEndpointRateLimitFilter;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.ErrorResponse;
import com.mannschaft.app.common.security.AccessGuard;
import com.mannschaft.app.config.JwtAuthenticationFilter;
import com.mannschaft.app.config.SecurityConfig;
import com.mannschaft.app.dashboard.DashboardScopeTabRateLimitFilter;
import com.mannschaft.app.event.EventDelegationRateLimitFilter;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.ProxyInputContextFilter;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.publicview.filter.PublicApiRateLimitFilter;
import com.mannschaft.app.reservation.dto.ReservationGridResponse;
import com.mannschaft.app.reservation.service.ReservationGridService;
import com.mannschaft.app.reservation.service.ReservationSlotService;
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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 空きグリッド API（{@code GET /reservation-slots/grid}）の契約テスト（F03.4.4 試練・実フィルタチェーン経由）。
 *
 * <p>受け入れ条件との対応:
 * <ul>
 *   <li>H-3（B3 観測点）: {@code date} の required 解除 — {@code from/to} 単独呼びが
 *       {@code MissingServletRequestParameterException} の汎用 400 で死なず Service 層に到達して 200</li>
 *   <li>H-3: 両方未指定は Service 層の<b>専用メッセージ</b>の 400（バインド段階の汎用 400 でないことを
 *       {@code error.fieldErrors[0].message} 本文で確認）</li>
 *   <li>H-1（後方互換）: 従来の {@code ?date=} 単独呼びは無変更で 200</li>
 *   <li>H-2/H-4（バインド）: {@code axis}/{@code menuId}(UUID)/{@code from}/{@code to} が
 *       Service 層へ正しく引き渡される</li>
 *   <li>H-10: 未認証は 401（認証層）</li>
 * </ul>
 * {@link ReservationSlotTemplateControllerContractTest} と同じ「SecurityConfig を最小コンテキストで読み込む」方式。
 * XOR・レンジ上限等の検証ロジック本体は {@code ReservationGridServiceExtensionTest}（UT）が実検証する。</p>
 */
@SpringBootTest(
        classes = TeamReservationSlotGridControllerContractTest.MinimalGridSecurityConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("空きグリッドAPI 契約テスト（F03.4.4 date required解除・レンジ・axis/menuIdバインド）")
class TeamReservationSlotGridControllerContractTest {

    private static final Long TEAM_ID = 10L;
    private static final String GRID_PATH = "/api/v1/teams/" + TEAM_ID + "/reservation-slots/grid";
    private static final LocalDate DATE = LocalDate.of(2026, 7, 10);
    private static final UUID MENU_ID = UUID.fromString("0198aaaa-bbbb-7ccc-8ddd-eeeeffff0001");

    private static final String XOR_MESSAGE = "date または from/to のいずれかを指定してください";

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
            TeamReservationSlotController.class
    })
    static class MinimalGridSecurityConfig {

        @Bean
        AccessGuard accessGuard(AccessControlService accessControlService) {
            return new AccessGuard(accessControlService);
        }

        // ── SecurityConfig のフィルタチェーンが要求する Filter 群（既存契約テストと同一） ──

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
    private ReservationSlotService slotService;

    @MockitoBean
    private ReservationGridService gridService;

    /** ProxyInputContextFilter 依存の JPA ロード防止。 */
    @MockitoBean
    @SuppressWarnings("unused")
    private ProxyInputConsentRepository proxyInputConsentRepository;

    private ReservationGridResponse rangeResponse() {
        return ReservationGridResponse.builder()
                .axis("STAFF")
                .days(List.of(new ReservationGridResponse.GridDayDto(DATE, List.of())))
                .build();
    }

    private ReservationGridResponse singleDayResponse() {
        return ReservationGridResponse.builder()
                .date(DATE)
                .columns(List.of())
                .axis("STAFF")
                .build();
    }

    // ────────────────────────────────────────────────────────────
    // H-3（B3 観測点）: from/to 単独呼びが 200 — date の required 解除
    // ────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = "200", roles = "MEMBER")
    @DisplayName("H-3(B3): GET ?from&to（date なし）は MissingServletRequestParameter の汎用400で死なず 200")
    void rangeOnly_ok() throws Exception {
        given(gridService.getGrid(eq(TEAM_ID), anyLong(), isNull(),
                eq(DATE), eq(DATE.plusDays(2)), isNull(), isNull(), isNull()))
                .willReturn(rangeResponse());

        mockMvc.perform(get(GRID_PATH)
                        .param("from", "2026-07-10")
                        .param("to", "2026-07-12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.days").isArray());

        verify(gridService).getGrid(eq(TEAM_ID), anyLong(), isNull(),
                eq(DATE), eq(DATE.plusDays(2)), isNull(), isNull(), isNull());
    }

    // ────────────────────────────────────────────────────────────
    // H-1: 従来の date 単独呼びは無変更で 200
    // ────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = "200", roles = "MEMBER")
    @DisplayName("H-1: GET ?date=（従来呼び）は 200・date/columns が応答に載る（後方互換）")
    void dateOnly_ok() throws Exception {
        given(gridService.getGrid(eq(TEAM_ID), anyLong(), eq(DATE),
                isNull(), isNull(), isNull(), isNull(), isNull()))
                .willReturn(singleDayResponse());

        mockMvc.perform(get(GRID_PATH).param("date", "2026-07-10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.date").value("2026-07-10"))
                .andExpect(jsonPath("$.data.columns").isArray());
    }

    // ────────────────────────────────────────────────────────────
    // H-3: 両方未指定は Service 層の専用メッセージの 400
    // ────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = "200", roles = "MEMBER")
    @DisplayName("H-3: GET（date も from/to も無し）はバインドで死なず Service 到達 → 専用メッセージの 400")
    void neitherDateNorRange_badRequestWithServiceMessage() throws Exception {
        // 実際の XOR 検証は Service 層（UT で実検証）。ここでは「バインドを通過して Service に到達し、
        // Service 層の BusinessException(COMMON_001+fieldErrors) が契約どおり 400 へ写像される」ことを確認する。
        given(gridService.getGrid(eq(TEAM_ID), anyLong(), isNull(),
                isNull(), isNull(), isNull(), isNull(), isNull()))
                .willThrow(new BusinessException(CommonErrorCode.COMMON_001,
                        List.of(new ErrorResponse.FieldError("date", XOR_MESSAGE))));

        mockMvc.perform(get(GRID_PATH))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_001"))
                // バインド段階（MissingServletRequestParameterException）の 400 は fieldErrors が空。
                // Service 層の専用メッセージが本文に載ることが B3 の観測点。
                .andExpect(jsonPath("$.error.fieldErrors[0].message").value(XOR_MESSAGE));

        verify(gridService).getGrid(eq(TEAM_ID), anyLong(), isNull(),
                isNull(), isNull(), isNull(), isNull(), isNull());
    }

    // ────────────────────────────────────────────────────────────
    // H-2/H-4: axis / menuId(UUID) / staffUserIds のバインド
    // ────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = "200", roles = "MEMBER")
    @DisplayName("H-2/H-4: GET ?date&axis=LINE&menuId=UUID が Service へ正しくバインドされる")
    void axisAndMenuId_bound() throws Exception {
        given(gridService.getGrid(eq(TEAM_ID), anyLong(), eq(DATE),
                isNull(), isNull(), eq("LINE"), eq(MENU_ID), isNull()))
                .willReturn(singleDayResponse());

        mockMvc.perform(get(GRID_PATH)
                        .param("date", "2026-07-10")
                        .param("axis", "LINE")
                        .param("menuId", MENU_ID.toString()))
                .andExpect(status().isOk());

        verify(gridService).getGrid(eq(TEAM_ID), anyLong(), eq(DATE),
                isNull(), isNull(), eq("LINE"), eq(MENU_ID), isNull());
    }

    @Test
    @WithMockUser(username = "200", roles = "MEMBER")
    @DisplayName("H-1(互換): 既存 staffUserIds パラメータも従来どおりバインドされる")
    void staffUserIds_bound() throws Exception {
        given(gridService.getGrid(eq(TEAM_ID), anyLong(), eq(DATE),
                isNull(), isNull(), isNull(), isNull(), eq(List.of(50L, 60L))))
                .willReturn(singleDayResponse());

        mockMvc.perform(get(GRID_PATH)
                        .param("date", "2026-07-10")
                        .param("staffUserIds", "50", "60"))
                .andExpect(status().isOk());

        verify(gridService).getGrid(eq(TEAM_ID), anyLong(), eq(DATE),
                isNull(), isNull(), isNull(), isNull(), eq(List.of(50L, 60L)));
    }

    @Test
    @WithMockUser(username = "200", roles = "MEMBER")
    @DisplayName("H-4(バインド): menuId が UUID 形式でない場合は 400（型変換エラー）")
    void menuId_invalidUuid_badRequest() throws Exception {
        mockMvc.perform(get(GRID_PATH)
                        .param("date", "2026-07-10")
                        .param("axis", "LINE")
                        .param("menuId", "not-a-uuid"))
                .andExpect(status().isBadRequest());
    }

    // ────────────────────────────────────────────────────────────
    // H-10: 未認証は 401
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("H-10: 未認証で GET /grid（レンジ呼び含む）→ 401")
    void anonymous_unauthorized() throws Exception {
        mockMvc.perform(get(GRID_PATH).param("date", "2026-07-10"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(GRID_PATH)
                        .param("from", "2026-07-10")
                        .param("to", "2026-07-12"))
                .andExpect(status().isUnauthorized());
    }
}
