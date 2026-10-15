package com.mannschaft.app.billing.api.dto;

import com.mannschaft.app.billing.EntitlementScopeKind;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * PR5 Stripe Customer Portal セッション発行の入力契約（正本 05 §7 の表）。
 *
 * <p>受け取るのは対象 scope だけである。<b>return URL は要求から一切受け取らない</b>
 * （固定の {@code /billing/portal/return} のみ・AC-67）。</p>
 */
public record CreateBillingCustomerPortalSessionRequest(
        @NotNull @Schema(description = "対象スコープ種別", example = "USER")
        EntitlementScopeKind scopeKind,
        @NotNull @Schema(description = "対象スコープ ID", example = "1")
        Long scopeId) {
}
