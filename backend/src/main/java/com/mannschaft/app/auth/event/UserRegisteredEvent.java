package com.mannschaft.app.auth.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * ユーザー新規登録完了イベント。確認メール送信等の後続処理をトリガーする。
 */
@Getter
public class UserRegisteredEvent extends BaseEvent {

    private final Long userId;
    private final String email;
    private final String locale;
    private final String timezone;

    public UserRegisteredEvent(Long userId, String email, String locale, String timezone) {
        super();
        this.userId = userId;
        this.email = email;
        this.locale = locale;
        this.timezone = timezone;
    }
}
