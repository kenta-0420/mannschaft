package com.mannschaft.app.billing.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/**
 * F20.1: 権利サマリ内のアクティブ契約（PLAN または ADDON・設計書 02 §2.2）。
 */
@Getter
@Builder
@Schema(name = "BillingActiveContract", description = "F20.1 権利サマリ内のアクティブ契約")
public class ActiveContract {

    @Schema(description = "契約 ID（UUID）", example = "0198aaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
    private final String contractId;

    @Schema(description = "プランキー（PLAN 契約時）。ADDON 時は null", nullable = true, example = "FULL")
    private final String planKey;

    @Schema(description = "機能キー（ADDON 契約時）。PLAN 時は null", nullable = true, example = "ads.hide")
    private final String featureKey;

    @Schema(description = "契約日時（ISO-8601）")
    private final LocalDateTime contractedAt;

    @Schema(description = "契約時単価スナップショット（円）。ベータ中は null（無償）", nullable = true)
    private final Integer priceJpySnapshot;

    @Schema(description = "契約状態（ContractStatus の6値: PENDING / ACTIVE / PAST_DUE / CANCELLED "
            + "/ EXPIRED / PENDING_HANDOVER）", example = "ACTIVE")
    private final String status;

    @Schema(description = "現在の課金期間の終了時刻。期末を持たない契約は null", nullable = true)
    private final OffsetDateTime currentPeriodEnd;

    @Schema(description = "この契約を解約できるか（解約予約済みなら false）")
    private final boolean canCancel;

    @Schema(description = "解約予約を撤回できるか（期末を跨いだら false）")
    private final boolean canResume;

    @Schema(description = "解約予約の内容。予約が無ければ null", nullable = true)
    private final ScheduledCancel cancel;

    /**
     * 解約予約の内容（PR6a AC-60）。予約が入っていないときは親の {@code cancel} 自体が null になる。
     *
     * <p>時刻はオフセット付きで返す（新規の壁時計型フィールドは番人
     * {@code DateTimeAndZoneGuardTest} が拒否する。API 境界を越える時刻はオフセットを明示する）。</p>
     */
    @Getter
    @Builder
    @Schema(name = "BillingScheduledCancel", description = "F20.1 解約予約の内容")
    public static class ScheduledCancel {

        @Schema(description = "解約予約を入れた時刻（cancelled_at）")
        private final OffsetDateTime scheduledAt;

        @Schema(description = "利用可能期限（＝currentPeriodEnd）")
        private final OffsetDateTime endAt;
    }
}
