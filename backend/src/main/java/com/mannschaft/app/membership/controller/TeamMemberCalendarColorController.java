package com.mannschaft.app.membership.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.dto.MemberCalendarColorResponse;
import com.mannschaft.app.membership.dto.UpdateMemberCalendarColorRequest;
import com.mannschaft.app.membership.service.ScopeMemberCalendarSettingService;
import com.mannschaft.app.team.service.TeamService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** ADMIN/DEPUTYの既存認可でチームメンバー色を管理する。 */
@RestController
@RequestMapping("/api/v1/teams/{teamPublicId}/members/{memberUserId}/calendar-color")
@RequiredArgsConstructor
public class TeamMemberCalendarColorController {
    private static final String SCOPE = "TEAM";
    private final TeamService teamService;
    private final AccessControlService accessControlService;
    private final ScopeMemberCalendarSettingService settingService;

    @PatchMapping
    public ResponseEntity<ApiResponse<MemberCalendarColorResponse>> override(
            @PathVariable String teamPublicId, @PathVariable Long memberUserId,
            @Valid @RequestBody UpdateMemberCalendarColorRequest request) {
        Long teamId = teamService.resolveTeamId(teamPublicId);
        accessControlService.checkAdminOrAbove(SecurityUtils.getCurrentUserId(), teamId, SCOPE);
        return ResponseEntity.ok(ApiResponse.of(settingService.override(
                ScopeType.TEAM, teamId, memberUserId, request.calendarColor())));
    }

    @DeleteMapping
    public ResponseEntity<ApiResponse<MemberCalendarColorResponse>> reset(
            @PathVariable String teamPublicId, @PathVariable Long memberUserId) {
        Long teamId = teamService.resolveTeamId(teamPublicId);
        accessControlService.checkAdminOrAbove(SecurityUtils.getCurrentUserId(), teamId, SCOPE);
        return ResponseEntity.ok(ApiResponse.of(settingService.reset(ScopeType.TEAM, teamId, memberUserId)));
    }
}
