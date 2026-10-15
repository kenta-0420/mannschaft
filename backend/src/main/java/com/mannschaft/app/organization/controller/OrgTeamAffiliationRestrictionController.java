package com.mannschaft.app.organization.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.featuregate.AlwaysReachable;
import com.mannschaft.app.common.featuregate.AlwaysReachableCategory;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.team.dto.TeamOrgAffiliationRestrictionResponse;
import com.mannschaft.app.team.service.TeamOrgAffiliationRestrictionAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 組織が止めている加盟申請（拒否による冷却・ブロック）の一覧と解除（F01.2.1 §5.4・§10.1）。
 *
 * <p><b>認可（§3.1）</b>: 2本とも<b>組織 ADMIN のみ</b>（AC-P08）。一覧に出るのは組織が拒否で止めた申請
 * （{@code TEAM_APPLY}・{@code REJECTED}）だけで、解除もその行に限る（チーム自身の取下げで止まった申請や、
 * チームが止めた招待は解除できない）。他組織の制限 ID・存在しない ID は同じ 404（AC-C12）。</p>
 */
@RestController
@RequestMapping("/api/v1/organizations/{slug}/team-affiliation-restrictions")
@Tag(name = "組織の加盟申請の制限")
@RequiredArgsConstructor
public class OrgTeamAffiliationRestrictionController {

    private static final String SCOPE_TYPE = "ORGANIZATION";

    private final OrganizationService organizationService;
    private final AccessControlService accessControlService;
    private final TeamOrgAffiliationRestrictionAdminService restrictionAdminService;

    @GetMapping
    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "組織が止めている加盟申請の確認は中核の所属管理機能として常時提供する")
    @Operation(summary = "組織が止めている申請の一覧（組織 ADMIN）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功（記録日時の降順）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "組織 ADMIN ではない")
    public ResponseEntity<PagedResponse<TeamOrgAffiliationRestrictionResponse>> list(
            @PathVariable String slug,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = SecurityUtils.getCurrentUserId();
        Long orgId = organizationService.resolveOrgId(slug);
        if (!accessControlService.isAdmin(userId, orgId, SCOPE_TYPE)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        return ResponseEntity.ok(restrictionAdminService.listByOrganization(orgId, page, size));
    }

    @DeleteMapping("/{restrictionId}")
    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "組織が止めた加盟申請の解除は中核の所属管理機能として常時提供する")
    @Operation(summary = "組織が止めている申請の解除（組織 ADMIN）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "解除成功")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "組織 ADMIN ではない")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "COMMON_005（制限が無い。他組織の ID・解除できない種類の行も同じ応答）")
    public ResponseEntity<Void> lift(
            @PathVariable String slug,
            @PathVariable("restrictionId") UUID restrictionId) {
        Long userId = SecurityUtils.getCurrentUserId();
        Long orgId = organizationService.resolveOrgId(slug);
        if (!accessControlService.isAdmin(userId, orgId, SCOPE_TYPE)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        restrictionAdminService.liftByOrganization(orgId, restrictionId);
        return ResponseEntity.noContent().build();
    }
}
