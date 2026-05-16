package com.mannschaft.app.auth.dto;

import java.util.List;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * WebAuthn 再認証開始時のチャレンジレスポンス。
 *
 * <p>F18 個人ポイントカードウォレットの提示モード追加保護
 * （設計書 §9.6 / POINT_CARD_009）で使用する。
 * 既存の {@link WebAuthnLoginBeginResponse} と異なり、メールアドレスを取らず
 * 認証済みユーザー本人の WebAuthn credential 一覧を返す。
 *
 * <p>{@code allowCredentials} は文字列の credential ID リストで、フロントは
 * Base64URL → ArrayBuffer 変換して {@code navigator.credentials.get} に渡す。
 */
@Getter
@RequiredArgsConstructor
public class WebAuthnReauthenticateBeginResponse {

    private final String challenge;
    private final String rpId;
    private final List<String> allowCredentials;
    private final Long userId;
    private final long timeout;
}
