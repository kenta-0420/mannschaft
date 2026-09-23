package com.mannschaft.app.shift.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalTime;

/**
 * デフォルト勤務可能時間設定リクエストDTO（CMP-260912-1758: 入力検証根治）。
 *
 * <p>単一フィールドで完結する検証（{@code dayOfWeek} の範囲）はここに置く。
 * 複数行にまたがる検証（同一 {@code dayOfWeek} の重複禁止）と {@code preference} の
 * 列挙値検証は、リスト全体を見る必要があるため {@code ShiftAvailabilityService} 側に置く
 * （{@code preference} を enum 型にすると Jackson のデシリアライズ段階で不正値が
 * {@code HttpMessageNotReadableException} → 400 にはなるが、エラーコードを
 * {@code ShiftErrorCode} 経由で統一するため、あえて {@code String} で受けてサービス側で検証する）。</p>
 */
@Getter
@RequiredArgsConstructor
public class AvailabilityDefaultRequest {

    /** 曜日（0=日曜〜6=土曜。設計 F03.5 準拠）。 */
    @NotNull
    @Min(0)
    @Max(6)
    private final Integer dayOfWeek;

    @NotNull
    private final LocalTime startTime;

    @NotNull
    private final LocalTime endTime;

    @NotNull
    private final String preference;

    @Size(max = 200)
    private final String note;
}
