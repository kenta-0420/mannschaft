package com.mannschaft.app.auth.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * パスワードリセット要求イベント。リセットメール送信をトリガーする。
 */
@Getter
public class PasswordResetRequestedEvent extends BaseEvent {

    private final Long userId;
    private final String email;
    private final String rawToken;

    public PasswordResetRequestedEvent(Long userId, String email, String rawToken) {
        super();
        this.userId = userId;
        this.email = email;
        this.rawToken = rawToken;
    }
}
