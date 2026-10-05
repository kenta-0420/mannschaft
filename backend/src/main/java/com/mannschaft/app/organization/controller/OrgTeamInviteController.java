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
import com.mannschaft.app.team.dto.InviteTeamToOrganizationRequest;
import com.mannschaft.app.team.dto.TeamOrgAffiliationResponse;
import com.mannschaft.app.team.service.TeamOrgInviteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 組織からチームへの加盟招待（F01.2.1 §6.5・§10.1・§10.6）。招待・送信済み一覧・取消。
 *
 * <p><b>認可（§3.1）</b>: 3本とも<b>組織 ADMIN のみ</b>（DEPUTY_ADMIN・MEMBER・他組織の ADMIN・SYSTEM_ADMIN、
 * チーム側で加盟操作権限を持つだけの人は 403。AC-P08）。認可の順序は 認証（401）→ 組織の存在（404）→ 権限（403）→
 * 入力検証（400）→ 状態（409/422）。認可判定は各エンドポイント本体で {@link AccessControlService} を直接呼ぶ。</p>
 *
 * <p>招待先チームが操作者から見えない場合は、存在しない slug と同じ 404 を返す（AC-D09）。
 * 招待の POST には 30件/時/ユーザーのレートリミットが掛かる（{@code TeamOrgInviteRateLimitFilter}。§10.10）。</p>
 */
@RestController
@RequestMapping("/api/v1/organizations/{slug}/team-invites")
@Tag(name = "組織からの加盟招待")
@RequiredArgsConstructor
public class OrgTeamInviteController {

    private static final String SCOPE_TYPE = "ORGANIZATION";

    private final OrganizationService organizationService;
    private final AccessControlService accessControlService;
    private final TeamOrgInviteService inviteService;

    @PostMapping
    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "組織からチームへの加盟招待は中核の所属管理機能として常時提供する")
    @Operation(summary = "チームを加盟に招待（組織 ADMIN）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "招待成功")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
            description = "TEAM_072（グループが選択できない）/ 入力不備")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "組織 ADMIN ではない / TEAM_068（現在招待できない）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "組織・チームが見つからない（見えないチームは存在しない slug と同じ応答）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
            description = "TEAM_065（加盟済み）/ TEAM_066（処理中の申請・招待がある）/ アーカイブ済み")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "招待は30件/時/ユーザー")
    public ResponseEntity<ApiResponse<TeamOrgAffiliationResponse>> invite(
            @PathVariable String slug,
            @RequestBody InviteTeamToOrganizationRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        Long orgId = organizationService.resolveOrgId(slug);
        if (!accessControlService.isAdmin(userId, orgId, SCOPE_TYPE)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        TeamOrgAffiliationResponse response = inviteService.invite(orgId, userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    @GetMapping
    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "送信済みの加盟招待の確認は中核の所属管理機能として常時提供する")
    @Operation(summary = "送信済み招待一覧（組織 ADMIN）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功（招待日時の降順）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "組織 ADMIN ではない")
    public ResponseEntity<PagedResponse<TeamOrgAffiliationResponse>> listSentInvites(
            @PathVariable String slug,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = SecurityUtils.getCurrentUserId();
        Long orgId = organizationService.resolveOrgId(slug);
        if (!accessControlService.isAdmin(userId, orgId, SCOPE_TYPE)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        return ResponseEntity.ok(inviteService.listSentInvites(orgId, page, size));
    }

    @DeleteMapping("/{teamSlug}")
    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "送信済みの加盟招待の取消は中核の所属管理機能として常時提供する")
    @Operation(summary = "招待の取消（組織 ADMIN）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "取消成功")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "組織 ADMIN ではない")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "TEAM_070（招待が無い。存在しないチームの slug も同じ応答）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
            description = "TEAM_071（既に ACTIVE・申請の行など、取消の前提と違う状態）")
    public ResponseEntity<Void> cancel(
            @PathVariable String slug,
            @PathVariable("teamSlug") String teamSlug) {
        Long userId = SecurityUtils.getCurrentUserId();
        Long orgId = organizationService.resolveOrgId(slug);
        if (!accessControlService.isAdmin(userId, orgId, SCOPE_TYPE)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        inviteService.cancel(orgId, userId, teamSlug);
        return ResponseEntity.noContent().build();
    }
}
