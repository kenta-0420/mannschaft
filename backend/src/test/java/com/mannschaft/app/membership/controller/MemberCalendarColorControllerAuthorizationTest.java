package com.mannschaft.app.membership.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.dto.MemberCalendarColorResponse;
import com.mannschaft.app.membership.dto.UpdateMemberCalendarColorRequest;
import com.mannschaft.app.membership.service.ScopeMemberCalendarSettingService;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.team.service.TeamService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("メンバーカレンダー色APIの認可境界")
class MemberCalendarColorControllerAuthorizationTest {

    private static final Long ACTOR_ID = 100L;
    private static final Long MEMBER_ID = 200L;

    @Mock private TeamService teamService;
    @Mock private OrganizationService organizationService;
    @Mock private AccessControlService accessControlService;
    @Mock private ScopeMemberCalendarSettingService settingService;

    private MockedStatic<SecurityUtils> securityUtils;

    @BeforeEach
    void setUp() {
        securityUtils = Mockito.mockStatic(SecurityUtils.class);
        securityUtils.when(SecurityUtils::getCurrentUserId).thenReturn(ACTOR_ID);
    }

    @AfterEach
    void tearDown() {
        securityUtils.close();
    }

    @Test
    @DisplayName("チームPATCHは公開IDから解決したスコープで管理者権限を検査する")
    void teamOverrideChecksResolvedScope() {
        when(teamService.resolveTeamId("family")).thenReturn(10L);
        when(settingService.override(ScopeType.TEAM, 10L, MEMBER_ID, "#2563EB"))
                .thenReturn(new MemberCalendarColorResponse(MEMBER_ID, "#2563EB", true));
        var controller = new TeamMemberCalendarColorController(
                teamService, accessControlService, settingService);

        controller.override("family", MEMBER_ID, new UpdateMemberCalendarColorRequest("#2563EB"));

        verify(accessControlService).checkAdminOrAbove(ACTOR_ID, 10L, "TEAM");
        verify(settingService).override(ScopeType.TEAM, 10L, MEMBER_ID, "#2563EB");
    }

    @Test
    @DisplayName("チームPATCHは権限拒否時に色設定へ到達しない")
    void teamOverrideStopsOnDeniedPermission() {
        when(teamService.resolveTeamId("family")).thenReturn(10L);
        doThrow(new AccessDeniedException("denied"))
                .when(accessControlService).checkAdminOrAbove(ACTOR_ID, 10L, "TEAM");
        var controller = new TeamMemberCalendarColorController(
                teamService, accessControlService, settingService);

        assertThatThrownBy(() -> controller.override(
                "family", MEMBER_ID, new UpdateMemberCalendarColorRequest("#2563EB")))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(settingService);
    }

    @Test
    @DisplayName("組織DELETEは公開IDから解決したスコープだけをリセットする")
    void organizationResetChecksResolvedScope() {
        when(organizationService.resolveOrgId("home-org")).thenReturn(20L);
        when(settingService.reset(ScopeType.ORGANIZATION, 20L, MEMBER_ID))
                .thenReturn(new MemberCalendarColorResponse(MEMBER_ID, "#64748B", false));
        var controller = new OrganizationMemberCalendarColorController(
                organizationService, accessControlService, settingService);

        controller.reset("home-org", MEMBER_ID);

        verify(accessControlService).checkAdminOrAbove(ACTOR_ID, 20L, "ORGANIZATION");
        verify(settingService).reset(ScopeType.ORGANIZATION, 20L, MEMBER_ID);
    }
}
