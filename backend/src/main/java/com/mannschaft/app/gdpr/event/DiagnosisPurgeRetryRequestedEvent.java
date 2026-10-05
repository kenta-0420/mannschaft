package com.mannschaft.app.gdpr.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/** 診断の削除再試行受付。GDPR側の受付コミット後にのみ独立ドメインへ配送する。 */
@Getter
public class DiagnosisPurgeRetryRequestedEvent extends BaseEvent {
    private final Long userId;

    public DiagnosisPurgeRetryRequestedEvent(Long userId) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("削除対象の指定が不正です");
        }
        this.userId = userId;
    }
}
