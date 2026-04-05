package com.mannschaft.app.gamification.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 管理者ポイント調整リクエストDTO。
 */
public record AdminAdjustPointRequest(
        @NotNull Long userId,
        @NotNull int points,
        @Size(max = 200) String reason
) {}
