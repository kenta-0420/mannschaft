package com.mannschaft.app.auth.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * WebAuthnデバイス登録完了イベント。
 */
@Getter
public class WebAuthnRegisteredEvent extends BaseEvent {

    private final Long userId;
    private final String deviceName;

    public WebAuthnRegisteredEvent(Long userId, String deviceName) {
        super();
        this.userId = userId;
        this.deviceName = deviceName;
    }
}
