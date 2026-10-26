package com.mannschaft.app.event.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 点呼エントリリクエストDTO。F03.12 §14 主催者点呼機能。
 *
 * <p>点呼セッション内の1名分の出欠記録を表す。
 * status=LATE の場合は lateArrivalMinutes を、
 * status=ABSENT の場合は absenceReason を付与することを推奨する。</p>
 */
@Getter
@RequiredArgsConstructor
public class RollCallEntryRequest {

    /** 点呼対象ユーザーID（必須）。 */
    @NotNull
    private final Long userId;

    /**
     * 点呼結果ステータス（必須）。
     * PRESENT=来た / ABSENT=欠席 / LATE=遅刻
     */
    @NotNull
    private final String status;

    /**
     * 遅刻分数。status=LATE の場合のみ有効。1以上を指定すること。
     */
    @Min(1)
    private final Integer lateArrivalMinutes;

    /**
     * 欠席理由。status=ABSENT の場合のみ有効。
     * NOT_ARRIVED / SICK / PERSONAL_REASON / OTHER のいずれか。
     */
    private final String absenceReason;
}
