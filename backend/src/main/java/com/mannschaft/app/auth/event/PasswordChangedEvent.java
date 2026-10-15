package com.mannschaft.app.auth.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * パスワード変更完了イベント。セッション無効化等の後続処理をトリガーする。
 */
@Getter
public class PasswordChangedEvent extends BaseEvent {

    private final Long userId;
    private final String ipAddress;

    public PasswordChangedEvent(Long userId, String ipAddress) {
        super();
        this.userId = userId;
        this.ipAddress = ipAddress;
    }
}
