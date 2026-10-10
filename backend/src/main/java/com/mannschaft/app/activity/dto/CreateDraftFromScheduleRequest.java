package com.mannschaft.app.activity.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** 初期値をサーバーで確定する予定由来下書き作成要求。 */
public record CreateDraftFromScheduleRequest(@NotNull @Positive Long scheduleId) {
}
