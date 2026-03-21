package com.mannschaft.app.schedule.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * カレンダー同期ON/OFFリクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CalendarSyncToggleRequest {

    @NotNull
    private final Boolean isEnabled;
}
