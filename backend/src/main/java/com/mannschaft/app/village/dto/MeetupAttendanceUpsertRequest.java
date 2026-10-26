package com.mannschaft.app.village.dto;

import com.mannschaft.app.village.entity.enums.VillageMeetupAttendanceStatus;
import jakarta.validation.constraints.NotNull;

/**
 * F17.2 Wave1 ②寄合後半戦 — 出欠 upsert リクエスト（設計書 §4.4）。
 *
 * <p>{@code status} は enum バインドのため、GOING/MAYBE/ABSENT 以外の文字列は
 * Jackson デシリアライズで 400（COMMON_001）となる。</p>
 */
public record MeetupAttendanceUpsertRequest(
        @NotNull VillageMeetupAttendanceStatus status) {
}
