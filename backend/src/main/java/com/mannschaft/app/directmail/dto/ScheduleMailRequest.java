package com.mannschaft.app.directmail.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * メール予約送信リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class ScheduleMailRequest {

    @NotNull
    @Future
    private final LocalDateTime scheduledAt;
}
