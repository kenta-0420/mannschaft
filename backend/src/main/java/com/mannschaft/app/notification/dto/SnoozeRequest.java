package com.mannschaft.app.notification.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 通知スヌーズリクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class SnoozeRequest {

    @NotNull
    @Future
    private final LocalDateTime snoozedUntil;
}
