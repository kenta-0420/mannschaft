package com.mannschaft.app.billing.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * 柱③-B: {@code MANUAL_INTERVENTION} からの再開（{@code RESUME}）要求
 * （設計書 billing_payer_handover_design.md §3.6.2・AC-37）。
 *
 * <p>{@code MANUAL_INTERVENTION} は「機械的なリトライでは安全に解消できない異常」を検知した状態であり、
 * 主因は<b>期末境界越え</b>（旧サブスクが期末解約の予約なしに次の期間へ更新されてしまった）である。
 * どちらの出口へ倒すか、旧サブスクの予約を差し戻すかは<b>運用者が Stripe 側の実データを確認したうえで
 * 明示的に選ぶ</b>——自動判定してはならない（設計書は自動での void/refund をスコープ外と明記している）。</p>
 *
 * @param target                  再開先。{@code SWITCHING}（切替を再試行させる）または
 *                                {@code FAILED}（引継を諦めて終端化する）
 * @param revertOldCancelSchedule {@code FAILED} 確定時に旧サブスクの {@code cancel_at_period_end} を
 *                                {@code false} へ差し戻すか。<b>旧サブスクが既に次の期間へ更新済みの場合、
 *                                差し戻しは旧をさらに継続させるため不適切なことがある</b>。よって必須にせず
 *                                運用者の判断に委ねる（{@code target=SWITCHING} のときは無視される）
 */
@Schema(name = "BillingPayerHandoverResumeRequest",
        description = "手動介入中の請求担当引継を再開（または失敗確定）する要求")
public record PayerHandoverResumeRequest(

        @NotNull
        @Schema(description = "再開先。SWITCHING=切替を再試行させる / FAILED=引継を諦めて終端化する",
                requiredMode = Schema.RequiredMode.REQUIRED, example = "SWITCHING")
        ResumeTargetValue target,

        @Schema(description = "FAILED 確定時に旧サブスクの期末解約予約を差し戻すか"
                + "（旧が既に次の期間へ更新済みの場合は差し戻しが不適切なことがあるため運用者が選ぶ）",
                defaultValue = "false")
        boolean revertOldCancelSchedule) {

    /** 再開先の許容値（設計書 §3.6.2 の出口2種）。 */
    public enum ResumeTargetValue {
        /** 切替再試行が可能と判断した: {@code SWITCHING} へ戻し、次回の切替バッチで再評価させる。 */
        SWITCHING,
        /** 引継自体を諦めると判断した: {@code FAILED} で終端化する。 */
        FAILED
    }
}
