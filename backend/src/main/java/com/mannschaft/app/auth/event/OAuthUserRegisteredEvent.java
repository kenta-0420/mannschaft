package com.mannschaft.app.auth.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * OAuth経由の新規ユーザー登録イベント。
 * AuthOAuthService.loginWithOAuth() で新規ユーザーを作成した時に発行される。
 */
@Getter
public class OAuthUserRegisteredEvent extends BaseEvent {

    private final Long userId;
    private final String ipAddress;
    private final String userAgent;
    /** 登録に使用した OAuthプロバイダ名（例: "GOOGLE", "LINE", "APPLE"）。 */
    private final String provider;

    public OAuthUserRegisteredEvent(Long userId, String ipAddress, String userAgent, String provider) {
        super();
        this.userId = userId;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.provider = provider;
    }
}
