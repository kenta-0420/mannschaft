package com.mannschaft.app.auth.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * OAuthプロバイダー連携完了イベント。
 */
@Getter
public class OAuthLinkedEvent extends BaseEvent {

    private final Long userId;
    private final String provider;

    public OAuthLinkedEvent(Long userId, String provider) {
        super();
        this.userId = userId;
        this.provider = provider;
    }
}
