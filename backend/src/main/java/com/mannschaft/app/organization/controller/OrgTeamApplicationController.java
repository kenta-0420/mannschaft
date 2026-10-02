package com.mannschaft.app.organization.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.featuregate.AlwaysReachable;
import com.mannschaft.app.common.featuregate.AlwaysReachableCategory;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.team.dto.ApproveTeamApplicationRequest;
import com.mannschaft.app.team.dto.RejectTeamApplicationRequest;
import com.mannschaft.app.team.dto.RejectTeamApplicationResponse;
import com.mannschaft.app.team.dto.TeamOrgAffiliationResponse;
import com.mannschaft.app.team.service.OrgTeamApplicationReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 組織が受信したチームからの加盟申請（F01.2.1 §6.2・§6.3・§10.5・§10.6）。一覧・承認・拒否。
 *
 * <p><b>認可（§3.1）</b>: 3本とも<b>組織 ADMIN のみ</b>。組織 DEPUTY_ADMIN・MEMBER・他組織の ADMIN・SYSTEM_ADMIN、
 * チーム側で加盟操作権限を付与された人（チーム ADMIN を含む）は 403（AC-C05・P08）。認可判定は各エンドポイント本体で
 * {@link AccessControlService} を直接呼ぶ（認可番人が直接呼び出しを検査するため）。
 * 認可の順序は 認証（401）→ 組織の存在（404 {@code ORG_001}）→ 権限（403）→ 入力検証（400）→ 行の所在と状態。</p>
 *
 * <p>他組織の {@code membershipId}・存在しない ID・処理済みで消えた行は区別せず 404 {@code TEAM_070}
 * （存在オラクルを作らない。AC-C06・K04）。受付 off にした後も、届いている申請は承認・拒否できる（§4.3・AC-A07）。</p>
 */
@RestController
@RequestMapping("/api/v1/organizations/{slug}/team-applications")
@Tag(name = "組織の加盟申請（受信）")
@RequiredArgsConstructor
public class OrgTeamApplicationController {

    private static final String SCOPE_TYPE = "ORGANIZATION";

    private final OrganizationService organizationService;
    private final AccessControlService accessControlService;
    private final OrgTeamApplicationReviewService reviewService;

    @GetMapping
    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "組織とチームの加盟関係の管理は組織運営の中核であり、機能フラグで止めない（F01.2.1 §10.6）")
    @Operation(summary = "受信した加盟申請の一覧（組織 ADMIN）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功（申請日時の降順）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "組織 ADMIN ではない")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "ORG_001: 組織が見つからない")
    public ResponseEntity<PagedResponse<TeamOrgAffiliationResponse>> listApplications(
            @PathVariable String slug,
            @RequestParam(required = false) UUID teamGroupId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = SecurityUtils.getCurrentUserId();
        Long orgId = organizationService.resolveOrgId(slug);
        if (!accessControlService.isAdmin(userId, orgId, SCOPE_TYPE)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        return ResponseEntity.ok(reviewService.listApplications(orgId, teamGroupId, page, size));
    }

    @PostMapping("/{membershipId}/approve")
    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "組織とチームの加盟関係の管理は組織運営の中核であり、機能フラグで止めない（F01.2.1 §6.2）")
    @Operation(summary = "加盟申請の承認（組織 ADMIN。グループを確定する）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "承認成功（ACTIVE の加盟）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
            description = "overrideGroup が無い / TEAM_072（指定したグループが選択できない）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "組織 ADMIN ではない")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "ORG_001: 組織が見つからない / TEAM_070: 申請が見つからない（他組織の ID・処理済みも同じ）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
            description = "TEAM_071（既に処理済み）/ 組織・チームがアーカイブ済み")
    public ResponseEntity<ApiResponse<TeamOrgAffiliationResponse>> approve(
            @PathVariable String slug,
            @PathVariable("membershipId") Long membershipId,
            @RequestBody ApproveTeamApplicationRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        Long orgId = organizationService.resolveOrgId(slug);
        if (!accessControlService.isAdmin(userId, orgId, SCOPE_TYPE)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        return ResponseEntity.ok(ApiResponse.of(reviewService.approve(orgId, userId, membershipId, request)));
    }

    @PostMapping("/{membershipId}/reject")
    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "組織とチームの加盟関係の管理は組織運営の中核であり、機能フラグで止めない（F01.2.1 §6.3）")
    @Operation(summary = "加盟申請の拒否（組織 ADMIN。30日の冷却または無期限ブロック）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "拒否成功（記録した制限）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "理由が500文字を超える")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "組織 ADMIN ではない")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "ORG_001: 組織が見つからない / TEAM_070: 申請が見つからない（他組織の ID・処理済みも同じ）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "TEAM_071（既に処理済み）")
    public ResponseEntity<ApiResponse<RejectTeamApplicationResponse>> reject(
            @PathVariable String slug,
            @PathVariable("membershipId") Long membershipId,
            @RequestBody(required = false) RejectTeamApplicationRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        Long orgId = organizationService.resolveOrgId(slug);
        if (!accessControlService.isAdmin(userId, orgId, SCOPE_TYPE)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        return ResponseEntity.ok(ApiResponse.of(reviewService.reject(orgId, userId, membershipId, request)));
    }
}
