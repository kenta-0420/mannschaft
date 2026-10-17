package com.mannschaft.app.common.security;

/**
 * JWT署名検証直後のトークン所有者と失効情報。
 * 管理者変身によるprincipalの置換とは独立して、本人credentialのログアウトに使用する。
 * 生トークンは保持せず、HTTPクライアントが設定できないrequest属性にだけ保存する。
 */
public record VerifiedAccessToken(Long userId, String jti, long expiresAtEpochSecond) {
    public static final String REQUEST_ATTRIBUTE = VerifiedAccessToken.class.getName();
}
