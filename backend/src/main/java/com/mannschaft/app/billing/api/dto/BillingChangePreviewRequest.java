package com.mannschaft.app.billing.api.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.mannschaft.app.billing.BillingProductKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Billing Center PR6b-1 A群: 事前見積り（{@code POST …/change-previews}）の要求本文。
 *
 * <p>AC-17: {@code priceVersionId} / {@code priceBandVersionId} を受け取るフィールドを
 * <b>意図的に持たない</b>。加えて
 * {@link BillingChangePreviewRequestDeserializer} で未知のプロパティを 400 で弾く。Spring Boot は既定で
 * {@code spring.jackson.deserialization.fail-on-unknown-properties=false}（＝未知項目を黙って捨てる）
 * を敷いているため、<b>フィールドを持たないだけでは攻撃者の {@code priceBandVersionId} は「無視された」
 * ように見えて素通りし、価格の同定を client が試みた事実そのものが観測できない</b>。価格の同定は
 * server が tx 内で行う以上、client が送ってきたこと自体を拒否するのが正しい。</p>
 *
 * @param toProductKind 変更後の商品種別（このフェーズは {@link BillingProductKind#PLAN} のみ）
 * @param toProductKey  変更後 PLAN の {@code product_key}
 * @param version       利用者が把握している契約 {@code version}（CAS）
 */
@JsonDeserialize(using = BillingChangePreviewRequestDeserializer.class)
public record BillingChangePreviewRequest(
        @NotNull BillingProductKind toProductKind,
        @NotBlank String toProductKey,
        @NotNull Long version) {
}
