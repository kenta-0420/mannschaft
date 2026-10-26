package com.mannschaft.app.village.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * F17.2 Wave1 ④歳時記×村史の年輪 — 年輪追加リクエスト（設計書 §6.4）。
 *
 * <p>{@code year} は記録対象の西暦年（必須）。{@code photoR2Key}（写真）・{@code note}（一言メモ）は任意。
 * 同一 {@code (calendarEventId, year)} に複数件を許す（UNIQUE を張らない・§6.3）。</p>
 */
public record CalendarEventLogCreateRequest(
        @NotNull @Min(1900) @Max(3000) Integer year,
        @Size(max = 255) String photoR2Key,
        @Size(max = 300) String note) {
}
