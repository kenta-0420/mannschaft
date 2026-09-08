package com.mannschaft.app.billing.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

/**
 * 柱③-B: 請求担当（payer）引継要求のレスポンス（設計書 billing_payer_handover_design.md §4.2）。
 *
 * <p>状態は要求レベルの状態機械（{@code REQUESTED}/{@code ACCEPTED}/{@code REQUIRES_PAYMENT_METHOD}/
 * {@code SWITCHING}/{@code PARTIALLY_COMPLETED}/{@code MANUAL_INTERVENTION}/{@code COMPLETED}/
 * {@code FAILED}/{@code EXPIRED} の9値）を文字列で返す。契約レベルの状態（{@code billing_contracts.status}）
 * とは別物であり混同しないこと。</p>
 *
 * <p>時刻は {@link Instant}（真の瞬間・UTC）で返す。引継の申請・期限は壁時計ではなく絶対時刻で判定するため
 * （{@code docs/architecture/datetime_policy_utc_instant_vs_wallclock.md}）。</p>
 */
@Getter
@Builder
@Schema(name = "BillingPayerHandoverRequestResponse", description = "請求担当引継要求")
public class PayerHandoverRequestResponse {

    @Schema(description = "引継要求 ID（UUIDv7）")
    private final String handoverRequestId;

    @Schema(description = "引継元の契約 ID")
    private final String oldContractId;

    @Schema(description = "スコープ種別（TEAM / ORG。USER は引継の概念が無く対象外）")
    private final String scopeKind;

    @Schema(description = "スコープ ID")
    private final Long scopeId;

    @Schema(description = "要求の状態（9値の状態機械）")
    private final String status;

    @Schema(description = "申請時刻")
    private final Instant requestedAt;

    @Schema(description = "承諾の猶予期限（既定: 申請から14日）")
    private final Instant expiresAt;
}
