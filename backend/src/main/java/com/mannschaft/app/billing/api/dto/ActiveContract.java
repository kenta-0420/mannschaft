package com.mannschaft.app.billing.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
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

    /**
     * 楽観ロックの CAS 期待値（{@code billing_contracts.version}）。
     *
     * <p>正本 05_billing_center.md:344 の {@code ContractBase} が持つ項目である。解約
     * （{@code POST …/cancel}）と撤回（{@code DELETE …/cancel}）は本文 {@code {"version": N}} を
     * <b>必須</b>とし、不一致なら 409 を返す（AC-27 / AC-44）。表示投影がこの値を返さないと
     * FE は CAS 期待値を得られず、<b>解約も撤回も実行できない</b>（0 を決め打ちで埋めるのは
     * 他人の更新を踏み潰す対処療法であり採らない）。</p>
     */
    @Schema(description = "楽観ロックのCAS期待値。解約・撤回APIの version に渡す", example = "0")
    private final Long version;

    @Schema(description = "解約予約の内容。予約が無ければ null", nullable = true)
    private final ScheduledCancel cancel;

    /**
     * 進行中のプラン変更（upgrade）の内容（PR6b-1 AC-133）。{@code PENDING_PAYMENT} /
     * {@code REQUIRES_ACTION} の変更が無ければ親の {@code pendingChange} 自体が null になる
     * （AC-107: 支払い待ちでない契約ではこの投影自体を出さない）。
     */
    @Schema(description = "進行中のプラン変更（upgrade）の内容。進行中の変更が無ければ null", nullable = true)
    private final PendingChange pendingChange;

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

    /**
     * 進行中のプラン変更の内容（PR6b-1 AC-133）。{@code billing_contract_changes} の
     * {@code effectiveAt} は Stripe 由来の瞬間であり {@link Instant} で返す
     * （sibling の {@code BillingChangePreviewResponse}/{@code BillingContractChangeResponse}
     * と同じ流儀。{@code docs/architecture/datetime_policy_utc_instant_vs_wallclock.md} §1/§4）。
     */
    @Getter
    @Builder
    @Schema(name = "BillingPendingChange", description = "F20.1 進行中のプラン変更（upgrade）の内容")
    public static class PendingChange {

        @Schema(description = "変更の状態（PENDING_PAYMENT または REQUIRES_ACTION）",
                example = "REQUIRES_ACTION")
        private final String status;

        @Schema(description = "変更の効力発生予定の瞬間（ISO-8601 Instant）")
        private final Instant effectiveAt;

        @Schema(description = "3DS等の追加認証待ちか（true なら GET …/payment-action を叩ける）")
        private final boolean paymentActionRequired;
    }
}
