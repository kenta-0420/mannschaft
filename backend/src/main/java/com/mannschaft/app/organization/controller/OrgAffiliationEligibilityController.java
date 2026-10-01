package com.mannschaft.app.organization.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.organization.dto.OrgAffiliationEligibilityResponse;
import com.mannschaft.app.organization.service.TeamAffiliationFacade;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * F01.2.1 §10.3（マスター確定 M3）: 組織の公開ページ・組織シェルで「チームとして加盟を申請」ボタンを出すかの判定。
 *
 * <p><b>常に 200</b> で {@code {canApply: boolean}} を返す。組織が存在しない・見えない・受付 off・
 * 権限のあるチームが無い、のいずれでも同じ false を返し、理由を区別しない（存在オラクルにしない）。
 * レートリミット（60件/分/ユーザー。§10.10）は
 * {@link com.mannschaft.app.organization.filter.OrgAffiliationEligibilityRateLimitFilter} が担う。</p>
 */
@RestController
@Tag(name = "チーム加盟（F01.2.1）")
@RequiredArgsConstructor
public class OrgAffiliationEligibilityController {

    private final TeamAffiliationFacade teamAffiliationFacade;

    @GetMapping("/api/v1/me/org-affiliation-eligibility")
    @Operation(summary = "組織へのチーム加盟申請ボタンを出すか（常に 200。理由は区別しない）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "判定結果")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "レートリミット超過")
    public ResponseEntity<ApiResponse<OrgAffiliationEligibilityResponse>> eligibility(
            @RequestParam String organizationSlug) {
        return ResponseEntity.ok(ApiResponse.of(
                teamAffiliationFacade.eligibility(organizationSlug, SecurityUtils.getCurrentUserId())));
    }
}
