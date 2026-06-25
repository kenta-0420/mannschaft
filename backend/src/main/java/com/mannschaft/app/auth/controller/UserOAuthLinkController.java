package com.mannschaft.app.auth.controller;

import com.mannschaft.app.auth.dto.OAuthLinkAuthUrlResponse;
import com.mannschaft.app.auth.service.AuthOAuthLinkService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
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
     * @param provider プロバイダ識別子（例: {@code GOOGLE}）
     * @return 認可 URL レスポンス
     */
    @GetMapping("/{provider}/auth-url")
    public ResponseEntity<ApiResponse<OAuthLinkAuthUrlResponse>> getAuthUrl(
            @PathVariable String provider) {
        Long userId = SecurityUtils.getCurrentUserId();
        String authUrl = authOAuthLinkService.generateAuthUrl(userId, provider.toUpperCase());
        return ResponseEntity.ok(ApiResponse.of(new OAuthLinkAuthUrlResponse(authUrl)));
    }
}
