package com.mannschaft.app.billing.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * PR5 Stripe Customer Portal セッション発行の応答（正本 05 §7 の表: {@code {url, issuedAt}}）。
 *
 * <p>Portal URL は短命であり、302 ではなく JSON で返す（正本 §336 の「document/Portal URL は
 * JSON の短命 URL に統一し 302 を使わない」）。この URL は監査・ログには残さない。</p>
 *
 * @param url      Stripe Customer Portal の短命 URL
 * @param issuedAt 発行時刻（PORTAL_RETURN state の {@code iat} と同一）
 */
public record BillingCustomerPortalSessionResponse(
        @Schema(description = "Stripe Customer Portal の短命 URL") String url,
        @Schema(description = "発行時刻") Instant issuedAt) {
}
