package com.mannschaft.app.auth.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * ログイン成功イベント。監査ログ記録等の後続処理をトリガーする。
 */
@Getter
public class LoginSuccessEvent extends BaseEvent {

    private final Long userId;
    private final String ipAddress;
    private final String userAgent;
    private final String method;

    public LoginSuccessEvent(Long userId, String ipAddress, String userAgent, String method) {
        super();
        this.userId = userId;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.method = method;
    }
}
