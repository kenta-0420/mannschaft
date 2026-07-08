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
import com.mannschaft.app.common.GlobalExceptionHandler;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.matching.MatchingErrorCode;
import com.mannschaft.app.matching.dto.CancellationSummaryResponse;
import com.mannschaft.app.matching.dto.MatchRequestCreateResponse;
import com.mannschaft.app.matching.dto.MatchRequestResponse;
import com.mannschaft.app.matching.dto.NgTeamResponse;
import com.mannschaft.app.matching.dto.NotificationPreferenceResponse;
import com.mannschaft.app.matching.dto.ProposalCreateResponse;
import com.mannschaft.app.matching.dto.ProposalResponse;
import com.mannschaft.app.matching.dto.ReviewCreateResponse;
import com.mannschaft.app.matching.dto.TemplateCreateResponse;
import com.mannschaft.app.matching.dto.TemplateResponse;
import com.mannschaft.app.matching.service.MatchNotificationService;
import com.mannschaft.app.matching.service.MatchProposalService;
import com.mannschaft.app.matching.service.MatchRequestService;
import com.mannschaft.app.matching.service.MatchReviewService;
import com.mannschaft.app.matching.service.MatchTemplateService;
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
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
    private static final String TEAM_PROPOSALS_PATH = "/api/v1/teams/" + TEAM_ID + "/matching/proposals";
    private static final String CANCELLATIONS_PATH = "/api/v1/teams/" + TEAM_ID + "/matching/cancellations";

    private static final Long TEMPLATE_ID = 77L;
    private static final Long PROPOSAL_ID = 88L;
    private static final Long REVIEWEE_TEAM_ID = 20L;

    private static final String TEMPLATES_PATH = "/api/v1/teams/" + TEAM_ID + "/matching/templates";
    private static final String TEMPLATE_ITEM_PATH = TEMPLATES_PATH + "/" + TEMPLATE_ID;
    private static final String NOTIFICATION_PREF_PATH =
            "/api/v1/teams/" + TEAM_ID + "/matching/notification-preferences";
    private static final String CREATE_REVIEW_PATH = "/api/v1/matching/reviews";

    private static final String VALID_REQUEST_BODY =
            "{\"title\":\"練習試合募集\",\"activityType\":\"PRACTICE\",\"prefectureCode\":\"13\"}";
    private static final String VALID_PROPOSE_BODY = "{\"message\":\"ぜひお願いします\"}";
    private static final String VALID_NG_BODY = "{\"blockedTeamId\":999,\"reason\":\"マナー違反\"}";
    private static final String VALID_TEMPLATE_BODY = "{\"name\":\"標準テンプレ\",\"templateJson\":\"{}\"}";
    private static final String VALID_NOTIFICATION_BODY = "{\"isEnabled\":true}";
    private static final String VALID_REVIEW_BODY =
            "{\"proposalId\":" + PROPOSAL_ID + ",\"rating\":5,\"comment\":\"良い試合でした\",\"isPublic\":true}";

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
            GlobalExceptionHandler.class,
            MatchRequestController.class,
            MatchProposalController.class,
            NgTeamController.class,
            MatchTemplateController.class,
            MatchNotificationController.class,
            MatchReviewController.class
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

    @MockitoBean
    private MatchTemplateService matchTemplateService;

    @MockitoBean
    private MatchNotificationService matchNotificationService;

    @MockitoBean
    private MatchReviewService matchReviewService;

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

    // ────────────────────────────────────────────────────────────
    // 読み取り系のチーム私的データは所属者のみ（@PreAuthorize isScopeMember・全体一括根治の仕上げ）
    // ────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = "300", roles = "MEMBER")
    @DisplayName("read red→green: 非所属が GET 自チーム応募一覧 → 403（isScopeMember 発火）")
    void outsider_listTeamProposals_forbidden() throws Exception {
        given(accessControlService.isSystemAdmin(OUTSIDER_USER_ID)).willReturn(false);
        given(accessControlService.isMember(OUTSIDER_USER_ID, TEAM_ID, "TEAM")).willReturn(false);

        mockMvc.perform(get(TEAM_PROPOSALS_PATH))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "200", roles = "MEMBER")
    @DisplayName("read: 所属者が GET 自チーム応募一覧 → 200（認可通過）")
    void member_listTeamProposals_ok() throws Exception {
        given(accessControlService.isSystemAdmin(MEMBER_USER_ID)).willReturn(false);
        given(accessControlService.isMember(MEMBER_USER_ID, TEAM_ID, "TEAM")).willReturn(true);
        given(matchProposalService.listTeamProposals(eq(TEAM_ID), any(), any()))
                .willReturn(new PageImpl<>(List.<ProposalResponse>of()));

        mockMvc.perform(get(TEAM_PROPOSALS_PATH))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "300", roles = "MEMBER")
    @DisplayName("read red→green: 非所属が GET キャンセル履歴 → 403（isScopeMember 発火）")
    void outsider_getCancellationHistory_forbidden() throws Exception {
        given(accessControlService.isSystemAdmin(OUTSIDER_USER_ID)).willReturn(false);
        given(accessControlService.isMember(OUTSIDER_USER_ID, TEAM_ID, "TEAM")).willReturn(false);

        mockMvc.perform(get(CANCELLATIONS_PATH))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "200", roles = "MEMBER")
    @DisplayName("read: 所属者が GET キャンセル履歴 → 200（認可通過）")
    void member_getCancellationHistory_ok() throws Exception {
        given(accessControlService.isSystemAdmin(MEMBER_USER_ID)).willReturn(false);
        given(accessControlService.isMember(MEMBER_USER_ID, TEAM_ID, "TEAM")).willReturn(true);
        given(matchProposalService.getCancellationHistory(eq(TEAM_ID), any()))
                .willReturn(new CancellationSummaryResponse(TEAM_ID, 0L, List.of()));

        mockMvc.perform(get(CANCELLATIONS_PATH))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "300", roles = "MEMBER")
    @DisplayName("read red→green: 非所属が GET NGリスト → 403（isScopeMember 発火）")
    void outsider_listNgTeams_forbidden() throws Exception {
        given(accessControlService.isSystemAdmin(OUTSIDER_USER_ID)).willReturn(false);
        given(accessControlService.isMember(OUTSIDER_USER_ID, TEAM_ID, "TEAM")).willReturn(false);

        mockMvc.perform(get(NG_TEAMS_PATH))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "200", roles = "MEMBER")
    @DisplayName("read: 所属者が GET NGリスト → 200（認可通過）")
    void member_listNgTeams_ok() throws Exception {
        given(accessControlService.isSystemAdmin(MEMBER_USER_ID)).willReturn(false);
        given(accessControlService.isMember(MEMBER_USER_ID, TEAM_ID, "TEAM")).willReturn(true);
        given(ngTeamService.listNgTeams(eq(TEAM_ID))).willReturn(List.of());

        mockMvc.perform(get(NG_TEAMS_PATH))
                .andExpect(status().isOk());
    }

    // ════════════════════════════════════════════════════════════
    // 第2弾: テンプレCRUD・通知設定・レビュー（マスター御裁可）
    // ════════════════════════════════════════════════════════════

    // ────────────────────────────────────────────────────────────
    // AC-T: 募集テンプレの作成/更新/削除は管理者/副管理者のみ（isScopeAdmin）、
    //       テンプレ一覧の閲覧は所属者のみ（isScopeMember）
    // ────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = "200", roles = "MEMBER")
    @DisplayName("AC-T red→green: 非管理者が POST テンプレ作成 → 403（isScopeAdmin 発火）")
    void member_createTemplate_forbidden() throws Exception {
        given(accessControlService.isSystemAdmin(MEMBER_USER_ID)).willReturn(false);
        given(accessControlService.isAdminOrAbove(MEMBER_USER_ID, TEAM_ID, "TEAM")).willReturn(false);

        mockMvc.perform(post(TEMPLATES_PATH)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_TEMPLATE_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "100", roles = "MEMBER")
    @DisplayName("AC-T: 管理者が POST テンプレ作成 → 201（認可通過）")
    void admin_createTemplate_created() throws Exception {
        given(accessControlService.isSystemAdmin(ADMIN_USER_ID)).willReturn(false);
        given(accessControlService.isAdminOrAbove(ADMIN_USER_ID, TEAM_ID, "TEAM")).willReturn(true);
        given(matchTemplateService.createTemplate(eq(TEAM_ID), any()))
                .willReturn(new TemplateCreateResponse(TEMPLATE_ID, "標準テンプレ"));

        mockMvc.perform(post(TEMPLATES_PATH)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_TEMPLATE_BODY))
                .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser(username = "200", roles = "MEMBER")
    @DisplayName("AC-T red→green: 非管理者が PUT テンプレ更新 → 403（isScopeAdmin 発火）")
    void member_updateTemplate_forbidden() throws Exception {
        given(accessControlService.isSystemAdmin(MEMBER_USER_ID)).willReturn(false);
        given(accessControlService.isAdminOrAbove(MEMBER_USER_ID, TEAM_ID, "TEAM")).willReturn(false);

        mockMvc.perform(put(TEMPLATE_ITEM_PATH)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_TEMPLATE_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "100", roles = "MEMBER")
    @DisplayName("AC-T: 管理者が PUT テンプレ更新 → 200（認可通過）")
    void admin_updateTemplate_ok() throws Exception {
        given(accessControlService.isSystemAdmin(ADMIN_USER_ID)).willReturn(false);
        given(accessControlService.isAdminOrAbove(ADMIN_USER_ID, TEAM_ID, "TEAM")).willReturn(true);
        given(matchTemplateService.updateTemplate(eq(TEAM_ID), eq(TEMPLATE_ID), any()))
                .willReturn(new TemplateResponse(TEMPLATE_ID, "標準テンプレ", "{}", null));

        mockMvc.perform(put(TEMPLATE_ITEM_PATH)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_TEMPLATE_BODY))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "300", roles = "MEMBER")
    @DisplayName("AC-T red→green: 他チームなりすまし（非管理者）が DELETE テンプレ削除 → 403（IDOR封鎖）")
    void outsider_deleteTemplate_forbidden() throws Exception {
        given(accessControlService.isSystemAdmin(OUTSIDER_USER_ID)).willReturn(false);
        given(accessControlService.isAdminOrAbove(OUTSIDER_USER_ID, TEAM_ID, "TEAM")).willReturn(false);

        mockMvc.perform(delete(TEMPLATE_ITEM_PATH).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "100", roles = "MEMBER")
    @DisplayName("AC-T: 管理者が DELETE テンプレ削除 → 204（認可通過）")
    void admin_deleteTemplate_noContent() throws Exception {
        given(accessControlService.isSystemAdmin(ADMIN_USER_ID)).willReturn(false);
        given(accessControlService.isAdminOrAbove(ADMIN_USER_ID, TEAM_ID, "TEAM")).willReturn(true);

        mockMvc.perform(delete(TEMPLATE_ITEM_PATH).with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser(username = "300", roles = "MEMBER")
    @DisplayName("AC-T read red→green: 非所属が GET テンプレ一覧 → 403（isScopeMember 発火）")
    void outsider_listTemplates_forbidden() throws Exception {
        given(accessControlService.isSystemAdmin(OUTSIDER_USER_ID)).willReturn(false);
        given(accessControlService.isMember(OUTSIDER_USER_ID, TEAM_ID, "TEAM")).willReturn(false);

        mockMvc.perform(get(TEMPLATES_PATH))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "200", roles = "MEMBER")
    @DisplayName("AC-T read: 所属者が GET テンプレ一覧 → 200（認可通過）")
    void member_listTemplates_ok() throws Exception {
        given(accessControlService.isSystemAdmin(MEMBER_USER_ID)).willReturn(false);
        given(accessControlService.isMember(MEMBER_USER_ID, TEAM_ID, "TEAM")).willReturn(true);
        given(matchTemplateService.listTemplates(eq(TEAM_ID))).willReturn(List.of());

        mockMvc.perform(get(TEMPLATES_PATH))
                .andExpect(status().isOk());
    }

    // ────────────────────────────────────────────────────────────
    // AC-N: 推薦通知設定の更新は管理者/副管理者のみ（isScopeAdmin）、
    //       自チーム通知設定の閲覧は所属者のみ（isScopeMember）
    // ────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = "200", roles = "MEMBER")
    @DisplayName("AC-N red→green: 非管理者が PUT 通知設定更新 → 403（isScopeAdmin 発火）")
    void member_updateNotification_forbidden() throws Exception {
        given(accessControlService.isSystemAdmin(MEMBER_USER_ID)).willReturn(false);
        given(accessControlService.isAdminOrAbove(MEMBER_USER_ID, TEAM_ID, "TEAM")).willReturn(false);

        mockMvc.perform(put(NOTIFICATION_PREF_PATH)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_NOTIFICATION_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "100", roles = "MEMBER")
    @DisplayName("AC-N: 管理者が PUT 通知設定更新 → 200（認可通過）")
    void admin_updateNotification_ok() throws Exception {
        given(accessControlService.isSystemAdmin(ADMIN_USER_ID)).willReturn(false);
        given(accessControlService.isAdminOrAbove(ADMIN_USER_ID, TEAM_ID, "TEAM")).willReturn(true);
        given(matchNotificationService.updatePreference(eq(TEAM_ID), any()))
                .willReturn(new NotificationPreferenceResponse(null, null, null, null, true));

        mockMvc.perform(put(NOTIFICATION_PREF_PATH)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_NOTIFICATION_BODY))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "300", roles = "MEMBER")
    @DisplayName("AC-N read red→green: 非所属が GET 通知設定 → 403（isScopeMember 発火）")
    void outsider_getNotification_forbidden() throws Exception {
        given(accessControlService.isSystemAdmin(OUTSIDER_USER_ID)).willReturn(false);
        given(accessControlService.isMember(OUTSIDER_USER_ID, TEAM_ID, "TEAM")).willReturn(false);

        mockMvc.perform(get(NOTIFICATION_PREF_PATH))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "200", roles = "MEMBER")
    @DisplayName("AC-N read: 所属者が GET 通知設定 → 200（認可通過）")
    void member_getNotification_ok() throws Exception {
        given(accessControlService.isSystemAdmin(MEMBER_USER_ID)).willReturn(false);
        given(accessControlService.isMember(MEMBER_USER_ID, TEAM_ID, "TEAM")).willReturn(true);
        given(matchNotificationService.getPreference(eq(TEAM_ID)))
                .willReturn(new NotificationPreferenceResponse(null, null, null, null, false));

        mockMvc.perform(get(NOTIFICATION_PREF_PATH))
                .andExpect(status().isOk());
    }

    // ────────────────────────────────────────────────────────────
    // AC-R: レビュー投稿は「対戦参加チームの管理者/副管理者」のみ。
    //       認可判定は path に teamId が無いためサービス内で行い（proposal 経由でレビュアーチームを解決）、
    //       非参加/非管理者は REVIEW_NOT_PARTICIPANT → 403 にマップされることを実 HTTP 経路で担保する。
    //       レビュアーチーム解決ロジックそのものの網羅は MatchReviewServiceTest（単体）で行う。
    // ────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = "300", roles = "MEMBER")
    @DisplayName("AC-R red→green: 対戦非参加/非管理者が POST レビュー → 403（REVIEW_NOT_PARTICIPANT マップ）")
    void nonParticipant_createReview_forbidden() throws Exception {
        // サービスが認可拒否（対戦参加チームの管理者でない）を検出し REVIEW_NOT_PARTICIPANT を送出。
        // GlobalExceptionHandler が 403 にマップすることを検証（デフォルト 400 のままだと red）。
        willThrow(new BusinessException(MatchingErrorCode.REVIEW_NOT_PARTICIPANT))
                .given(matchReviewService).createReview(eq(OUTSIDER_USER_ID), any());

        mockMvc.perform(post(CREATE_REVIEW_PATH)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REVIEW_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "100", roles = "MEMBER")
    @DisplayName("AC-R: 対戦相手チームの管理者が POST レビュー → 201（currentUserId を渡す配線・認可通過）")
    void participantAdmin_createReview_created() throws Exception {
        // 認可根治の配線検証: コントローラは userID（=100）をサービスへ渡す（従来は userID を teamId と誤用）。
        given(matchReviewService.createReview(eq(ADMIN_USER_ID), any()))
                .willReturn(new ReviewCreateResponse(1L, REVIEWEE_TEAM_ID, (short) 5));

        mockMvc.perform(post(CREATE_REVIEW_PATH)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REVIEW_BODY))
                .andExpect(status().isCreated());
    }
}
