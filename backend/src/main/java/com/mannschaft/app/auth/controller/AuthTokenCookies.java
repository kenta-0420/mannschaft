package com.mannschaft.app.auth.controller;

import org.springframework.http.ResponseCookie;

/**
 * パスワード・パスキーで同じ属性の認証Cookieを組み立てる。
 * 寿命は呼出元が既存のJWT設定値を渡し、削除時も同じ属性を使用する。
 */
final class AuthTokenCookies {

    private AuthTokenCookies() {
    }

    static ResponseCookie accessToken(String token, boolean secure, long maxAge) {
        return build("access_token", token, secure, maxAge);
    }

    static ResponseCookie refreshToken(String token, boolean secure, long maxAge) {
        return build("refresh_token", token, secure, maxAge);
    }

    private static ResponseCookie build(String name, String token, boolean secure, long maxAge) {
        return ResponseCookie.from(name, token)
                .httpOnly(true)
                .secure(secure)
                .path("/")
                .sameSite("Strict")
                .maxAge(maxAge)
                .build();
    }
}
