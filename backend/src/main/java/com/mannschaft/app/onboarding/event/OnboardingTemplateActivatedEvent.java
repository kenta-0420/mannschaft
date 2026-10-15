package com.mannschaft.app.onboarding.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * オンボーディングテンプレートアクティベーションイベント。
 */
@Getter
public class OnboardingTemplateActivatedEvent extends BaseEvent {

    private final Long templateId;
    private final String scopeType;
    private final Long scopeId;

    public OnboardingTemplateActivatedEvent(Long templateId, String scopeType, Long scopeId) {
        super();
        this.templateId = templateId;
        this.scopeType = scopeType;
        this.scopeId = scopeId;
    }
}
