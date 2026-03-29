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
    private final String displayName;
    private final String rawToken;

    public UserRegisteredEvent(Long userId, String email, String displayName, String rawToken) {
        super();
        this.userId = userId;
        this.email = email;
        this.displayName = displayName;
        this.rawToken = rawToken;
    }
}
