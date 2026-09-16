package com.mannschaft.app.template.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.config.TeamScopeId;
import com.mannschaft.app.config.TeamScopeIdConverter;
import com.mannschaft.app.team.service.TeamService;
import com.mannschaft.app.template.dto.ToggleModuleRequest;
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
import org.springframework.format.support.DefaultFormattingConversionService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link TeamModuleController} の単体テスト。
 * slug 解決対応（AC-3/AC-4）を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TeamModuleController 単体テスト")
class TeamModuleControllerTest {

    private static final Long USER_ID = 2L;
    private static final Long TEAM_ID = 20L;
    private static final Long MODULE_ID = 200L;
    private static final Long TEMPLATE_ID = 300L;
    private static final String TEAM_SLUG = "team-000001";

    @Mock private ModuleService moduleService;
    @Mock private TeamService teamService;
    @Mock private AccessControlService accessControlService;

    @InjectMocks
    private TeamModuleController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUpSecurityContext() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(String.valueOf(USER_ID), null, List.of()));
        DefaultFormattingConversionService conversionService = new DefaultFormattingConversionService();
        conversionService.addConverter(new TeamScopeIdConverter(teamService));
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setConversionService(conversionService)
                .build();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // -------------------------------------------------------
    // AC-3: GET /teams/{slug}/modules が slug で 200
    // -------------------------------------------------------

    @Test
    @DisplayName("AC-3a: getTeamModules – slug を渡すと resolveTeamId 経由で 200 を返す")
    void getTeamModules_slugResolves_200() throws Exception {
        given(teamService.resolveTeamId(TEAM_SLUG)).willReturn(TEAM_ID);
        willDoNothing().given(accessControlService).checkMembership(USER_ID, TEAM_ID, "TEAM");
        given(moduleService.getTeamModules(TEAM_ID)).willReturn(List.of());

        mockMvc.perform(get("/api/v1/teams/{slug}/modules", TEAM_SLUG))
                .andExpect(status().isOk());

        verify(teamService).resolveTeamId(TEAM_SLUG);
        verify(accessControlService).checkMembership(USER_ID, TEAM_ID, "TEAM");
        verify(moduleService).getTeamModules(TEAM_ID);
    }

    @Test
    @DisplayName("認可根治: 非メンバーは checkMembership が COMMON_002 を投げ 一覧取得不可（BOLA対策）")
    void getTeamModules_nonMember_403() {
        org.mockito.BDDMockito.willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .given(accessControlService).checkMembership(USER_ID, TEAM_ID, "TEAM");

        assertThatThrownBy(() -> controller.getTeamModules(new TeamScopeId(TEAM_ID)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(CommonErrorCode.COMMON_002));
    }

    // -------------------------------------------------------
    // AC-3: PATCH /teams/{slug}/modules/{moduleId}/toggle が slug で 200
    // -------------------------------------------------------

    @Test
    @DisplayName("AC-3b: toggleTeamModule – ADMIN が slug でトグル成功 200")
    void toggleTeamModule_slugResolves_200() {
        given(accessControlService.isAdmin(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
        ToggleModuleRequest req = new ToggleModuleRequest(MODULE_ID, true);

        ResponseEntity<Void> resp = controller.toggleTeamModule(new TeamScopeId(TEAM_ID), MODULE_ID, req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(accessControlService).isAdmin(USER_ID, TEAM_ID, "TEAM");
        verify(moduleService).toggleTeamModule(TEAM_ID, req, USER_ID);
    }

    @Test
    @DisplayName("認可根治: ADMIN でないユーザーの toggleTeamModule は COMMON_002 で拒否（無認可トグルBOLA対策）")
    void toggleTeamModule_notAdmin_403() {
        given(accessControlService.isAdmin(USER_ID, TEAM_ID, "TEAM")).willReturn(false);
        ToggleModuleRequest req = new ToggleModuleRequest(MODULE_ID, true);

        assertThatThrownBy(() -> controller.toggleTeamModule(new TeamScopeId(TEAM_ID), MODULE_ID, req))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(CommonErrorCode.COMMON_002));
        org.mockito.Mockito.verifyNoInteractions(moduleService);
    }

    // -------------------------------------------------------
    // AC-3: PUT /teams/{slug}/modules/template が slug で 200
    // -------------------------------------------------------

    @Test
    @DisplayName("AC-3c: applyTemplate – ADMIN が slug でテンプレート適用 200")
    void applyTemplate_slugResolves_200() {
        given(accessControlService.isAdmin(USER_ID, TEAM_ID, "TEAM")).willReturn(true);

        ResponseEntity<Void> resp = controller.applyTemplate(new TeamScopeId(TEAM_ID), TEMPLATE_ID);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(accessControlService).isAdmin(USER_ID, TEAM_ID, "TEAM");
        verify(moduleService).applyTemplate(TEAM_ID, TEMPLATE_ID, USER_ID);
    }

    @Test
    @DisplayName("認可根治: ADMIN でないユーザーの applyTemplate は COMMON_002 で拒否（無認可一括適用BOLA対策）")
    void applyTemplate_notAdmin_403() {
        given(accessControlService.isAdmin(USER_ID, TEAM_ID, "TEAM")).willReturn(false);

        assertThatThrownBy(() -> controller.applyTemplate(new TeamScopeId(TEAM_ID), TEMPLATE_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(CommonErrorCode.COMMON_002));
        org.mockito.Mockito.verifyNoInteractions(moduleService);
    }

    // -------------------------------------------------------
    // AC-4: 存在しない slug は resolveTeamId が BusinessException を投げる
    // -------------------------------------------------------

    @Test
    @DisplayName("AC-4: getTeamModules – 存在しない slug は resolveTeamId が TEAM_001 例外")
    void getTeamModules_notFoundSlug_throws() throws Exception {
        given(teamService.resolveTeamId("unknown-slug"))
                .willThrow(new BusinessException(com.mannschaft.app.team.TeamErrorCode.TEAM_001));

        mockMvc.perform(get("/api/v1/teams/{slug}/modules", "unknown-slug"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("CMP-112: 数値チームIDはslug解決を呼ばず認可を通る")
    void getTeamModules_numericTeamId_usesCanonicalScopeId() throws Exception {
        given(moduleService.getTeamModules(TEAM_ID)).willReturn(List.of());

        mockMvc.perform(get("/api/v1/teams/{slug}/modules", TEAM_ID))
                .andExpect(status().isOk());

        verify(teamService, never()).resolveTeamId(String.valueOf(TEAM_ID));
        verify(accessControlService).checkMembership(USER_ID, TEAM_ID, "TEAM");
    }
}
