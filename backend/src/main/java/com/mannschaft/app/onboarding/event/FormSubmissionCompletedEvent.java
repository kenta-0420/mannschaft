package com.mannschaft.app.onboarding.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * フォーム送信完了イベント（想定クラス）。
 * F05.7 フォームビルダー機能から発行される想定。
 */
@Getter
public class FormSubmissionCompletedEvent extends BaseEvent {

    private final Long formId;
    private final Long userId;
    private final String scopeType;
    private final Long scopeId;

    public FormSubmissionCompletedEvent(Long formId, Long userId, String scopeType, Long scopeId) {
        super();
        this.formId = formId;
        this.userId = userId;
        this.scopeType = scopeType;
        this.scopeId = scopeId;
    }
}
