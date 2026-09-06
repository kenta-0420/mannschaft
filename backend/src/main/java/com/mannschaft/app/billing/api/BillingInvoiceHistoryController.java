package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.api.dto.BillingInvoiceDetailResponse;
import com.mannschaft.app.billing.api.dto.BillingInvoiceSummaryResponse;
import com.mannschaft.app.billing.api.dto.BillingManageableScopeListResponse;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.CursorPagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * F20.1 Billing Center — 課金履歴 API（AC-44〜AC-60）。
 *
 * <p>正本: {@code docs/features/F20.1_entitlement_billing/05_billing_center.md} §7・§8・§9。</p>
 *
 * <h2>認可</h2>
 * <p>URL 層の deny-by-default（{@code anyRequest().authenticated()}）で未認証は 401（AC-44）。
 * scope の管理権限は {@link BillingAccessGuard} を {@link BillingInvoiceHistoryService} 経由で
 * 必ず通す。SYSTEM_ADMIN の権限文字列だけでは通らない（AC-47）。</p>
 *
 * <h2>ページング</h2>
 * <p>{@code cursor} は不透明な base64 値であり、中身の書式は API 契約ではない。
 * {@code size} は 1〜100（AC-49・範囲外は 400）。</p>
 */
@RestController
@RequestMapping("/api/v1/me/billing")
@Validated
@Tag(name = "課金 - 請求履歴", description = "F20.1 請求書の一覧・明細・管理可能スコープ")
@RequiredArgsConstructor
public class BillingInvoiceHistoryController {

    /** 一覧の既定ページサイズ。 */
    private static final String DEFAULT_SIZE = "20";

    private final BillingInvoiceHistoryService invoiceHistoryService;

    @GetMapping("/invoices")
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "請求書一覧",
            description = "指定スコープの請求書を period_end 降順で返す。cursor は不透明値。")
    public ResponseEntity<CursorPagedResponse<BillingInvoiceSummaryResponse>> list(
            @RequestParam("scopeKind") EntitlementScopeKind scopeKind,
            @RequestParam("scopeId") Long scopeId,
            @RequestParam(name = "size", defaultValue = DEFAULT_SIZE)
            @Min(BillingInvoiceHistoryService.MIN_PAGE_SIZE)
            @Max(BillingInvoiceHistoryService.MAX_PAGE_SIZE) int size,
            @RequestParam(name = "cursor", required = false) String cursor) {
        Long actorId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(
                invoiceHistoryService.list(actorId, scopeKind, scopeId, size, cursor));
    }

    @GetMapping("/invoices/{invoiceId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "請求書明細",
            description = "明細行・調整・税内訳を返す。他スコープの ID は存在秘匿のため 404。")
    public ResponseEntity<ApiResponse<BillingInvoiceDetailResponse>> detail(
            @PathVariable("invoiceId") UUID invoiceId) {
        Long actorId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(invoiceHistoryService.detail(actorId, invoiceId)));
    }

    @GetMapping("/scopes")
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "課金を管理できるスコープ一覧",
            description = "本人の USER スコープと、ADMIN／課金権限付き DEPUTY_ADMIN のチーム・組織。")
    public ResponseEntity<ApiResponse<BillingManageableScopeListResponse>> scopes() {
        Long actorId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(
                ApiResponse.of(invoiceHistoryService.manageableScopes(actorId)));
    }
}
