package com.mannschaft.app.template.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.team.service.TeamService;
import com.mannschaft.app.template.FeatureDisplayScope;
import com.mannschaft.app.template.dto.FeatureDisplayPoliciesResponse;
import com.mannschaft.app.template.dto.UpdateFeatureDisplayPoliciesRequest;
import com.mannschaft.app.template.service.ScopeFeatureDisplayPolicyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** チームの一般機能表示設定。生存scope解決をSYSTEM_ADMIN短絡より先に行う。 */
@RestController
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@RequestMapping("/api/v1/teams/{slug}/feature-display-policies")
@ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "入力不正・未知または重複キー"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "認証が必要"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "対象scopeの権限不足"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "対象scopeが存在しない・削除済み")
})
public class TeamFeatureDisplayPolicyController {
    private final TeamService teams;
    private final ScopeFeatureDisplayPolicyService policies;

    @GetMapping
    @Operation(summary = "チームの一般機能表示設定取得", description = "所属SUPPORTER以上。未保存キーはtrue/true。認可には影響しない。")
    public ApiResponse<FeatureDisplayPoliciesResponse> getSettings(@PathVariable String slug) {
        Long scopeId = teams.resolveTeamId(slug);
        return ApiResponse.of(policies.getSettings(SecurityUtils.getCurrentUserId(), FeatureDisplayScope.TEAM, scopeId));
    }

    @PutMapping
    @Operation(summary = "チームの一般機能表示設定更新", description = "当該ADMINまたはSYSTEM_ADMIN専用。指定キーのみ更新し全件を返す。")
    public ApiResponse<FeatureDisplayPoliciesResponse> updateSettings(@PathVariable String slug,
            @Valid @RequestBody UpdateFeatureDisplayPoliciesRequest request) {
        Long scopeId = teams.resolveTeamId(slug);
        return ApiResponse.of(policies.updateSettings(SecurityUtils.getCurrentUserId(), FeatureDisplayScope.TEAM, scopeId, request));
    }
}
