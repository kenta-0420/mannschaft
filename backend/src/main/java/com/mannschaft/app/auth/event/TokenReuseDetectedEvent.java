package com.mannschaft.app.auth.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * Refresh Tokenリプレイ攻撃検出イベント。
 * refreshAccessToken() で既に revoke 済みのトークンが使用された時に発行される。
 */
@Getter
public class TokenReuseDetectedEvent extends BaseEvent {

    private final Long userId;
    /** 再使用が検出されたトークンの DB ID。 */
    private final Long tokenId;

    public TokenReuseDetectedEvent(Long userId, Long tokenId) {
        super();
        this.userId = userId;
        this.tokenId = tokenId;
    }
}
