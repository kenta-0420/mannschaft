package com.mannschaft.app.team.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.config.TeamScopeId;
import com.mannschaft.app.config.TeamScopeIdConverter;
import com.mannschaft.app.team.dto.TeamShiftSettingsResponse;
import com.mannschaft.app.team.dto.UpdateTeamShiftSettingsRequest;
import com.mannschaft.app.team.service.TeamService;
import com.mannschaft.app.team.service.TeamShiftSettingsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.format.support.DefaultFormattingConversionService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link TeamShiftSettingsController} の単体テスト（認可根治戦役 Wave6）。
 *
 * <p>粒度の設計根拠: shift ドメインの既定の流儀（参照 = {@code checkMembership} /
 * 変更 = {@code checkAdminOrAbove}。金型は {@code MemberWorkConstraintService} と
 * {@code ShiftScheduleService}）に揃えている。本テストはその粒度を固定する番人である。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TeamShiftSettingsController 単体テスト（Wave6 認可）")
class TeamShiftSettingsControllerTest {

    private static final Long USER_ID = 1L;
    private static final Long TEAM_ID = 10L;

    @Mock private TeamShiftSettingsService settingsService;
    @Mock private TeamService teamService;
    @Mock private AccessControlService accessControlService;

    @InjectMocks
    private TeamShiftSettingsController controller;
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

    @Test
    @DisplayName("getSettings: 200 OK（メンバーであることを checkMembership で確認する）")
    void getSettings_200() {
        given(settingsService.getSettings(TEAM_ID)).willReturn(TeamShiftSettingsResponse.builder().build());
        assertThat(controller.getSettings(new TeamScopeId(TEAM_ID)).getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(accessControlService).checkMembership(USER_ID, TEAM_ID, "TEAM");
    }

    @Test
    @DisplayName("getSettings: 非メンバーは 403 を送出し設定取得本体を呼ばない")
    void getSettings_403_whenNotMember() {
        willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .given(accessControlService).checkMembership(USER_ID, TEAM_ID, "TEAM");
        assertThatThrownBy(() -> controller.getSettings(new TeamScopeId(TEAM_ID)))
                .isInstanceOf(BusinessException.class);
        verify(settingsService, Mockito.never()).getSettings(TEAM_ID);
    }

    @Test
    @DisplayName("updateSettings: 200 OK（checkAdminOrAbove を必ず呼ぶ）")
    void updateSettings_200() {
        UpdateTeamShiftSettingsRequest req = new UpdateTeamShiftSettingsRequest();
        given(settingsService.updateSettings(TEAM_ID, req)).willReturn(TeamShiftSettingsResponse.builder().build());
        assertThat(controller.updateSettings(new TeamScopeId(TEAM_ID), req).getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(accessControlService).checkAdminOrAbove(USER_ID, TEAM_ID, "TEAM");
    }

    @Test
    @DisplayName("updateSettings: ADMIN/DEPUTY でなければ 403 を送出し設定更新本体を呼ばない")
    void updateSettings_403_whenNotAdmin() {
        UpdateTeamShiftSettingsRequest req = new UpdateTeamShiftSettingsRequest();
        willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .given(accessControlService).checkAdminOrAbove(USER_ID, TEAM_ID, "TEAM");
        assertThatThrownBy(() -> controller.updateSettings(new TeamScopeId(TEAM_ID), req))
                .isInstanceOf(BusinessException.class);
        verify(settingsService, Mockito.never()).updateSettings(TEAM_ID, req);
    }

    @Test
    @DisplayName("CMP-112: 数値チームIDはslug解決を呼ばず認可を通る")
    void getSettings_numericTeamId_usesCanonicalScopeId() throws Exception {
        given(settingsService.getSettings(TEAM_ID)).willReturn(TeamShiftSettingsResponse.builder().build());

        mockMvc.perform(get("/api/v1/teams/{slug}/shift-settings", TEAM_ID))
                .andExpect(status().isOk());

        verify(teamService, never()).resolveTeamId(String.valueOf(TEAM_ID));
        verify(accessControlService).checkMembership(USER_ID, TEAM_ID, "TEAM");
    }
}
