package com.mannschaft.app.dashboard.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.dashboard.dto.ActionRequiredSummaryResponse;
import com.mannschaft.app.dashboard.service.ScopeActionRequiredFacade;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.team.service.TeamService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * F22.1 第二波: {@link DashboardController} の統合「要対応」エンドポイントの契約テスト。
 *
 * <p>本アプリは {@code @EnableMethodSecurity} 未有効のため、コントローラを直接呼び出す方式で
 * GET 200 形状（チーム/組織）と非所属 403 伝播を検証する。{@code SecurityUtils.getCurrentUserId()}
 * は {@code authentication.getName()} を userId として読むため、テストでは "1" を設定する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DashboardController 統合「要対応」エンドポイント 単体テスト")
class DashboardActionRequiredControllerTest {

    @Mock
    private ScopeActionRequiredFacade scopeActionRequiredFacade;

    @Mock
    private TeamService teamService;

    @Mock
    private OrganizationService organizationService;

    @InjectMocks
    private DashboardController controller;

    private static final Long USER_ID = 1L;
    private static final UUID TEAM_UUID = UUID.fromString("00000000-0000-7000-8000-00000000000a");
    private static final Long TEAM_ID = 10L;
    private static final UUID ORG_UUID = UUID.fromString("00000000-0000-7000-8000-000000000014");
    private static final Long ORG_ID = 20L;

    @BeforeEach
    void setUpSecurityContext() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(String.valueOf(USER_ID), null, List.of()));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private ActionRequiredSummaryResponse summary() {
        return ActionRequiredSummaryResponse.builder()
                .circulation(ActionRequiredSummaryResponse.CirculationSection.builder()
                        .unconfirmedCount(2).items(List.of()).build())
                .survey(ActionRequiredSummaryResponse.SurveySection.builder()
                        .unansweredCount(1).items(List.of()).build())
                .attendance(ActionRequiredSummaryResponse.AttendanceSection.builder()
                        .unansweredCount(3).items(List.of()).build())
                .totalActionCount(6)
                .build();
    }

    @Test
    @DisplayName("GET team/{id}/action-required: 200 で集計形状を返す")
    void teamActionRequired_returns200() {
        given(teamService.resolveTeamId(TEAM_UUID)).willReturn(TEAM_ID);
        given(scopeActionRequiredFacade.getActionRequired(USER_ID, "TEAM", TEAM_ID)).willReturn(summary());

        ResponseEntity<ApiResponse<ActionRequiredSummaryResponse>> res =
                controller.getTeamActionRequired(TEAM_UUID);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().getData().totalActionCount()).isEqualTo(6);
        verify(scopeActionRequiredFacade).getActionRequired(USER_ID, "TEAM", TEAM_ID);
    }

    @Test
    @DisplayName("GET organization/{id}/action-required: 200 で集計形状を返す")
    void orgActionRequired_returns200() {
        given(organizationService.resolveOrgId(ORG_UUID)).willReturn(ORG_ID);
        given(scopeActionRequiredFacade.getActionRequired(USER_ID, "ORGANIZATION", ORG_ID)).willReturn(summary());

        ResponseEntity<ApiResponse<ActionRequiredSummaryResponse>> res =
                controller.getOrgActionRequired(ORG_UUID);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().getData().survey().unansweredCount()).isEqualTo(1);
        verify(scopeActionRequiredFacade).getActionRequired(USER_ID, "ORGANIZATION", ORG_ID);
    }

    @Test
    @DisplayName("非所属（checkMembership 403）はそのまま伝播する")
    void nonMemberPropagates403() {
        given(teamService.resolveTeamId(TEAM_UUID)).willReturn(TEAM_ID);
        doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .when(scopeActionRequiredFacade).getActionRequired(USER_ID, "TEAM", TEAM_ID);

        assertThatThrownBy(() -> controller.getTeamActionRequired(TEAM_UUID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.COMMON_002);
    }
}
