package com.mannschaft.app.onboarding.dto;

import com.mannschaft.app.onboarding.OnboardingPresetCategory;

import java.time.LocalDateTime;

/**
 * システムプリセットレスポンス（SYSTEM_ADMIN用）。
 */
public record PresetResponse(
        Long id,
        String name,
        String description,
        OnboardingPresetCategory category,
        String welcomeMessage,
        Boolean isOrderEnforced,
        Integer deadlineDays,
        String stepsJson,
        Boolean isActive,
        Integer sortOrder,
        LocalDateTime createdAt
) {}
