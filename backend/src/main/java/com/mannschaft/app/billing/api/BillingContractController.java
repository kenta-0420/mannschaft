package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.api.dto.ChangePlanRequest;
import com.mannschaft.app.billing.api.dto.ContractResponse;
import com.mannschaft.app.billing.api.dto.CreateContractRequest;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * F20.1: 契約 API（PLAN / ADDON の作成・解約・変更・設計書 02 §3）。
 *
 * <p>認可（03 §1・§2）: {@code me}＝本人固定（scopeId をパスで受けず {@code getCurrentUserId()} 固定・
 * IDOR 対策 AC-10）／{@code teams/{teamId}}＝当該チームの ADMIN（DEPUTY 含む）／
 * {@code organizations/{orgId}}＝当該組織の ADMIN。契約の所属スコープ一致（子リソース contractId）は
 * ドメイン層で 404 秘匿の二重防御（{@code ENTITLEMENT_007}）。</p>
 *
 * <p><b>作成は {@code Idempotency-Key} ヘッダ必須</b>（M-1・権利発行の二重押下防止）。欠落時は Spring が
 * {@code MissingRequestHeaderException}→400 で弾く。</p>
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "課金 - 契約", description = "F20.1 PLAN/ADDON 契約の作成・解約・変更")
@RequiredArgsConstructor
public class BillingContractController {

    private final BillingContractApplicationService contractApplicationService;

    // ============================================================
    // USER スコープ（/me・本人固定）
    // ============================================================

    @PostMapping("/me/billing/contracts")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "自分の契約作成", description = "USER スコープ。Idempotency-Key 必須。")
    public ResponseEntity<ApiResponse<ContractResponse>> createForMe(
            @Valid @RequestBody CreateContractRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        Long userId = SecurityUtils.getCurrentUserId();
        ContractResponse body = contractApplicationService.create(
                EntitlementScopeKind.USER, userId, userId, request, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(body));
    }

    @DeleteMapping("/me/billing/contracts/{contractId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "自分の契約解約", description = "USER スコープ。")
    public ResponseEntity<ApiResponse<ContractResponse>> cancelForMe(@PathVariable UUID contractId) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(
                contractApplicationService.cancel(EntitlementScopeKind.USER, userId, contractId, userId)));
    }

    @PutMapping("/me/billing/contracts/{contractId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "自分のプラン変更", description = "USER スコープ。")
    public ResponseEntity<ApiResponse<ContractResponse>> changeForMe(
            @PathVariable UUID contractId, @Valid @RequestBody ChangePlanRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(
                contractApplicationService.changePlan(EntitlementScopeKind.USER, userId, contractId, request, userId)));
    }

    // ============================================================
    // TEAM スコープ（ADMIN）
    // ============================================================

    @PostMapping("/teams/{teamId}/billing/contracts")
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #teamId, 'TEAM')")
    @Operation(summary = "チームの契約作成", description = "TEAM スコープ。ADMIN のみ。Idempotency-Key 必須。")
    public ResponseEntity<ApiResponse<ContractResponse>> createForTeam(
            @PathVariable Long teamId,
            @Valid @RequestBody CreateContractRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        Long operatorUserId = SecurityUtils.getCurrentUserId();
        ContractResponse body = contractApplicationService.create(
                EntitlementScopeKind.TEAM, teamId, operatorUserId, request, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(body));
    }

    @DeleteMapping("/teams/{teamId}/billing/contracts/{contractId}")
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #teamId, 'TEAM')")
    @Operation(summary = "チームの契約解約", description = "TEAM スコープ。ADMIN のみ。")
    public ResponseEntity<ApiResponse<ContractResponse>> cancelForTeam(
            @PathVariable Long teamId, @PathVariable UUID contractId) {
        Long operatorUserId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(
                contractApplicationService.cancel(EntitlementScopeKind.TEAM, teamId, contractId, operatorUserId)));
    }

    @PutMapping("/teams/{teamId}/billing/contracts/{contractId}")
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #teamId, 'TEAM')")
    @Operation(summary = "チームのプラン変更", description = "TEAM スコープ。ADMIN のみ。")
    public ResponseEntity<ApiResponse<ContractResponse>> changeForTeam(
            @PathVariable Long teamId, @PathVariable UUID contractId,
            @Valid @RequestBody ChangePlanRequest request) {
        Long operatorUserId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(contractApplicationService.changePlan(
                EntitlementScopeKind.TEAM, teamId, contractId, request, operatorUserId)));
    }

    // ============================================================
    // ORG スコープ（ADMIN）
    // ============================================================

    @PostMapping("/organizations/{orgId}/billing/contracts")
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #orgId, 'ORGANIZATION')")
    @Operation(summary = "組織の契約作成", description = "ORG スコープ。ADMIN のみ。Idempotency-Key 必須。")
    public ResponseEntity<ApiResponse<ContractResponse>> createForOrg(
            @PathVariable Long orgId,
            @Valid @RequestBody CreateContractRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        Long operatorUserId = SecurityUtils.getCurrentUserId();
        ContractResponse body = contractApplicationService.create(
                EntitlementScopeKind.ORG, orgId, operatorUserId, request, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(body));
    }

    @DeleteMapping("/organizations/{orgId}/billing/contracts/{contractId}")
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #orgId, 'ORGANIZATION')")
    @Operation(summary = "組織の契約解約", description = "ORG スコープ。ADMIN のみ。")
    public ResponseEntity<ApiResponse<ContractResponse>> cancelForOrg(
            @PathVariable Long orgId, @PathVariable UUID contractId) {
        Long operatorUserId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(
                contractApplicationService.cancel(EntitlementScopeKind.ORG, orgId, contractId, operatorUserId)));
    }

    @PutMapping("/organizations/{orgId}/billing/contracts/{contractId}")
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #orgId, 'ORGANIZATION')")
    @Operation(summary = "組織のプラン変更", description = "ORG スコープ。ADMIN のみ。")
    public ResponseEntity<ApiResponse<ContractResponse>> changeForOrg(
            @PathVariable Long orgId, @PathVariable UUID contractId,
            @Valid @RequestBody ChangePlanRequest request) {
        Long operatorUserId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(contractApplicationService.changePlan(
                EntitlementScopeKind.ORG, orgId, contractId, request, operatorUserId)));
    }
}
