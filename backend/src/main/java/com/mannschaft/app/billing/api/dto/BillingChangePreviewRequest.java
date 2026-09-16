package com.mannschaft.app.billing.api.dto;

import com.mannschaft.app.billing.BillingProductKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Billing Center PR6b-1 A群: 事前見積り（{@code POST …/change-previews}）の要求本文。
 *
 * <p>AC-17: {@code priceVersionId} / {@code priceBandVersionId} を受け取るフィールドを
 * <b>意図的に持たない</b>。未知のプロパティは既定の Jackson 設定（{@code FAIL_ON_UNKNOWN_PROPERTIES}）
 * により 400 で弾かれる（server が tx 内で確定する）。</p>
 *
 * @param toProductKind 変更後の商品種別（このフェーズは {@link BillingProductKind#PLAN} のみ）
 * @param toProductKey  変更後 PLAN の {@code product_key}
 * @param version       利用者が把握している契約 {@code version}（CAS）
 */
public record BillingChangePreviewRequest(
        @NotNull BillingProductKind toProductKind,
        @NotBlank String toProductKey,
        @NotNull Long version) {
}
