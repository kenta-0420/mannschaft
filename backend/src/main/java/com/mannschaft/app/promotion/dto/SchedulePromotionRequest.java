package com.mannschaft.app.promotion.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * プロモーション予約配信リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class SchedulePromotionRequest {

    @NotNull
    private final LocalDateTime scheduledAt;
}
