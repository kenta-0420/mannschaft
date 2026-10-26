package com.mannschaft.app.auth.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * OAuth統合確認メール送信イベント。
 * AuthOAuthService.loginWithOAuth() でメール一致ユーザーへの OAuth 連携確認メールを送信した時に発行される。
 */
@Getter
public class OAuthLinkRequestedEvent extends BaseEvent {

    private final Long userId;
    private final String ipAddress;
    private final String userAgent;
    /** 連携確認リクエストの OAuthプロバイダ名（例: "GOOGLE", "LINE", "APPLE"）。 */
    private final String provider;

    public OAuthLinkRequestedEvent(Long userId, String ipAddress, String userAgent, String provider) {
        super();
        this.userId = userId;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.provider = provider;
    }
}
