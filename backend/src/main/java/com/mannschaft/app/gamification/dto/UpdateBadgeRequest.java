package com.mannschaft.app.gamification.dto;

import jakarta.validation.constraints.NotNull;

/**
 * バッジ更新リクエストDTO。
 */
public record UpdateBadgeRequest(
        String name,
        Boolean isActive,
        @NotNull Long version
) {}
