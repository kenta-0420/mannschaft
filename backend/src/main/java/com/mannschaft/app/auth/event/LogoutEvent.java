package com.mannschaft.app.auth.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * ログアウトイベント。
 */
@Getter
public class LogoutEvent extends BaseEvent {

    private final Long userId;
    private final int deviceCount;

    public LogoutEvent(Long userId, int deviceCount) {
        super();
        this.userId = userId;
        this.deviceCount = deviceCount;
    }
}
