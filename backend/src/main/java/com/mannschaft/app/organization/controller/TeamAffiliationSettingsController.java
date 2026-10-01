package com.mannschaft.app.organization.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.organization.dto.TeamAffiliationSettingsResponse;
import com.mannschaft.app.organization.dto.TeamApplicationFormResponse;
import com.mannschaft.app.organization.dto.UpdateTeamAffiliationSettingsRequest;
import com.mannschaft.app.organization.service.TeamAffiliationFacade;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * F01.2.1 §10.2・§10.3: 組織のチーム加盟の申請受付設定と、申請フォームの取得。
 *
 * <p>認可と存在オラクル対策（見えない組織は不在と同じ 404 {@code ORG_001}。権限 403 はその後。入力検証 400 は
 * さらにその後）は {@link TeamAffiliationFacade} が担う。設定の PUT は認可の後に検証するため、
 * {@code @RequestBody} に {@code @Valid} を付けない。</p>
 */
@RestController
@RequestMapping("/api/v1/organizations/{slug}")
@Tag(name = "チーム加盟（F01.2.1）")
@RequiredArgsConstructor
public class TeamAffiliationSettingsController {

    private final TeamAffiliationFacade teamAffiliationFacade;

    @GetMapping("/team-affiliation-settings")
    @Operation(summary = "チーム加盟の申請受付・グループ設定の取得（組織 ADMIN。SYSTEM_ADMIN は閲覧のみ）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "組織は見えるが ADMIN ではない（COMMON_002）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "組織が存在しない・見えない（どちらも ORG_001。区別しない）")
    public ResponseEntity<ApiResponse<TeamAffiliationSettingsResponse>> getSettings(@PathVariable String slug) {
        return ResponseEntity.ok(ApiResponse.of(
                teamAffiliationFacade.getSettings(slug, SecurityUtils.getCurrentUserId())));
    }

    @PutMapping("/team-affiliation-settings")
    @Operation(summary = "チーム加盟の申請受付・グループ設定の更新（組織 ADMIN のみ。全項目の置き換え）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功（GET と同じ形）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "入力不備")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "組織は見えるが ADMIN ではない（SYSTEM_ADMIN を含む。COMMON_002）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "組織が存在しない・見えない（どちらも ORG_001。区別しない）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422",
            description = "REQUIRED の保存条件（グループ機能 on・生存グループ1件以上）を満たさない（ORG_070）")
    public ResponseEntity<ApiResponse<TeamAffiliationSettingsResponse>> updateSettings(
            @PathVariable String slug, @RequestBody UpdateTeamAffiliationSettingsRequest req) {
        return ResponseEntity.ok(ApiResponse.of(
                teamAffiliationFacade.updateSettings(slug, SecurityUtils.getCurrentUserId(), req)));
    }

    @GetMapping("/team-application-form")
    @Operation(summary = "チーム加盟の申請フォームの内容（認証済み・組織が見えること）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "受付 off（TEAM_064）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "組織が存在しない・見えない（どちらも ORG_001。区別しない）")
    public ResponseEntity<ApiResponse<TeamApplicationFormResponse>> getApplicationForm(@PathVariable String slug) {
        return ResponseEntity.ok(ApiResponse.of(
                teamAffiliationFacade.getApplicationForm(slug, SecurityUtils.getCurrentUserId())));
    }
}
