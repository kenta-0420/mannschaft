package com.mannschaft.app.billing.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

/**
 * 柱③-B: {@code MANUAL_INTERVENTION} からの再開（{@code RESUME}）結果
 * （設計書 billing_payer_handover_design.md §3.6.2・AC-37）。
 */
@Getter
@Builder
@Schema(name = "BillingPayerHandoverResumeResponse", description = "手動介入からの再開結果")
public class PayerHandoverResumeResponse {

    @Schema(description = "引継要求 ID")
    private final String handoverRequestId;

    @Schema(description = "再開後の状態（SWITCHING または FAILED）")
    private final String status;

    @Schema(description = "旧サブスクの期末解約予約を差し戻したか（FAILED 確定時のみ意味を持つ）")
    private final boolean oldCancelScheduleReverted;
}
