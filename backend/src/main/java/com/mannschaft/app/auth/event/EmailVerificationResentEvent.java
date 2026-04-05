package com.mannschaft.app.auth.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * メール確認再送信イベント。
 */
@Getter
public class EmailVerificationResentEvent extends BaseEvent {

    private final Long userId;
    private final String email;
    private final String rawToken;

    public EmailVerificationResentEvent(Long userId, String email, String rawToken) {
        super();
        this.userId = userId;
        this.email = email;
        this.rawToken = rawToken;
    }
}
