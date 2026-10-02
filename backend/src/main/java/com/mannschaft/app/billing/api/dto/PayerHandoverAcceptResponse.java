package com.mannschaft.app.billing.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

/**
 * 柱③-B: 請求担当（payer）引継の承諾レスポンス（設計書 billing_payer_handover_design.md §3.6）。
 *
 * <p><b>{@code status} が {@code REQUIRES_PAYMENT_METHOD} の場合は「承諾が差し戻された」ことを意味する</b>
 * （設計書 §3.6・AC-16/AC-19）。新 payer に有効な支払い手段が無いまま新サブスクを作ると trial 終了時に
 * {@code past_due} へ落ちるため、承諾前に検証して差し戻す。この場合 {@code checkoutUrl} は null であり、
 * 旧契約は一切変更されていない（旧サブスクの期末解約予約もまだ行われていないため<b>旧契約は無傷</b>）。
 * 利用者は支払い方法を登録したうえで再度承諾する。</p>
 *
 * <p>{@code status} が {@code ACCEPTED} の場合は {@code checkoutUrl} へ遷移させる。決済完了
 * （{@code checkout.session.completed}）が引継確定条件であり、その時点で初めて旧サブスクへ期末解約が
 * 予約される。なお新サブスクは旧期末までトライアル扱いのため、<b>この時点では課金は一切発生しない</b>。</p>
 */
@Getter
@Builder
@Schema(name = "BillingPayerHandoverAcceptResponse", description = "請求担当引継の承諾結果")
public class PayerHandoverAcceptResponse {

    @Schema(description = "引継要求 ID")
    private final String handoverRequestId;

    @Schema(description = "承諾後の状態。REQUIRES_PAYMENT_METHOD は支払い方法未登録による差し戻し")
    private final String status;

    @Schema(description = "引継先として先行作成された契約 ID（PENDING_HANDOVER 状態。差し戻し時は null）")
    private final String newContractId;

    @Schema(description = "新 payer が決済を完了するための Checkout URL（差し戻し時は null）")
    private final String checkoutUrl;
}
