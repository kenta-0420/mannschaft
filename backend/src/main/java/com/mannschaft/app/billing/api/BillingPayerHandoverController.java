package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.api.dto.PayerHandoverAcceptResponse;
import com.mannschaft.app.billing.api.dto.PayerHandoverRequestResponse;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.featuregate.AlwaysReachable;
import com.mannschaft.app.common.featuregate.AlwaysReachableCategory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 柱③-B: 請求担当（payer）引継 API（設計書 billing_payer_handover_design.md §5.6）。
 *
 * <p><b>USER スコープの API は存在しない。</b> USER スコープ契約は契約者本人以外に payer が存在し得ないため
 * 引継の概念自体が無い（設計書 §4.2）。ドメイン層でも {@code HANDOVER_SCOPE_NOT_SUPPORTED} で拒否する。</p>
 *
 * <p><b>認可は二層</b>（設計書 §5.6・AC-11）:</p>
 * <ol>
 *   <li>HTTP 層: {@code @PreAuthorize("@billingAccessGuard.canManage(...)")} で当該スコープの ADMIN
 *       （課金管理権限を明示付与された DEPUTY_ADMIN を含む）以外を 403 で拒否する。</li>
 *   <li>ドメイン層: 書き込みトランザクション内で {@code billingOperationAuthorizer.requireCanManage(...)}
 *       が操作者行をロックしたうえで再判定する（権限剥奪との競合を直列化する）。加えて子リソース
 *       （contractId / handoverRequestId）のスコープ一致を検証し、越境は<b>404 で畳む</b>
 *       （{@code ENTITLEMENT_030}・存在オラクルを残さないため 403 ではなく 404）。</li>
 * </ol>
 *
 * <p>承諾 API のパスにスコープを含めているのは、HTTP 層の第一防衛で対象スコープの ADMIN 判定を行うため。
 * ドメイン層では引継要求が本当にそのスコープに属するかを再検証する（パス改竄による越境の封じ込め）。</p>
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "課金 - 請求担当の引継", description = "柱③-B TEAM/ORG 契約の請求担当（payer）引継の申請・承諾")
@RequiredArgsConstructor
public class BillingPayerHandoverController {

    private final BillingPayerHandoverApplicationService payerHandoverApplicationService;

    // ============================================================
    // TEAM スコープ
    // ============================================================

    @PostMapping("/teams/{teamId}/billing/contracts/{contractId}/payer-handover-requests")
    @PreAuthorize("@billingAccessGuard.canManage(authentication, T(com.mannschaft.app.billing.EntitlementScopeKind).TEAM, #teamId)")
    @Operation(summary = "チーム契約の請求担当引継を申請",
            description = "旧 payer が申請する（承諾型2段の1段目）。旧契約が PAST_DUE または期末が過去の場合は拒否される。")
    public ResponseEntity<ApiResponse<PayerHandoverRequestResponse>> requestForTeam(
            @PathVariable Long teamId, @PathVariable UUID contractId) {
        Long operatorUserId = SecurityUtils.getCurrentUserId();
        PayerHandoverRequestResponse body = payerHandoverApplicationService.request(
                EntitlementScopeKind.TEAM, teamId, contractId, operatorUserId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(body));
    }

    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "退会予定の払い手から請求担当を引き継ぐ操作は、Gate状態にかかわらず到達可能でなければ課金が止まらないため")
    @PostMapping("/teams/{teamId}/billing/payer-handover-requests/{handoverRequestId}/acceptance")
    @PreAuthorize("@billingAccessGuard.canManage(authentication, T(com.mannschaft.app.billing.EntitlementScopeKind).TEAM, #teamId)")
    @Operation(summary = "チーム契約の請求担当引継を承諾",
            description = "新 payer となる他 ADMIN が承諾する（2段目）。支払い方法未登録なら REQUIRES_PAYMENT_METHOD で差し戻す。")
    public ResponseEntity<ApiResponse<PayerHandoverAcceptResponse>> acceptForTeam(
            @PathVariable Long teamId, @PathVariable UUID handoverRequestId) {
        Long operatorUserId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(payerHandoverApplicationService.accept(
                EntitlementScopeKind.TEAM, teamId, handoverRequestId, operatorUserId)));
    }

    // ============================================================
    // ORG スコープ
    // ============================================================

    @PostMapping("/organizations/{organizationId}/billing/contracts/{contractId}/payer-handover-requests")
    @PreAuthorize("@billingAccessGuard.canManage(authentication, T(com.mannschaft.app.billing.EntitlementScopeKind).ORG, #organizationId)")
    @Operation(summary = "組織契約の請求担当引継を申請",
            description = "旧 payer が申請する（承諾型2段の1段目）。旧契約が PAST_DUE または期末が過去の場合は拒否される。")
    public ResponseEntity<ApiResponse<PayerHandoverRequestResponse>> requestForOrganization(
            @PathVariable Long organizationId, @PathVariable UUID contractId) {
        Long operatorUserId = SecurityUtils.getCurrentUserId();
        PayerHandoverRequestResponse body = payerHandoverApplicationService.request(
                EntitlementScopeKind.ORG, organizationId, contractId, operatorUserId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(body));
    }

    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "退会予定の払い手から請求担当を引き継ぐ操作は、Gate状態にかかわらず到達可能でなければ課金が止まらないため")
    @PostMapping("/organizations/{organizationId}/billing/payer-handover-requests/{handoverRequestId}/acceptance")
    @PreAuthorize("@billingAccessGuard.canManage(authentication, T(com.mannschaft.app.billing.EntitlementScopeKind).ORG, #organizationId)")
    @Operation(summary = "組織契約の請求担当引継を承諾",
            description = "新 payer となる他 ADMIN が承諾する（2段目）。支払い方法未登録なら REQUIRES_PAYMENT_METHOD で差し戻す。")
    public ResponseEntity<ApiResponse<PayerHandoverAcceptResponse>> acceptForOrganization(
            @PathVariable Long organizationId, @PathVariable UUID handoverRequestId) {
        Long operatorUserId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(payerHandoverApplicationService.accept(
                EntitlementScopeKind.ORG, organizationId, handoverRequestId, operatorUserId)));
    }
}
