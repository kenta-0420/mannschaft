package com.mannschaft.app.onboarding.dto;

import com.mannschaft.app.onboarding.OnboardingProgressStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * オンボーディング進捗詳細レスポンス。
 */
public record OnboardingProgressDetailResponse(
        Long id,
        String scopeType,
        Long scopeId,
        String scopeName,
        String templateName,
        String welcomeMessage,
        OnboardingProgressStatus status,
        Integer totalSteps,
        Integer completedSteps,
        BigDecimal completionRate,
        LocalDateTime deadlineAt,
        LocalDateTime startedAt,
        List<StepProgressResponse> steps
) {}
