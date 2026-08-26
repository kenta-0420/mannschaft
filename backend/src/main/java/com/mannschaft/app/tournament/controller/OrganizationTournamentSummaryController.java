package com.mannschaft.app.tournament.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.tournament.dto.OrganizationTournamentSummaryResponse;
import com.mannschaft.app.tournament.service.OrganizationTournamentSummaryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * F08.7.1 / 02 ②: 主催大会サマリ（ORG_TOURNAMENT_SUMMARY ウィジェット）コントローラー。
 *
 * <p>新設エンドポイント {@code GET /api/v1/organizations/{orgId}/tournaments/summary}。
 * 各大会 × 各部の首位・参加数・status を集約して返す（N+1 回避はサービス層）。</p>
 *
 * <p>認可: 組織 MEMBER 以上（設計書 §6 デフォルト min_role = MEMBER に対応）。
 * 非公開（DRAFT）大会の非露出はサービス層で常に除外（§5.3）。</p>
 *
 * <p>path 変数 {@code orgId} は URL 識別子（slug、例 {@code org-000001}）として受け取り、
 * {@link OrganizationService#resolveOrgId(String)} で内部 BIGINT に解決してから認可・サービスへ渡す。
 * フロントエンドのダッシュボードは slug を渡すため、{@code @PathVariable Long} のままだと
 * Spring の型変換に失敗して 400 となり、ウィジェットが空表示になっていた（survey の流儀へ整合）。</p>
 */
@RestController
@Tag(name = "主催大会サマリ", description = "F08.7.1 組織ダッシュボード 主催大会サマリ")
@RequiredArgsConstructor
public class OrganizationTournamentSummaryController {

    private final OrganizationTournamentSummaryService summaryService;
    private final AccessControlService accessControlService;
    private final OrganizationService organizationService;

    @GetMapping("/api/v1/organizations/{orgId}/tournaments/summary")
    @Operation(summary = "主催大会サマリ取得")
    public ResponseEntity<ApiResponse<OrganizationTournamentSummaryResponse>> getSummary(
            @PathVariable String orgId) {
        Long userId = SecurityUtils.getCurrentUserId();
        // slug（URL識別子）を内部 BIGINT に解決してから認可・サービスへ渡す（survey resolveScopeId 流儀）
        Long resolvedOrgId = organizationService.resolveOrgId(orgId);
        // 組織メンバー以上のみ閲覧可能（§6 ORG_TOURNAMENT_SUMMARY デフォルト min_role = MEMBER）
        accessControlService.checkMembership(userId, resolvedOrgId, "ORGANIZATION");
        return ResponseEntity.ok(ApiResponse.of(summaryService.getSummary(resolvedOrgId)));
    }
}
