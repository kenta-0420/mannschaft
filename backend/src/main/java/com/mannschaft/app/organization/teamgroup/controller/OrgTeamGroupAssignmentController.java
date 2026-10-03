package com.mannschaft.app.organization.teamgroup.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.featuregate.AlwaysReachable;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.organization.OrgErrorCode;
import com.mannschaft.app.common.featuregate.AlwaysReachableCategory;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.organization.teamgroup.dto.AssignTeamGroupRequest;
import com.mannschaft.app.organization.teamgroup.dto.BulkAssignTeamGroupRequest;
import com.mannschaft.app.organization.teamgroup.dto.BulkAssignTeamGroupResponse;
import com.mannschaft.app.team.dto.TeamOrgAffiliationResponse;
import com.mannschaft.app.team.service.TeamOrgGroupAssignmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 加盟チームのグループ割当コントローラー（F01.2.1 §7.4・§10.8）。単体割当と一括割当。
 *
 * <p><b>認可（§3.1）</b>: <b>組織 ADMIN のみ</b>（DEPUTY_ADMIN・MEMBER・SYSTEM_ADMIN・他組織の ADMIN・組織に属さない人は 403）。
 * 認可の順序は 認証（401）→ 組織の存在と可視性（404。見えない組織は存在しない組織と同じ応答）→ 権限（403。SYSTEM_ADMIN は他のロールを兼ねていても 403）→ 入力検証（400）→ グループ・チームの存在と状態（404/409）。
 * 認可判定は各エンドポイント本体で {@link AccessControlService} を直接呼ぶ（認可番人が直接呼び出しを検査するため）。</p>
 *
 * <p>他組織・削除済み・不在のグループ ID は区別せず 404 {@code ORG_064}、その組織の ACTIVE 加盟でないチーム
 * （存在しない・他組織・PENDING）は区別せず 404 {@code TEAM_070}（存在オラクルを作らない）。
 * グループ機能 off の組織では 409 {@code ORG_067}。一括割当は 1 分あたり 20 件/ユーザーに制限する
 * （{@code OrgTeamGroupAssignmentRateLimitFilter}。§10.10）。</p>
 */
@RestController
@RequestMapping("/api/v1/organizations")
@Tag(name = "チームグループ割当")
@RequiredArgsConstructor
public class OrgTeamGroupAssignmentController {

    private static final String SCOPE_TYPE = "ORGANIZATION";

    private final TeamOrgGroupAssignmentService assignmentService;
    private final OrganizationService organizationService;
    private final AccessControlService accessControlService;
    private final ContentVisibilityChecker contentVisibilityChecker;

    /**
     * 1チームのグループ割当を変更する（組織 ADMIN のみ。groupId=null で未分類）。
     */
    @PutMapping("/{slug}/teams/{teamSlug}/team-group")
    @Operation(summary = "加盟チームのグループ割当（単体）")
    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "組織 ADMIN が加盟チームの構成を整える操作。グループ機能の on/off は ORG_067 で業務判定し、feature gate の対象にしない")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "割当成功（更新後の加盟）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "組織 ADMIN ではない")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "ORG_064: グループなし（他組織・削除済みも同じ）/ TEAM_070: その組織の ACTIVE 加盟ではないチーム")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "ORG_067: 機能無効")
    public ResponseEntity<ApiResponse<TeamOrgAffiliationResponse>> assignOne(
            @PathVariable String slug,
            @PathVariable String teamSlug,
            @RequestBody AssignTeamGroupRequest req) {
        Long userId = SecurityUtils.getCurrentUserId();
        Long orgId = organizationService.resolveOrgId(slug);
        requireVisibleOrganization(userId, orgId);
        // SYSTEM_ADMIN は組織 ADMIN を兼ねていても書き込めない（§3.1・AC-F12。閲覧のみ）
        if (accessControlService.isSystemAdmin(userId) || !accessControlService.isAdmin(userId, orgId, SCOPE_TYPE)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        return ResponseEntity.ok(ApiResponse.of(
                assignmentService.assignOne(orgId, teamSlug, req.groupId(), userId)));
    }

    /**
     * 複数チームのグループ割当をまとめて変更する（組織 ADMIN のみ。全部か無しか）。
     */
    @PutMapping("/{slug}/team-group-assignments")
    @Operation(summary = "加盟チームのグループ割当（一括）")
    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "組織 ADMIN が加盟チームの構成を整える操作。グループ機能の on/off は ORG_067 で業務判定し、feature gate の対象にしない")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "割当成功（updatedCount）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
            description = "ORG_069: その組織の ACTIVE 加盟でないチームを含む（何も更新しない）/ teamSlugs が空・欠落・501 件以上")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "組織 ADMIN ではない")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "ORG_064: グループなし（他組織・削除済みも同じ）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "ORG_067: 機能無効")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "一括割当は 20 件/分/ユーザー")
    public ResponseEntity<ApiResponse<BulkAssignTeamGroupResponse>> assignBulk(
            @PathVariable String slug,
            @RequestBody BulkAssignTeamGroupRequest req) {
        Long userId = SecurityUtils.getCurrentUserId();
        Long orgId = organizationService.resolveOrgId(slug);
        requireVisibleOrganization(userId, orgId);
        // SYSTEM_ADMIN は組織 ADMIN を兼ねていても書き込めない（§3.1・AC-F12。閲覧のみ）
        if (accessControlService.isSystemAdmin(userId) || !accessControlService.isAdmin(userId, orgId, SCOPE_TYPE)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        int updatedCount = assignmentService.assignBulk(orgId, req.groupId(), req.teamSlugs(), userId);
        return ResponseEntity.ok(ApiResponse.of(new BulkAssignTeamGroupResponse(updatedCount)));
    }

    /**
     * 閲覧者から見えない組織は、存在しない組織と同じ 404 {@code ORG_001} にする（存在オラクルを作らない。§10 認可の順序）。
     * 可視性は F00 の ORGANIZATION ラダーに委譲する。
     */
    private void requireVisibleOrganization(Long userId, Long orgId) {
        if (!contentVisibilityChecker.canView(ReferenceType.ORGANIZATION, orgId, userId)) {
            throw new BusinessException(OrgErrorCode.ORG_001);
        }
    }
}
