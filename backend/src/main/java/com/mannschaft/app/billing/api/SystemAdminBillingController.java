package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.api.dto.ContractResponse;
import com.mannschaft.app.billing.api.dto.FeatureAdminResponse;
import com.mannschaft.app.billing.api.dto.FeatureUpsertRequest;
import com.mannschaft.app.billing.api.dto.ManualGrantRequest;
import com.mannschaft.app.billing.api.dto.PagedContractResponse;
import com.mannschaft.app.billing.api.dto.PlanAdminResponse;
import com.mannschaft.app.billing.api.dto.PlanFeaturesReplaceRequest;
import com.mannschaft.app.billing.api.dto.PlanUpsertRequest;
import com.mannschaft.app.billing.api.dto.PriceBandsReplaceRequest;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * F20.1: シスアド運用 API（マスタ CRUD・手動付与・契約横断検索・設計書 02 §4）。
 *
 * <p>全 EP {@code @PreAuthorize("hasRole('SYSTEM_ADMIN')")}（03 §1・AC-17）。SecurityConfig の
 * {@code /api/v1/system-admin/**} パスルール（hasRole SYSTEM_ADMIN）とメソッドガードの二重で担保する。
 * {@code fee_policies} シスアド CRUD と同じ設計様式（自然キー PATH・{@code @Builder} DTO）。</p>
 */
@RestController
@RequestMapping("/api/v1/system-admin/billing")
@Tag(name = "システム管理 - 課金", description = "F20.1 プラン/機能マスタ CRUD・手動付与・契約検索（SYSTEM_ADMIN専用）")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
@RequiredArgsConstructor
public class SystemAdminBillingController {

    private final SystemAdminBillingService service;

    // ============================================================
    // プラン CRUD
    // ============================================================

    @GetMapping("/plans")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    @Operation(summary = "プラン一覧", description = "enabled=false 含む全件・sort_order 昇順。")
    public ResponseEntity<ApiResponse<List<PlanAdminResponse>>> listPlans() {
        return ResponseEntity.ok(ApiResponse.of(service.listPlans()));
    }

    @GetMapping("/plans/{planKey}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    @Operation(summary = "プラン詳細", description = "不在は 404（ENTITLEMENT_001）。")
    public ResponseEntity<ApiResponse<PlanAdminResponse>> getPlan(@PathVariable String planKey) {
        return ResponseEntity.ok(ApiResponse.of(service.getPlan(planKey)));
    }

    @PostMapping("/plans/{planKey}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    @Operation(summary = "プラン新規", description = "既存キーは 400（更新は PUT）。")
    public ResponseEntity<ApiResponse<PlanAdminResponse>> createPlan(
            @PathVariable String planKey, @Valid @RequestBody PlanUpsertRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(service.createPlan(planKey, request)));
    }

    @PutMapping("/plans/{planKey}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    @Operation(summary = "プラン更新", description = "不在は 404。")
    public ResponseEntity<ApiResponse<PlanAdminResponse>> updatePlan(
            @PathVariable String planKey, @Valid @RequestBody PlanUpsertRequest request) {
        return ResponseEntity.ok(ApiResponse.of(service.updatePlan(planKey, request)));
    }

    @DeleteMapping("/plans/{planKey}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    @Operation(summary = "プラン削除", description = "参照中（ACTIVE 契約・plan_features 登録）は 409（ENTITLEMENT_012）。")
    public ResponseEntity<Void> deletePlan(@PathVariable String planKey) {
        service.deletePlan(planKey);
        return ResponseEntity.noContent().build();
    }

    // ============================================================
    // 機能カタログ CRUD
    // ============================================================

    @GetMapping("/features")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    @Operation(summary = "機能一覧", description = "enabled=false 含む全件・sort_order 昇順。")
    public ResponseEntity<ApiResponse<List<FeatureAdminResponse>>> listFeatures() {
        return ResponseEntity.ok(ApiResponse.of(service.listFeatures()));
    }

    @GetMapping("/features/{featureKey}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    @Operation(summary = "機能詳細", description = "不在は 404（ENTITLEMENT_002）。")
    public ResponseEntity<ApiResponse<FeatureAdminResponse>> getFeature(@PathVariable String featureKey) {
        return ResponseEntity.ok(ApiResponse.of(service.getFeature(featureKey)));
    }

    @PostMapping("/features/{featureKey}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    @Operation(summary = "機能新規", description = "REVENUE×非営利無料は 400（ENTITLEMENT_010）。既存キーは 400。")
    public ResponseEntity<ApiResponse<FeatureAdminResponse>> createFeature(
            @PathVariable String featureKey, @Valid @RequestBody FeatureUpsertRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(service.createFeature(featureKey, request)));
    }

    @PutMapping("/features/{featureKey}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    @Operation(summary = "機能更新", description = "REVENUE×非営利無料は 400。不在は 404。")
    public ResponseEntity<ApiResponse<FeatureAdminResponse>> updateFeature(
            @PathVariable String featureKey, @Valid @RequestBody FeatureUpsertRequest request) {
        return ResponseEntity.ok(ApiResponse.of(service.updateFeature(featureKey, request)));
    }

    @DeleteMapping("/features/{featureKey}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    @Operation(summary = "機能削除", description = "参照中は 409（ENTITLEMENT_012）。")
    public ResponseEntity<Void> deleteFeature(@PathVariable String featureKey) {
        service.deleteFeature(featureKey);
        return ResponseEntity.noContent().build();
    }

    // ============================================================
    // plan_features / price-bands 一括置換
    // ============================================================

    @PutMapping("/plans/{planKey}/features")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    @Operation(summary = "プラン→機能一括置換", description = "実在しない機能キーは 400（ENTITLEMENT_010）。")
    public ResponseEntity<Void> replacePlanFeatures(
            @PathVariable String planKey, @Valid @RequestBody PlanFeaturesReplaceRequest request) {
        service.replacePlanFeatures(planKey, request);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/plans/{planKey}/price-bands")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    @Operation(summary = "人数バンド一括置換", description = "band_no 昇順・min=前 max+1・最終のみ max=null 違反は 400。")
    public ResponseEntity<Void> replacePriceBands(
            @PathVariable String planKey, @Valid @RequestBody PriceBandsReplaceRequest request) {
        service.replacePriceBands(planKey, request);
        return ResponseEntity.noContent().build();
    }

    // ============================================================
    // 手動付与・契約横断検索
    // ============================================================

    @PostMapping("/grants")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    @Operation(summary = "手動付与", description = "契約行を作って発行（created_by=シスアド・REVENUE イベント非発火）。")
    public ResponseEntity<ApiResponse<ContractResponse>> grant(@Valid @RequestBody ManualGrantRequest request) {
        Long sysAdminUserId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(service.grant(request, sysAdminUserId)));
    }

    @GetMapping("/contracts")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    @Operation(summary = "契約横断検索", description = "scopeKind / scopeId / status は任意フィルタ・contracted_at 降順。")
    public ResponseEntity<ApiResponse<PagedContractResponse>> searchContracts(
            @RequestParam(value = "scopeKind", required = false) String scopeKind,
            @RequestParam(value = "scopeId", required = false) Long scopeId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.of(
                service.searchContracts(scopeKind, scopeId, status, page, size)));
    }
}
