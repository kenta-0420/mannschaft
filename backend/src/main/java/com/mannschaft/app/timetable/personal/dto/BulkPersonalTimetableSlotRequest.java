package com.mannschaft.app.timetable.personal.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * F03.15 Phase 2 個人時間割のコマ一括更新リクエスト。
 *
 * <p>{@code dayOfWeek} クエリ未指定時は全曜日を全置換、指定時はその曜日のみ全置換。</p>
 */
public record BulkPersonalTimetableSlotRequest(
        @NotNull
        @Valid
        List<PersonalTimetableSlotRequest> slots) {
}
