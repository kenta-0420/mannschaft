package com.mannschaft.app.auth.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * 多要素認証（MFA）無効化イベント。
 */
@Getter
public class MfaDisabledEvent extends BaseEvent {

    private final Long userId;

    public MfaDisabledEvent(Long userId) {
        super();
        this.userId = userId;
    }
}
