package com.mannschaft.app.onboarding.dto;

import com.mannschaft.app.onboarding.OnboardingTemplateStatus;

/**
 * テンプレートアクティベーションレスポンス。
 */
public record ActivateTemplateResponse(
        Long id,
        OnboardingTemplateStatus status,
        Long previousActiveTemplateId,
        Long version
) {}
