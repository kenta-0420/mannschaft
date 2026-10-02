package com.mannschaft.app.auth.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * OAuth 連携用の認可 URL レスポンス DTO。
 * <p>
 * 試練(red)用スタブ。{@code /settings/linked-accounts} の Google OAuth 連携ボタンが
 * 叩く認可URL生成エンドポイントの戻り値を表す。
 */
@Getter
@RequiredArgsConstructor
public class OAuthLinkAuthUrlResponse {

    /** 生成された OAuth 認可 URL（Google の認可エンドポイント + クエリ）。 */
    private final String authUrl;
}
