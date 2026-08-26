package com.mannschaft.app.auth.controller;

import com.mannschaft.app.auth.dto.OAuthLinkAuthUrlResponse;
import com.mannschaft.app.auth.service.AuthOAuthLinkService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.SelfScopedEndpoint;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 設定画面からの OAuth 連携開始エンドポイント。
 * <p>
 * {@code GET /api/v1/users/me/oauth/link/{provider}/auth-url} で
 * ログイン済みユーザーの OAuth 連携認可 URL を返す。
 */
@RestController
@RequestMapping("/api/v1/users/me/oauth/link")
@RequiredArgsConstructor
public class UserOAuthLinkController {

    private final AuthOAuthLinkService authOAuthLinkService;

    /**
     * 指定プロバイダの OAuth 認可 URL を取得する。
     * <p>
     * AC-1: 正常系 → 200 + {@code { data: { authUrl } }}<br>
     * AC-2: 未認証 → 401（{@link SecurityUtils#getCurrentUserId()} が COMMON_000 を投げる）<br>
     * AC-3: 既連携 → 409（AuthOAuthLinkService が AUTH_034 を投げる）<br>
     * AC-4: 未サポートプロバイダ → 400（AuthOAuthLinkService が AUTH_028 を投げる）
     *
     * <p><b>認可方式（{@link com.mannschaft.app.common.security.SelfScopedEndpoint} メソッド付与）</b>:
     * パス変数 {@code provider} はプロバイダ種別を指すのみで他ユーザーを指す識別子ではなく、
     * 連携先ユーザーは {@code SecurityUtils.getCurrentUserId()} のみで解決される。</p>
     *
     * <p>認可根治戦役 Wave5 監査済。</p>
     *
     * @param provider        プロバイダ識別子（例: {@code GOOGLE}）
     * @param includeCalendar true の場合 Google Calendar スコープを追加し、コールバックでGCal接続も確立する（デフォルト: false）
     * @return 認可 URL レスポンス
     */
    @SelfScopedEndpoint(
            "SecurityUtils.getCurrentUserId() のみで連携対象ユーザーを解決し、"
                    + "provider はプロバイダ種別に過ぎない（UserOAuthLinkController#getAuthUrl）")
    @GetMapping("/{provider}/auth-url")
    public ResponseEntity<ApiResponse<OAuthLinkAuthUrlResponse>> getAuthUrl(
            @PathVariable String provider,
            @RequestParam(defaultValue = "false") boolean includeCalendar) {
        Long userId = SecurityUtils.getCurrentUserId();
        String authUrl = authOAuthLinkService.generateAuthUrl(userId, provider.toUpperCase(), includeCalendar);
        return ResponseEntity.ok(ApiResponse.of(new OAuthLinkAuthUrlResponse(authUrl)));
    }

    /**
     * Google Calendar 専用の OAuth 認可 URL を取得する。
     * OAuthAccount が既に連携済みでも使用可能（Google Calendar スコープを追加）。
     * コールバック後は OAuthAccount 作成をスキップし、カレンダー接続のみ確立する。
     *
     * <p><b>認可方式（{@link com.mannschaft.app.common.security.SelfScopedEndpoint} メソッド付与）</b>:
     * 対象ユーザーは {@code SecurityUtils.getCurrentUserId()} のみで解決され、リクエストに
     * 他ユーザーを指す識別子は含まれない（プロバイダは {@code GOOGLE} 固定でパス自体に埋め込み）。</p>
     *
     * <p>認可根治戦役 Wave5 監査済。</p>
     *
     * @return 認可 URL レスポンス
     */
    @SelfScopedEndpoint(
            "SecurityUtils.getCurrentUserId() のみで連携対象ユーザーを解決する"
                    + "（UserOAuthLinkController#getCalendarOnlyAuthUrl）")
    @GetMapping("/GOOGLE/calendar-only-auth-url")
    public ResponseEntity<ApiResponse<OAuthLinkAuthUrlResponse>> getCalendarOnlyAuthUrl() {
        Long userId = SecurityUtils.getCurrentUserId();
        String authUrl = authOAuthLinkService.generateCalendarOnlyAuthUrl(userId);
        return ResponseEntity.ok(ApiResponse.of(new OAuthLinkAuthUrlResponse(authUrl)));
    }
}
