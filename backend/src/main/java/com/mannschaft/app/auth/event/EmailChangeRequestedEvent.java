package com.mannschaft.app.auth.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * メールアドレス変更要求イベント。確認メール送信をトリガーする。
 */
@Getter
public class EmailChangeRequestedEvent extends BaseEvent {

    private final Long userId;
    private final String newEmail;
    private final String rawToken;

    public EmailChangeRequestedEvent(Long userId, String newEmail, String rawToken) {
        super();
        this.userId = userId;
        this.newEmail = newEmail;
        this.rawToken = rawToken;
    }
}
