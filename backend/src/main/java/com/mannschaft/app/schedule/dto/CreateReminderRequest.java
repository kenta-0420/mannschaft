package com.mannschaft.app.schedule.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * リマインダー作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateReminderRequest {

    @NotNull
    @Future
    private final LocalDateTime remindAt;
}
