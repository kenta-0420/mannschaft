package com.mannschaft.app.reservation.dto;

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
    private final LocalDateTime remindAt;
}
