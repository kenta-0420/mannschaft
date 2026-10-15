package com.mannschaft.app.onboarding.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * オンボーディング完了イベント。全ステップ完了時に発行される。
 */
@Getter
public class OnboardingCompletedEvent extends BaseEvent {

    private final Long progressId;
    private final Long userId;
    private final String scopeType;
    private final Long scopeId;

    public OnboardingCompletedEvent(Long progressId, Long userId, String scopeType, Long scopeId) {
        super();
        this.progressId = progressId;
        this.userId = userId;
        this.scopeType = scopeType;
        this.scopeId = scopeId;
    }
}
