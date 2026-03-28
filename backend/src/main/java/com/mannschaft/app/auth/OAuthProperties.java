package com.mannschaft.app.auth;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * OAuth各プロバイダの設定値を保持するコンポーネント。
 * application.yml の mannschaft.google / mannschaft.line / mannschaft.apple セクションにマップされる。
 */
@Getter
@Component
public class OAuthProperties {

    // ---- Google ----
    private final String googleClientId;
    private final String googleClientSecret;
    private final String googleTokenUri;
    private final String googleUserinfoUri;
    private final String googleRedirectUri;

    // ---- LINE ----
    private final String lineClientId;
    private final String lineClientSecret;
    private final String lineTokenUri;
    private final String lineProfileUri;
    private final String lineRedirectUri;

    // ---- Apple ----
    private final String appleClientId;
    private final String appleClientSecret;
    private final String appleTokenUri;
    private final String appleRedirectUri;

    public OAuthProperties(
            @Value("${mannschaft.google.client-id:}") String googleClientId,
            @Value("${mannschaft.google.client-secret:}") String googleClientSecret,
            @Value("${mannschaft.google.token-uri:https://oauth2.googleapis.com/token}") String googleTokenUri,
            @Value("${mannschaft.google.userinfo-uri:https://www.googleapis.com/oauth2/v3/userinfo}") String googleUserinfoUri,
            @Value("${mannschaft.google.redirect-uri:}") String googleRedirectUri,
            @Value("${mannschaft.line.client-id:}") String lineClientId,
            @Value("${mannschaft.line.client-secret:}") String lineClientSecret,
            @Value("${mannschaft.line.token-uri:https://api.line.me/oauth2/v2.1/token}") String lineTokenUri,
            @Value("${mannschaft.line.profile-uri:https://api.line.me/v2/profile}") String lineProfileUri,
            @Value("${mannschaft.line.redirect-uri:}") String lineRedirectUri,
            @Value("${mannschaft.apple.client-id:}") String appleClientId,
            @Value("${mannschaft.apple.client-secret:}") String appleClientSecret,
            @Value("${mannschaft.apple.token-uri:https://appleid.apple.com/auth/token}") String appleTokenUri,
            @Value("${mannschaft.apple.redirect-uri:}") String appleRedirectUri) {
        this.googleClientId = googleClientId;
        this.googleClientSecret = googleClientSecret;
        this.googleTokenUri = googleTokenUri;
        this.googleUserinfoUri = googleUserinfoUri;
        this.googleRedirectUri = googleRedirectUri;
        this.lineClientId = lineClientId;
        this.lineClientSecret = lineClientSecret;
        this.lineTokenUri = lineTokenUri;
        this.lineProfileUri = lineProfileUri;
        this.lineRedirectUri = lineRedirectUri;
        this.appleClientId = appleClientId;
        this.appleClientSecret = appleClientSecret;
        this.appleTokenUri = appleTokenUri;
        this.appleRedirectUri = appleRedirectUri;
    }
}
