package com.mannschaft.app.auth.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * パスワード初期設定イベント。
 * UserService.setupPassword() 実行時に発行される。
 * OAuth専用ユーザーが初めてパスワードを設定する際のイベント。
 */
@Getter
public class PasswordSetupEvent extends BaseEvent {

    private final Long userId;

    public PasswordSetupEvent(Long userId) {
        super();
        this.userId = userId;
    }
}
