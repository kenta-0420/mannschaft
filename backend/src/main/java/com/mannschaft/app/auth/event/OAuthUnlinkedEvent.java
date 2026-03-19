package com.mannschaft.app.auth.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * OAuthプロバイダー連携解除イベント。
 */
@Getter
public class OAuthUnlinkedEvent extends BaseEvent {

    private final Long userId;
    private final String provider;

    public OAuthUnlinkedEvent(Long userId, String provider) {
        super();
        this.userId = userId;
        this.provider = provider;
    }
}
