package com.mannschaft.app.auth.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * 2FA回復リクエストイベント。
 */
@Getter
public class MfaRecoveryRequestedEvent extends BaseEvent {

    private final Long userId;
    private final String email;
    private final String rawToken;

    public MfaRecoveryRequestedEvent(Long userId, String email, String rawToken) {
        super();
        this.userId = userId;
        this.email = email;
        this.rawToken = rawToken;
    }
}
