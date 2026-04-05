package com.mannschaft.app.auth.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * メールアドレス変更完了イベント。旧メールアドレスへの通知等をトリガーする。
 */
@Getter
public class EmailChangedEvent extends BaseEvent {

    private final Long userId;
    private final String oldEmail;
    private final String newEmail;

    public EmailChangedEvent(Long userId, String oldEmail, String newEmail) {
        super();
        this.userId = userId;
        this.oldEmail = oldEmail;
        this.newEmail = newEmail;
    }
}
