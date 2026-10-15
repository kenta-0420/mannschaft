package com.mannschaft.app.team.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.featuregate.AlwaysReachable;
import com.mannschaft.app.common.featuregate.AlwaysReachableCategory;
import com.mannschaft.app.config.TeamScopeId;
import com.mannschaft.app.team.dto.DeclineOrgInviteRequest;
import com.mannschaft.app.team.dto.TeamOrgAffiliationResponse;
import com.mannschaft.app.team.dto.TeamOrgRestrictionSummaryResponse;
import com.mannschaft.app.team.service.TeamAffiliationAccessGuard;
import com.mannschaft.app.team.service.TeamOrgInviteService;
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

/**
 * チームが受け取った組織からの加盟招待（F01.2.1 §6.5・§10.1・§10.6）。受信招待一覧・承諾・辞退。
 *
 * <p>3本とも、操作者が当該チームの加盟操作者（{@code MANAGE_ORG_AFFILIATION}。チーム ADMIN は常に含む。§3.2）で
 * あることを {@link TeamAffiliationAccessGuard} で先に要求する（TD・TM は付与されていなければ 403。AC-D06）。
 * 他チーム宛ての招待 ID・存在しない ID は区別せず同じ 404 {@code TEAM_070}（AC-D07・K04）。</p>
 */
@RestController
@RequestMapping("/api/v1/teams/{teamSlug}/org-invites")
@Tag(name = "チームが受け取った加盟招待")
@RequiredArgsConstructor
public class TeamOrgInviteController {

    private final TeamAffiliationAccessGuard accessGuard;
    private final TeamOrgInviteService inviteService;

    @GetMapping
    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "受け取った加盟招待の確認は中核の所属管理機能として常時提供する")
    @Operation(summary = "受信した招待の一覧（チームの加盟操作者）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功（招待日時の降順）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "加盟操作権限なし")
    public ResponseEntity<PagedResponse<TeamOrgAffiliationResponse>> listReceivedInvites(
            @PathVariable("teamSlug") TeamScopeId teamId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        accessGuard.requireOperator(SecurityUtils.getCurrentUserId(), teamId.value());
        return ResponseEntity.ok(inviteService.listReceivedInvites(teamId.value(), page, size));
    }

    @PostMapping("/{membershipId}/accept")
    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "受け取った加盟招待の承諾は中核の所属管理機能として常時提供する")
    @Operation(summary = "招待の承諾（チームの加盟操作者）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "承諾成功（加盟が ACTIVE になる）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "加盟操作権限なし")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "TEAM_070（存在しない ID・他チームの ID・処理済みで消えた行は同じ応答）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
            description = "TEAM_071（既に ACTIVE・申請の行など、承諾の前提と違う状態）/ アーカイブ済み")
    public ResponseEntity<ApiResponse<TeamOrgAffiliationResponse>> accept(
            @PathVariable("teamSlug") TeamScopeId teamId,
            @PathVariable("membershipId") Long membershipId) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessGuard.requireOperator(userId, teamId.value());
        return ResponseEntity.ok(ApiResponse.of(inviteService.accept(teamId.value(), userId, membershipId)));
    }

    @PostMapping("/{membershipId}/reject")
    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "受け取った加盟招待の辞退は中核の所属管理機能として常時提供する")
    @Operation(summary = "招待の辞退（チームの加盟操作者。block=true でこの組織からの招待を止める）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "辞退成功（記録された制限を返す）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "加盟操作権限なし")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "TEAM_070（存在しない ID・他チームの ID・処理済みで消えた行は同じ応答）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
            description = "TEAM_071（既に ACTIVE・申請の行など、辞退の前提と違う状態）")
    public ResponseEntity<ApiResponse<TeamOrgRestrictionSummaryResponse>> decline(
            @PathVariable("teamSlug") TeamScopeId teamId,
            @PathVariable("membershipId") Long membershipId,
            @RequestBody(required = false) DeclineOrgInviteRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessGuard.requireOperator(userId, teamId.value());
        Boolean block = request == null ? null : request.block();
        return ResponseEntity.ok(ApiResponse.of(inviteService.decline(teamId.value(), userId, membershipId, block)));
    }
}
