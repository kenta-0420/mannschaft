package com.mannschaft.app.gamification.dto;

import jakarta.validation.constraints.NotNull;

/**
 * バッジ手動付与リクエストDTO。
 */
public record AwardBadgeRequest(
        @NotNull Long userId
) {}
