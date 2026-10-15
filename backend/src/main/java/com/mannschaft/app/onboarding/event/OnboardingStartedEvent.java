package com.mannschaft.app.onboarding.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * オンボーディング開始イベント。進捗作成時に発行される。
 */
@Getter
public class OnboardingStartedEvent extends BaseEvent {

    private final Long progressId;
    private final Long userId;
    private final String scopeType;
    private final Long scopeId;

    public OnboardingStartedEvent(Long progressId, Long userId, String scopeType, Long scopeId) {
        super();
        this.progressId = progressId;
        this.userId = userId;
        this.scopeType = scopeType;
        this.scopeId = scopeId;
    }
}
