package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.api.dto.EntitlementSummaryResponse;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * F20.1: スコープの権利サマリ API（設計書 02 §2.2）。
 *
 * <p>認可（03 §1）: {@code me}＝本人固定（scopeId をパスで受けない）／
 * {@code teams/{teamId}}＝当該チームのメンバー以上／{@code organizations/{orgId}}＝当該組織のメンバー以上。
 * 閲覧はメンバー可・契約変更は ADMIN のみ（別 Controller）。</p>
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "課金 - 権利サマリ", description = "F20.1 スコープの権利サマリ")
@RequiredArgsConstructor
public class BillingEntitlementSummaryController {

    private final BillingEntitlementQueryService entitlementQueryService;

    @GetMapping("/me/entitlements")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "自分の権利サマリ", description = "USER スコープ（scopeId=本人固定）の契約・有効機能を返す。")
    public ResponseEntity<ApiResponse<EntitlementSummaryResponse>> me() {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(
                entitlementQueryService.getSummary(EntitlementScopeKind.USER, userId)));
    }

    @GetMapping("/teams/{teamId}/entitlements")
    @PreAuthorize("@accessGuard.isScopeMember(authentication, #teamId, 'TEAM')")
    @Operation(summary = "チームの権利サマリ", description = "当該チームのメンバー以上が閲覧可。")
    public ResponseEntity<ApiResponse<EntitlementSummaryResponse>> team(@PathVariable Long teamId) {
        return ResponseEntity.ok(ApiResponse.of(
                entitlementQueryService.getSummary(EntitlementScopeKind.TEAM, teamId)));
    }

    @GetMapping("/organizations/{orgId}/entitlements")
    @PreAuthorize("@accessGuard.isScopeMember(authentication, #orgId, 'ORGANIZATION')")
    @Operation(summary = "組織の権利サマリ", description = "当該組織のメンバー以上が閲覧可。")
    public ResponseEntity<ApiResponse<EntitlementSummaryResponse>> organization(@PathVariable Long orgId) {
        return ResponseEntity.ok(ApiResponse.of(
                entitlementQueryService.getSummary(EntitlementScopeKind.ORG, orgId)));
    }
}
