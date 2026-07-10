package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.api.dto.EntitlementCheckResponse;
import com.mannschaft.app.billing.api.dto.PlanCatalogResponse;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * F20.1: プランカタログ・単一判定 API（設計書 02 §2.1 / §2.3）。
 *
 * <p>認可: カタログ閲覧＝認証ユーザー（{@code isAuthenticated()}・未認証公開はしない）。
 * check は認証ユーザーで、当該スコープの可読性は Service 層（{@code assertScopeReadable}）で検証する
 * （scopeKind/scopeId をクエリで受けるため・03 §2.2・AC-10）。</p>
 */
@RestController
@RequestMapping("/api/v1/billing")
@Tag(name = "課金 - プランカタログ/判定", description = "F20.1 プランカタログ・単一機能判定")
@RequiredArgsConstructor
public class BillingPlanController {

    private final BillingCatalogQueryService catalogQueryService;
    private final BillingEntitlementQueryService entitlementQueryService;

    @GetMapping("/plans")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "プランカタログ", description = "enabled なプラン・機能を sort_order 昇順で返す。")
    public ResponseEntity<ApiResponse<PlanCatalogResponse>> plans() {
        return ResponseEntity.ok(ApiResponse.of(catalogQueryService.getCatalog()));
    }

    @GetMapping("/entitlements/check")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "単一機能の判定", description =
            "FE ゲート補助（BE が正）。呼び出し元のスコープ可読性を検証してから isEntitled を返す。")
    public ResponseEntity<ApiResponse<EntitlementCheckResponse>> check(
            @RequestParam("scopeKind") String scopeKind,
            @RequestParam("scopeId") Long scopeId,
            @RequestParam("featureKey") String featureKey) {
        Long callerUserId = SecurityUtils.getCurrentUserId();
        EntitlementScopeKind kind = BillingApiSupport.parseScopeKind(scopeKind);
        EntitlementCheckResponse body = entitlementQueryService.check(callerUserId, kind, scopeId, featureKey);
        return ResponseEntity.ok(ApiResponse.of(body));
    }
}
