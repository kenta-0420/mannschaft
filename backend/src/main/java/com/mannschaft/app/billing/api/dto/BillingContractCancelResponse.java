package com.mannschaft.app.billing.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Billing Center PR6a: 解約／解約撤回の応答（正本 05_billing_center.md:334-335・344）。
 *
 * <p><b>時刻はオフセット付きで返す</b>。新規の {@code LocalDateTime} フィールドは番人
 * {@code DateTimeAndZoneGuardTest} が拒否しており（暗黙のゾーン依存を増やさないため）、
 * API 境界を越える時刻はオフセットを明示した型で運ぶのが正しい。</p>
 *
 * <p>{@code contractStatus} と {@code status} は別物である。期末解約を予約しても契約そのものは
 * 期末まで利用できるので {@code contractStatus} は {@code ACTIVE} のまま（AC-23）であり、
 * 「解約予約中か」は {@code status} が {@code SCHEDULED} か {@code ACTIVE} かで表す。</p>
 *
 * @param contractId       契約 ID
 * @param contractStatus   契約そのものの状態（{@code ACTIVE} / {@code PAST_DUE} / …）
 * @param status           解約予約の状態（{@code SCHEDULED} ＝予約中 / {@code ACTIVE} ＝予約なし）
 * @param scheduledAt      解約予約を入れた時刻（撤回後は null）
 * @param endAt            利用可能期限。<b>非 null</b>（AC-37c。null のまま 200 を返してはならない）
 * @param currentPeriodEnd {@code endAt} と同値（表示用・AC-60 との整合）
 * @param version          更新後の CAS version
 * @param canCancel        解約できるか
 * @param canResume        撤回できるか（期末を跨いだら false・AC-46）
 */
@Schema(description = "解約・解約撤回の結果")
public record BillingContractCancelResponse(
        UUID contractId,
        String contractStatus,
        String status,
        OffsetDateTime scheduledAt,
        OffsetDateTime endAt,
        OffsetDateTime currentPeriodEnd,
        Long version,
        boolean canCancel,
        boolean canResume) {

    /** 解約予約中を表す {@code status} 値。 */
    public static final String STATUS_SCHEDULED = "SCHEDULED";
    /** 解約予約が無いことを表す {@code status} 値。 */
    public static final String STATUS_ACTIVE = "ACTIVE";
}
