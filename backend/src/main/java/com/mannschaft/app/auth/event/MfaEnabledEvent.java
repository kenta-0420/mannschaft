package com.mannschaft.app.auth.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * 多要素認証（MFA）有効化イベント。
 */
@Getter
public class MfaEnabledEvent extends BaseEvent {

    private final Long userId;

    public MfaEnabledEvent(Long userId) {
        super();
        this.userId = userId;
    }
}
