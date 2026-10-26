package com.mannschaft.app.membership.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.dto.MemberCalendarColorResponse;
import com.mannschaft.app.membership.dto.UpdateMemberCalendarColorRequest;
import com.mannschaft.app.membership.service.ScopeMemberCalendarSettingService;
import com.mannschaft.app.organization.service.OrganizationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** ADMIN/DEPUTYの既存認可で組織メンバー色を管理する。 */
@RestController
@RequestMapping("/api/v1/organizations/{orgPublicId}/members/{memberUserId}/calendar-color")
@RequiredArgsConstructor
public class OrganizationMemberCalendarColorController {
    private static final String SCOPE = "ORGANIZATION";
    private final OrganizationService organizationService;
    private final AccessControlService accessControlService;
    private final ScopeMemberCalendarSettingService settingService;

    @PatchMapping
    public ResponseEntity<ApiResponse<MemberCalendarColorResponse>> override(
            @PathVariable String orgPublicId, @PathVariable Long memberUserId,
            @Valid @RequestBody UpdateMemberCalendarColorRequest request) {
        Long orgId = organizationService.resolveOrgId(orgPublicId);
        accessControlService.checkAdminOrAbove(SecurityUtils.getCurrentUserId(), orgId, SCOPE);
        return ResponseEntity.ok(ApiResponse.of(settingService.override(
                ScopeType.ORGANIZATION, orgId, memberUserId, request.calendarColor())));
    }

    @DeleteMapping
    public ResponseEntity<ApiResponse<MemberCalendarColorResponse>> reset(
            @PathVariable String orgPublicId, @PathVariable Long memberUserId) {
        Long orgId = organizationService.resolveOrgId(orgPublicId);
        accessControlService.checkAdminOrAbove(SecurityUtils.getCurrentUserId(), orgId, SCOPE);
        return ResponseEntity.ok(ApiResponse.of(settingService.reset(ScopeType.ORGANIZATION, orgId, memberUserId)));
    }
}
