package com.mannschaft.app.onboarding.dto;

import com.mannschaft.app.onboarding.OnboardingTemplateStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * オンボーディングテンプレートレスポンス。
 */
public record OnboardingTemplateResponse(
        Long id,
        String scopeType,
        Long scopeId,
        String name,
        String description,
        String welcomeMessage,
        OnboardingTemplateStatus status,
        Boolean isOrderEnforced,
        Integer deadlineDays,
        Integer reminderDaysBefore,
        Boolean isAdminNotifiedOnComplete,
        Boolean isTimelinePostedOnComplete,
        List<StepResponse> steps,
        Long version,
        LocalDateTime createdAt
) {}
