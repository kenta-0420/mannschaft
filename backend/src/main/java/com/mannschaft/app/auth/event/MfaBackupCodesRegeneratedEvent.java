package com.mannschaft.app.auth.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * MFAバックアップコード再生成イベント。バックアップコードの再生成時に発行される。
 */
@Getter
public class MfaBackupCodesRegeneratedEvent extends BaseEvent {

    private final Long userId;

    public MfaBackupCodesRegeneratedEvent(Long userId) {
        super();
        this.userId = userId;
    }
}
