package com.mannschaft.app.billing.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Billing Center PR6a: 解約（{@code POST …/cancel}）／解約撤回（{@code DELETE …/cancel}）のリクエスト本文。
 *
 * <p>1フィールドだけを持つのは意図である。{@code Idempotency-Key} の request hash は
 * 「actor / HTTP method / request path / 本文 JSON」を連結して取るため、DELETE でも本文を受けて
 * POST と<b>同一の算式</b>を共有する（本文が無いと「同じキーで違う内容」を検出できない）。</p>
 *
 * @param version 契約の CAS 期待値（{@code billing_contracts.version}）。不一致は 409（AC-27 / AC-44）
 */
@Schema(description = "解約・解約撤回リクエスト")
public record BillingCancelRequest(
        @Schema(description = "契約の version（楽観ロックの期待値）", example = "0")
        @NotNull @PositiveOrZero Long version) {
}
