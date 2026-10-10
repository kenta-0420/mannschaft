package com.mannschaft.app.activity.dto;

import com.mannschaft.app.schedule.dto.UpdateScheduleRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/** 保存前の予定変更と活動差分のプレビュー要求。 */
public record ActivitySyncPreviewRequest(@NotNull @Valid UpdateScheduleRequest scheduleUpdate,
                                         String updateScope) {
}
