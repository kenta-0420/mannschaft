package com.mannschaft.app.match.controller;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.GlobalExceptionHandler;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.config.OrgScopeIdConverter;
import com.mannschaft.app.config.TeamScopeIdConverter;
import com.mannschaft.app.match.dto.TeamMatchStatsResponse;
import com.mannschaft.app.match.dto.UserMatchStatsResponse;
import com.mannschaft.app.match.service.MatchStatsAggregationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.format.support.DefaultFormattingConversionService;
import org.springframework.format.support.FormattingConversionService;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link MatchStatsController} の HTTP 契約テスト（02 §F.1 / §F.3・03 §C.4 認可）。
 *
 * <p><b>@WebMvcTest+@EnableMethodSecurity の罠回避</b>: SpEL の {@code @accessGuard} を解決できず失敗するため、
 * {@code standaloneSetup}（method security を評価しない）＋ 実 {@link GlobalExceptionHandler} ＋
 * モックした静的 {@link SecurityUtils} で、Controller の認可分岐（本人/他者/F19.1/SUPPORTER ランキング）と
 * ステータス契約のみを検証する（既存 LeagueTransferControllerTest と同方式）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MatchStatsController HTTP 契約テスト")
class MatchStatsControllerContractTest {

    @Mock
    private MatchStatsAggregationService aggregationService;
    @Mock
    private AccessControlService accessControlService;
    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private MatchStatsController controller;

    private MockMvc mockMvc;
    private MockedStatic<SecurityUtils> securityUtils;

    private static final long VIEWER = 1L;
    private static final long ORG = 100L;
    private static final long TEAM = 200L;
    private static final long OTHER_USER = 9L;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setConversionService(scopeConversionService())
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
                .build();
        securityUtils = mockStatic(SecurityUtils.class);
        securityUtils.when(SecurityUtils::getCurrentUserId).thenReturn(VIEWER);
    }

    /**
     * 型付きパス変数 {@code OrgScopeId}/{@code TeamScopeId}（課題 #12・案A）の変換器を登録した
     * 変換サービス。本テストは数値 ID のみを渡すため slug 解決 Service は呼ばれない（高速パス）。
     */
    private FormattingConversionService scopeConversionService() {
        FormattingConversionService cs = new DefaultFormattingConversionService();
        cs.addConverter(new OrgScopeIdConverter(org.mockito.Mockito.mock(
                com.mannschaft.app.organization.service.OrganizationService.class)));
        cs.addConverter(new TeamScopeIdConverter(org.mockito.Mockito.mock(
                com.mannschaft.app.team.service.TeamService.class)));
        return cs;
    }

    @AfterEach
    void tearDown() {
        securityUtils.close();
    }

    // ── 個人統計（本人・チーム横断）────────────────────────────────

    @Test
    @DisplayName("本人のチーム横断統計は 200")
    void selfCrossTeamStats_200() throws Exception {
        given(aggregationService.aggregateUserStats(eq(ORG), eq(VIEWER), isNull(), any(), any(), any(), any()))
                .willReturn(UserMatchStatsResponse.builder().userId(VIEWER).build());

        mockMvc.perform(get("/api/v1/organizations/{orgId}/users/{userId}/match-stats", ORG, VIEWER))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("他者のチーム横断統計（teamId 無し）は 403（本人限定）")
    void otherCrossTeamStats_403() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/{orgId}/users/{userId}/match-stats", ORG, OTHER_USER))
                .andExpect(status().isForbidden());
        // 集計は呼ばれず、認可で弾かれること（漏洩防止）
        verify(aggregationService, never())
                .aggregateUserStats(anyLong(), anyLong(), any(), any(), any(), any(), any());
    }

    // ── 個人統計（team スコープ・他者閲覧）────────────────────────

    @Test
    @DisplayName("他者 team 統計: 対象が当該チーム非所属なら 403")
    void otherTeamStats_targetNotInTeam_403() throws Exception {
        given(accessControlService.isMember(OTHER_USER, TEAM, "TEAM")).willReturn(false);

        mockMvc.perform(get("/api/v1/organizations/{orgId}/users/{userId}/teams/{teamId}/match-stats",
                        ORG, OTHER_USER, TEAM))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("他者 team 統計: 閲覧者が当該チーム ADMIN なら 200（二重検証成立）")
    void otherTeamStats_viewerAdmin_200() throws Exception {
        given(accessControlService.isMember(OTHER_USER, TEAM, "TEAM")).willReturn(true);
        given(accessControlService.isAdminOrAbove(VIEWER, TEAM, "TEAM")).willReturn(true);
        given(aggregationService.aggregateUserStats(eq(ORG), eq(OTHER_USER), eq(TEAM), any(), any(), any(), any()))
                .willReturn(UserMatchStatsResponse.builder().userId(OTHER_USER).build());

        mockMvc.perform(get("/api/v1/organizations/{orgId}/users/{userId}/teams/{teamId}/match-stats",
                        ORG, OTHER_USER, TEAM))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("他者 team 統計: 閲覧者が非ADMINでも対象の F19.1 公開ON＋閲覧者メンバーなら 200")
    void otherTeamStats_publicProfileAndMember_200() throws Exception {
        given(accessControlService.isMember(OTHER_USER, TEAM, "TEAM")).willReturn(true);
        given(accessControlService.isAdminOrAbove(VIEWER, TEAM, "TEAM")).willReturn(false);
        UserEntity target = org.mockito.Mockito.mock(UserEntity.class);
        given(target.isPublicProfileEnabled()).willReturn(true);
        given(userRepository.findById(OTHER_USER)).willReturn(Optional.of(target));
        given(accessControlService.isMember(VIEWER, TEAM, "TEAM")).willReturn(true);
        given(aggregationService.aggregateUserStats(eq(ORG), eq(OTHER_USER), eq(TEAM), any(), any(), any(), any()))
                .willReturn(UserMatchStatsResponse.builder().userId(OTHER_USER).build());

        mockMvc.perform(get("/api/v1/organizations/{orgId}/users/{userId}/teams/{teamId}/match-stats",
                        ORG, OTHER_USER, TEAM))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("他者 team 統計: 非ADMIN＋F19.1非公開なら 403（本人・管理者以外不可）")
    void otherTeamStats_nonAdminNonPublic_403() throws Exception {
        given(accessControlService.isMember(OTHER_USER, TEAM, "TEAM")).willReturn(true);
        given(accessControlService.isAdminOrAbove(VIEWER, TEAM, "TEAM")).willReturn(false);
        UserEntity target = org.mockito.Mockito.mock(UserEntity.class);
        given(target.isPublicProfileEnabled()).willReturn(false);
        given(userRepository.findById(OTHER_USER)).willReturn(Optional.of(target));

        mockMvc.perform(get("/api/v1/organizations/{orgId}/users/{userId}/teams/{teamId}/match-stats",
                        ORG, OTHER_USER, TEAM))
                .andExpect(status().isForbidden());
        verify(aggregationService, never())
                .aggregateUserStats(anyLong(), anyLong(), any(), any(), any(), any(), any());
    }

    // ── チーム統計（メンバー以上・ランキングは MEMBER 以上）──────────

    @Test
    @DisplayName("チーム統計: 非メンバーは 403")
    void teamStats_nonMember_403() throws Exception {
        given(accessControlService.isMember(VIEWER, TEAM, "TEAM")).willReturn(false);

        mockMvc.perform(get("/api/v1/organizations/{orgId}/teams/{teamId}/match-stats", ORG, TEAM))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("チーム統計: SUPPORTER はランキング除外（includeRankings=false で集計呼出し）")
    void teamStats_supporter_excludesRankings() throws Exception {
        given(accessControlService.isMember(VIEWER, TEAM, "TEAM")).willReturn(true);
        // MEMBER 以上ではない（SUPPORTER）
        given(accessControlService.hasRoleOrAbove(VIEWER, TEAM, "TEAM", "MEMBER")).willReturn(false);
        given(aggregationService.aggregateTeamStats(
                eq(ORG), eq(TEAM), any(), any(), any(), any(), eq(false), anyInt()))
                .willReturn(TeamMatchStatsResponse.builder().teamId(TEAM).playerRankings(List.of()).build());

        mockMvc.perform(get("/api/v1/organizations/{orgId}/teams/{teamId}/match-stats", ORG, TEAM))
                .andExpect(status().isOk());

        // includeRankings=false でランキング非表示が選択されたことを検証（SUPPORTER 除外・02 §F.3）
        verify(aggregationService).aggregateTeamStats(
                eq(ORG), eq(TEAM), any(), any(), any(), any(), eq(false), anyInt());
    }

    @Test
    @DisplayName("チーム統計: MEMBER 以上はランキング込み（includeRankings=true）")
    void teamStats_member_includesRankings() throws Exception {
        given(accessControlService.isMember(VIEWER, TEAM, "TEAM")).willReturn(true);
        given(accessControlService.hasRoleOrAbove(VIEWER, TEAM, "TEAM", "MEMBER")).willReturn(true);
        given(aggregationService.aggregateTeamStats(
                eq(ORG), eq(TEAM), any(), any(), any(), any(), eq(true), anyInt()))
                .willReturn(TeamMatchStatsResponse.builder().teamId(TEAM).playerRankings(List.of()).build());

        mockMvc.perform(get("/api/v1/organizations/{orgId}/teams/{teamId}/match-stats", ORG, TEAM))
                .andExpect(status().isOk());

        verify(aggregationService).aggregateTeamStats(
                eq(ORG), eq(TEAM), any(), any(), any(), any(), eq(true), anyInt());
    }
}
