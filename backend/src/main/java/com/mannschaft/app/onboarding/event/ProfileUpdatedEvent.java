package com.mannschaft.app.onboarding.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * プロフィール更新イベント（想定クラス）。
 * メンバープロフィール更新時に発行される想定。
 */
@Getter
public class ProfileUpdatedEvent extends BaseEvent {

    private final Long userId;
    private final String scopeType;
    private final Long scopeId;

    public ProfileUpdatedEvent(Long userId, String scopeType, Long scopeId) {
        super();
        this.userId = userId;
        this.scopeType = scopeType;
        this.scopeId = scopeId;
    }
}
