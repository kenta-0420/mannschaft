package com.mannschaft.app.auth.dto;

import java.time.OffsetDateTime;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * WebAuthn 再認証完了レスポンス。
 *
 * <p>F18 提示モード追加保護（設計書 §9.6 / POINT_CARD_009）で使用する。
 * 既存の {@link WebAuthnLoginCompleteRequest} 系と異なり、Access Token / Refresh Token を
 * 返さず、サーバー側で 5 分間有効な「再認証済みフラグ」をマークしたことを示すために
 * 期限のみを返す。フロントはこのレスポンスを受け取った後すぐに提示モード起動 API を呼ぶ。
 */
@Getter
@RequiredArgsConstructor
public class WebAuthnReauthenticateCompleteResponse {

    /** 再認証フラグが有効な期限（UTC offset 付き）。クライアントは表示のみに使う。 */
    private final OffsetDateTime verifiedUntil;
}
