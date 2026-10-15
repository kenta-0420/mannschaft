package com.mannschaft.app.auth.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * ログイン失敗イベント。アカウントロック判定等の後続処理をトリガーする。
 */
@Getter
public class LoginFailedEvent extends BaseEvent {

    private final String email;
    private final String ipAddress;
    private final String userAgent;
    private final String reason;

    public LoginFailedEvent(String email, String ipAddress, String userAgent, String reason) {
        super();
        this.email = email;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.reason = reason;
    }
}
