package com.mannschaft.app.team.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.config.TeamScopeId;
import com.mannschaft.app.team.dto.ApplyToOrganizationRequest;
import com.mannschaft.app.team.dto.TeamOrgAffiliationResponse;
import com.mannschaft.app.team.service.TeamAffiliationAccessGuard;
import com.mannschaft.app.team.service.TeamOrgAffiliationService;
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
 * チームから組織への加盟申請（F01.2.1 §6.1・§6.4・§10.4・§10.6）。
 *
 * <p>3本とも、操作者が当該チームの加盟操作者（{@code MANAGE_ORG_AFFILIATION}。チーム ADMIN は常に含む。§3.2）で
 * あることを {@link TeamAffiliationAccessGuard} で先に要求する。チームの slug が存在しなければ、パス変数の変換で 404。
 * 認可は書き込みより前に行い、非メンバー・権限なしは 403（組織の ADMIN・SYSTEM_ADMIN でも、チームで権限を持たなければ 403）。</p>
 *
 * <p>申請の POST には 10件/時/ユーザーのレートリミットが掛かる
 * （{@code TeamOrgApplicationRateLimitFilter}。§10.10）。</p>
 */
@RestController
@RequestMapping("/api/v1/teams/{teamSlug}/org-applications")
@Tag(name = "チームの加盟申請")
@RequiredArgsConstructor
public class TeamOrgApplicationController {

    private final TeamAffiliationAccessGuard accessGuard;
    private final TeamOrgAffiliationService affiliationService;

    @PostMapping
    @Operation(summary = "組織へ加盟申請（チームの加盟操作者）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "申請成功")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
            description = "TEAM_067（グループ選択が必須）/ TEAM_072（グループが選択できない）/ 入力不備")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "加盟操作権限なし / TEAM_064（受付していない）/ TEAM_068（現在申請できない）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "チーム・組織が見つからない（見えない組織は存在しない slug と同じ応答）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
            description = "TEAM_065（加盟済み）/ TEAM_066（処理中の申請・招待がある）/ アーカイブ済み")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422",
            description = "TEAM_069（同時に申請できるのは10件まで）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "申請は10件/時/ユーザー")
    public ResponseEntity<ApiResponse<TeamOrgAffiliationResponse>> apply(
            @PathVariable("teamSlug") TeamScopeId teamId,
            @RequestBody ApplyToOrganizationRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessGuard.requireOperator(userId, teamId.value());
        TeamOrgAffiliationResponse response = affiliationService.apply(teamId.value(), userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    @GetMapping
    @Operation(summary = "申請中一覧（チームの加盟操作者）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功（申請日時の降順）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "加盟操作権限なし")
    public ResponseEntity<PagedResponse<TeamOrgAffiliationResponse>> listApplications(
            @PathVariable("teamSlug") TeamScopeId teamId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        accessGuard.requireOperator(SecurityUtils.getCurrentUserId(), teamId.value());
        return ResponseEntity.ok(affiliationService.listApplications(teamId.value(), page, size));
    }

    @DeleteMapping("/{membershipId}")
    @Operation(summary = "加盟申請の取下げ（チームの加盟操作者）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "取下げ成功")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "加盟操作権限なし")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "TEAM_070（存在しない ID・他チームの ID・処理済みで消えた行は同じ応答）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
            description = "TEAM_071（既に承認済みなど、取下げの前提と違う状態）")
    public ResponseEntity<Void> withdraw(
            @PathVariable("teamSlug") TeamScopeId teamId,
            @PathVariable("membershipId") Long membershipId) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessGuard.requireOperator(userId, teamId.value());
        affiliationService.withdraw(teamId.value(), userId, membershipId);
        return ResponseEntity.noContent().build();
    }
}
