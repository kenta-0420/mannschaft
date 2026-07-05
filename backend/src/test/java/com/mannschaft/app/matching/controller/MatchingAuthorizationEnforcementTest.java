package com.mannschaft.app.matching.controller;

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
import com.mannschaft.app.matching.dto.MatchRequestCreateResponse;
import com.mannschaft.app.matching.dto.MatchRequestResponse;
import com.mannschaft.app.matching.dto.NgTeamResponse;
import com.mannschaft.app.matching.dto.ProposalCreateResponse;
import com.mannschaft.app.matching.service.MatchProposalService;
import com.mannschaft.app.matching.service.MatchRequestService;
import com.mannschaft.app.matching.service.NgTeamService;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F08.1 マッチングの管理系エンドポイントの認可ゲート（{@code @PreAuthorize}）が
 * <b>実 HTTP 経路で発火する</b>ことを担保する結合テスト（認可漏れ根治・IDOR 封鎖の実効性裏取り）。
 *
 * <p>マッチング 3 コントローラ（募集・応募・NGチーム）には {@code @PreAuthorize} が皆無で、
 * URL の {@code {teamId}} を無検証で信用していた（他チームなりすまし可能な IDOR）。
 * 本テストは {@link ReservationAuthorizationEnforcementTest} と同じ
 * 「SecurityConfig を最小コンテキストで読み込む（DB/Redis/Flyway 不要）」方式で、
 * {@code @EnableMethodSecurity} が実際に点火して 403 を返す配線を実 MockMvc 経由で検証する。</p>
 *
 * <p>マスター御裁可: 作成・応募・NG登録＝管理者/副管理者のみ（{@code isScopeAdmin}）、
 * 自チーム募集一覧の閲覧＝所属者のみ（{@code isScopeMember}）。予約スロットと同粒度。</p>
 *
 * <p>red/green の論理:</p>
 * <ul>
 *   <li>実装前（{@code @PreAuthorize} 皆無）: 非管理者/非所属でも 2xx を返す（＝誰でも通る）→ 本テストは red。</li>
 *   <li>実装後: 非管理者/非所属は 403、管理者/所属者は 2xx → green。</li>
 * </ul>
 */
@SpringBootTest(
        classes = MatchingAuthorizationEnforcementTest.MinimalMatchingSecurityConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("マッチング管理API 認可ゲート実発火テスト（@EnableMethodSecurity 点火・IDOR封鎖）")
class MatchingAuthorizationEnforcementTest {

    private static final Long TEAM_ID = 10L;
    private static final Long ADMIN_USER_ID = 100L;
    private static final Long MEMBER_USER_ID = 200L;
    private static final Long OUTSIDER_USER_ID = 300L;
    private static final Long REQUEST_ID = 55L;

    private static final String CREATE_REQUEST_PATH = "/api/v1/teams/" + TEAM_ID + "/matching/requests";
    private static final String LIST_TEAM_REQUESTS_PATH = "/api/v1/teams/" + TEAM_ID + "/matching/requests";
    private static final String PROPOSE_PATH =
            "/api/v1/teams/" + TEAM_ID + "/matching/requests/" + REQUEST_ID + "/propose";
    private static final String NG_TEAMS_PATH = "/api/v1/teams/" + TEAM_ID + "/matching/ng-teams";
    private static final String NG_TEAM_REMOVE_PATH = NG_TEAMS_PATH + "/999";

    private static final String VALID_REQUEST_BODY =
            "{\"title\":\"練習試合募集\",\"activityType\":\"PRACTICE\",\"prefectureCode\":\"13\"}";
    private static final String VALID_PROPOSE_BODY = "{\"message\":\"ぜひお願いします\"}";
    private static final String VALID_NG_BODY = "{\"blockedTeamId\":999,\"reason\":\"マナー違反\"}";

    /**
     * SecurityConfig（{@code @EnableMethodSecurity} を内包）＋マッチング 3 コントローラのみを最小コンテキストで読み込む。
     * DB / Redis / Flyway / JPA の自動構成を除外し Docker 不要にする。{@link ReservationAuthorizationEnforcementTest} と同型。
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
            MatchRequestController.class,
            MatchProposalController.class,
            NgTeamController.class
    })
    static class MinimalMatchingSecurityConfig {

        /** SpEL {@code @accessGuard.isScopeAdmin(...)} / {@code isScopeMember(...)} 解決用の実 AccessGuard（判定は mock へ委譲）。 */
        @Bean
        AccessGuard accessGuard(AccessControlService accessControlService) {
            return new AccessGuard(accessControlService);
        }

        // ── SecurityConfig のフィルタチェーンが要求する Filter 群（ReservationAuthorizationEnforcementTest と同一） ──

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
    private MatchRequestService matchRequestService;

    @MockitoBean
    private MatchProposalService matchProposalService;

    @MockitoBean
    private NgTeamService ngTeamService;

    /** ProxyInputContextFilter 依存の JPA ロード防止（ReservationAuthorizationEnforcementTest と同様）。 */
    @MockitoBean
    @SuppressWarnings("unused")
    private ProxyInputConsentRepository proxyInputConsentRepository;

    // ────────────────────────────────────────────────────────────
    // AC-1: 募集作成は管理者/副管理者のみ（@PreAuthorize isScopeAdmin）
    // ────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = "200", roles = "MEMBER")
    @DisplayName("AC-1 red→green: 非管理者が POST 募集作成 → 403（isScopeAdmin 発火）")
    void member_createRequest_forbidden() throws Exception {
        given(accessControlService.isSystemAdmin(MEMBER_USER_ID)).willReturn(false);
        given(accessControlService.isAdminOrAbove(MEMBER_USER_ID, TEAM_ID, "TEAM")).willReturn(false);

        mockMvc.perform(post(CREATE_REQUEST_PATH)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "100", roles = "MEMBER")
    @DisplayName("AC-1: 管理者が POST 募集作成 → 201（認可通過）")
    void admin_createRequest_created() throws Exception {
        given(accessControlService.isSystemAdmin(ADMIN_USER_ID)).willReturn(false);
        given(accessControlService.isAdminOrAbove(ADMIN_USER_ID, TEAM_ID, "TEAM")).willReturn(true);
        given(matchRequestService.createRequest(eq(TEAM_ID), any()))
                .willReturn(new MatchRequestCreateResponse(REQUEST_ID, "OPEN"));

        mockMvc.perform(post(CREATE_REQUEST_PATH)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST_BODY))
                .andExpect(status().isCreated());
    }

    // ────────────────────────────────────────────────────────────
    // AC-5: 応募・NG登録/解除は管理者/副管理者のみ（@PreAuthorize isScopeAdmin・IDOR封鎖）
    // ────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = "300", roles = "MEMBER")
    @DisplayName("AC-5 red→green: 他チームなりすまし（非管理者）が POST 応募 → 403（IDOR封鎖）")
    void outsider_propose_forbidden() throws Exception {
        given(accessControlService.isSystemAdmin(OUTSIDER_USER_ID)).willReturn(false);
        given(accessControlService.isAdminOrAbove(OUTSIDER_USER_ID, TEAM_ID, "TEAM")).willReturn(false);

        mockMvc.perform(post(PROPOSE_PATH)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_PROPOSE_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "100", roles = "MEMBER")
    @DisplayName("AC-5: 管理者が POST 応募 → 201（認可通過）")
    void admin_propose_created() throws Exception {
        given(accessControlService.isSystemAdmin(ADMIN_USER_ID)).willReturn(false);
        given(accessControlService.isAdminOrAbove(ADMIN_USER_ID, TEAM_ID, "TEAM")).willReturn(true);
        given(matchProposalService.createProposal(eq(TEAM_ID), eq(REQUEST_ID), any()))
                .willReturn(new ProposalCreateResponse(70L, REQUEST_ID, "PENDING"));

        mockMvc.perform(post(PROPOSE_PATH)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_PROPOSE_BODY))
                .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser(username = "300", roles = "MEMBER")
    @DisplayName("AC-5 red→green: 他チームなりすまし（非管理者）が POST NG登録 → 403（IDOR封鎖）")
    void outsider_addNgTeam_forbidden() throws Exception {
        given(accessControlService.isSystemAdmin(OUTSIDER_USER_ID)).willReturn(false);
        given(accessControlService.isAdminOrAbove(OUTSIDER_USER_ID, TEAM_ID, "TEAM")).willReturn(false);

        mockMvc.perform(post(NG_TEAMS_PATH)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_NG_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "100", roles = "MEMBER")
    @DisplayName("AC-5: 管理者が POST NG登録 → 201（認可通過）")
    void admin_addNgTeam_created() throws Exception {
        given(accessControlService.isSystemAdmin(ADMIN_USER_ID)).willReturn(false);
        given(accessControlService.isAdminOrAbove(ADMIN_USER_ID, TEAM_ID, "TEAM")).willReturn(true);
        given(ngTeamService.addNgTeam(eq(TEAM_ID), any()))
                .willReturn(new NgTeamResponse(999L, null));

        mockMvc.perform(post(NG_TEAMS_PATH)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_NG_BODY))
                .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser(username = "300", roles = "MEMBER")
    @DisplayName("AC-5 red→green: 他チームなりすまし（非管理者）が DELETE NG解除 → 403（IDOR封鎖）")
    void outsider_removeNgTeam_forbidden() throws Exception {
        given(accessControlService.isSystemAdmin(OUTSIDER_USER_ID)).willReturn(false);
        given(accessControlService.isAdminOrAbove(OUTSIDER_USER_ID, TEAM_ID, "TEAM")).willReturn(false);

        mockMvc.perform(delete(NG_TEAM_REMOVE_PATH).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "100", roles = "MEMBER")
    @DisplayName("AC-5: 管理者が DELETE NG解除 → 204（認可通過）")
    void admin_removeNgTeam_noContent() throws Exception {
        given(accessControlService.isSystemAdmin(ADMIN_USER_ID)).willReturn(false);
        given(accessControlService.isAdminOrAbove(ADMIN_USER_ID, TEAM_ID, "TEAM")).willReturn(true);

        mockMvc.perform(delete(NG_TEAM_REMOVE_PATH).with(csrf()))
                .andExpect(status().isNoContent());
    }

    // ────────────────────────────────────────────────────────────
    // AC-6: 自チーム募集一覧の閲覧は所属者のみ（@PreAuthorize isScopeMember）
    // ────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = "300", roles = "MEMBER")
    @DisplayName("AC-6 red→green: 非所属が GET 自チーム募集一覧 → 403（isScopeMember 発火）")
    void outsider_listTeamRequests_forbidden() throws Exception {
        given(accessControlService.isSystemAdmin(OUTSIDER_USER_ID)).willReturn(false);
        given(accessControlService.isMember(OUTSIDER_USER_ID, TEAM_ID, "TEAM")).willReturn(false);

        mockMvc.perform(get(LIST_TEAM_REQUESTS_PATH))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "200", roles = "MEMBER")
    @DisplayName("AC-6: 所属者が GET 自チーム募集一覧 → 200（認可通過）")
    void member_listTeamRequests_ok() throws Exception {
        given(accessControlService.isSystemAdmin(MEMBER_USER_ID)).willReturn(false);
        given(accessControlService.isMember(MEMBER_USER_ID, TEAM_ID, "TEAM")).willReturn(true);
        given(matchRequestService.listTeamRequests(eq(TEAM_ID), any()))
                .willReturn(new PageImpl<>(List.<MatchRequestResponse>of()));

        mockMvc.perform(get(LIST_TEAM_REQUESTS_PATH))
                .andExpect(status().isOk());
    }
}
