package com.mannschaft.app.auth.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * WebAuthnログイン失敗イベント。
 * completeLogin() で署名検証失敗・例外スロー時に発行される。
 */
@Getter
public class WebAuthnLoginFailedEvent extends BaseEvent {

    private final Long userId;
    private final String ipAddress;
    private final String userAgent;
    /** 失敗した credential の ID（特定できる場合のみ）。特定不能時は null。 */
    private final String credentialId;

    public WebAuthnLoginFailedEvent(Long userId, String ipAddress, String userAgent, String credentialId) {
        super();
        this.userId = userId;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.credentialId = credentialId;
    }
}
