package com.mannschaft.app.auth.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * パスワードリセット完了イベント。
 */
@Getter
public class PasswordResetCompletedEvent extends BaseEvent {

    private final Long userId;
    private final String email;

    public PasswordResetCompletedEvent(Long userId, String email) {
        super();
        this.userId = userId;
        this.email = email;
    }
}
