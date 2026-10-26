package com.mannschaft.app.auth.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * WebAuthnログイン成功イベント。
 * completeLogin() 成功時に発行される。監査ログの event_type は WEBAUTHN_LOGIN。
 */
@Getter
public class WebAuthnLoginEvent extends BaseEvent {

    private final Long userId;
    private final String ipAddress;
    private final String userAgent;
    /** 使用した WebAuthn credential の credentialId（Base64URL）。 */
    private final String credentialId;

    public WebAuthnLoginEvent(Long userId, String ipAddress, String userAgent, String credentialId) {
        super();
        this.userId = userId;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.credentialId = credentialId;
    }
}
