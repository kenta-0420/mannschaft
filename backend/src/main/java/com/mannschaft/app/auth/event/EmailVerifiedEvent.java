package com.mannschaft.app.auth.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * メールアドレス確認完了イベント。
 */
@Getter
public class EmailVerifiedEvent extends BaseEvent {

    private final Long userId;
    private final String email;

    public EmailVerifiedEvent(Long userId, String email) {
        super();
        this.userId = userId;
        this.email = email;
    }
}
