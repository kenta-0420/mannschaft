package com.mannschaft.app.template.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.team.TeamErrorCode;
import com.mannschaft.app.team.service.TeamService;
import com.mannschaft.app.template.dto.TeamModuleCatalogResponse;
import com.mannschaft.app.template.service.ModuleService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

/**
 * {@link TeamModuleController} のカタログ取得エンドポイント単体テスト。
 * 認可（MEMBER 以上）・slug 解決・404 を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TeamModuleController カタログ取得 単体テスト")
class TeamModuleCatalogControllerTest {

    private static final Long USER_ID = 2L;
    private static final Long TEAM_ID = 20L;
    private static final String TEAM_SLUG = "team-000001";

    @Mock private ModuleService moduleService;
    @Mock private TeamService teamService;
    @Mock private AccessControlService accessControlService;

    @InjectMocks
    private TeamModuleController controller;

    @BeforeEach
    void setUpSecurityContext() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(String.valueOf(USER_ID), null, List.of()));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("MEMBER がカタログを取得 – slug 解決＋認可後 200")
    void getTeamModuleCatalog_member_200() {
        given(teamService.resolveTeamId(TEAM_SLUG)).willReturn(TEAM_ID);
        willDoNothing().given(accessControlService).checkMembership(USER_ID, TEAM_ID, "TEAM");
        TeamModuleCatalogResponse body = TeamModuleCatalogResponse.builder()
                .planLimit(10).enabledCount(0L).hasPaidPlan(false).modules(List.of()).build();
        given(moduleService.getTeamModuleCatalog(TEAM_ID)).willReturn(body);

        ResponseEntity<ApiResponse<TeamModuleCatalogResponse>> resp =
                controller.getTeamModuleCatalog(TEAM_SLUG);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(teamService).resolveTeamId(TEAM_SLUG);
        verify(accessControlService).checkMembership(USER_ID, TEAM_ID, "TEAM");
        verify(moduleService).getTeamModuleCatalog(TEAM_ID);
    }

    @Test
    @DisplayName("AC-9: 非メンバーは checkMembership が COMMON_002 を投げる")
    void getTeamModuleCatalog_nonMember_403() {
        given(teamService.resolveTeamId(TEAM_SLUG)).willReturn(TEAM_ID);
        willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .given(accessControlService).checkMembership(USER_ID, TEAM_ID, "TEAM");

        assertThatThrownBy(() -> controller.getTeamModuleCatalog(TEAM_SLUG))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(CommonErrorCode.COMMON_002));
    }

    @Test
    @DisplayName("AC-8: 存在しない slug は resolveTeamId が TEAM_001 例外")
    void getTeamModuleCatalog_notFoundSlug_throws() {
        given(teamService.resolveTeamId("unknown-slug"))
                .willThrow(new BusinessException(TeamErrorCode.TEAM_001));

        assertThatThrownBy(() -> controller.getTeamModuleCatalog("unknown-slug"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(TeamErrorCode.TEAM_001));
    }
}
