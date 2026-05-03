package com.mannschaft.app.timetable.personal.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * F03.15 Phase 2 個人時間割の時限一括更新リクエスト。
 *
 * <p>全置換動作。空リストを送ると時限が全削除される（ユーザーが意図的にリセットするケースのため許容）。</p>
 */
public record BulkPersonalTimetablePeriodRequest(
        @NotNull
        @Valid
        List<PersonalTimetablePeriodRequest> periods) {
}
