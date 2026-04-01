package com.mannschaft.app.auth.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * MFAリカバリー完了イベント。バックアップコードを使用したMFAリカバリー完了時に発行される。
 */
@Getter
public class MfaRecoveryCompletedEvent extends BaseEvent {

    private final Long userId;

    public MfaRecoveryCompletedEvent(Long userId) {
        super();
        this.userId = userId;
    }
}
