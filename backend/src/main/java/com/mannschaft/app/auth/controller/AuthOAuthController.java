package com.mannschaft.app.auth.controller;

import com.mannschaft.app.auth.service.AuthOAuthService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.security.IntentionallyPublic;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * OAuth認証コントローラー。
 * OAuthプロバイダ連携によるログイン・アカウント連携確認のエンドポイントを提供する。
 *
 * <p><b>公開根拠（{@link IntentionallyPublic} クラス付与・凍結ストア該当 3 EP）</b>:
 * 本 Controller の全 Mapping エンドポイントは {@code SecurityConfig} で
 * {@code permitAll()} 済み。</p>
 *
 * <p><b>根拠</b>:
 * SecurityConfig — requestMatchers("/api/v1/auth/oauth/**").permitAll()
 * </p>
 *
 * <p><b>公開してよいと判断した理由</b>:
 * OAuth ログインは<b>認証を確立するための入口</b>であり、認証前に未認証で到達できなければ機能しない。資格情報（認可コード・ID
 * トークン）の検証は認証処理そのものが行う。
 * </p>
 *
 * <p>認可根治戦役 Wave5 監査済。レスポンス項目が将来増えた場合は公開の妥当性が崩れうるため、
 * 当該 DTO の変更時は本注釈の妥当性を再評価すること。</p>
 */
@IntentionallyPublic("/api/v1/auth/oauth/**")
@RestController
@RequestMapping("/api/v1/auth/oauth")
@Tag(name = "OAuth")
@RequiredArgsConstructor
public class AuthOAuthController {

    private final AuthOAuthService authOAuthService;

    /**
     * Google OAuth 認証 URL を取得する。
     * 認証不要エンドポイント。フロントエンドはこの URL に遷移してOAuth認証フローを開始する。
     */
    @GetMapping("/google/auth-url")
    @Operation(summary = "Google OAuth 認証URL取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Google OAuth 認証URL")
    public ResponseEntity<ApiResponse<Map<String, String>>> getGoogleAuthUrl() {
        String authUrl = authOAuthService.generateGoogleLoginAuthUrl();
        return ResponseEntity.ok(ApiResponse.of(Map.of("authUrl", authUrl)));
    }

    /**
     * OAuthプロバイダを使用してログインする。
     */
    @PostMapping("/{provider}")
    @Operation(summary = "OAuthログイン")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "ログイン成功またはアカウント競合")
    public ResponseEntity<ApiResponse<?>> loginWithOAuth(
            @PathVariable String provider,
            @RequestParam String code,
            HttpServletRequest request) {

        String ipAddress = com.mannschaft.app.common.IpAddressUtils.getClientIp(request);
        String userAgent = request.getHeader("User-Agent");
        return ResponseEntity.ok(authOAuthService.loginWithOAuth(
                provider, code, ipAddress, userAgent));
    }

    /**
     * OAuth連携を確認する。連携トークンを検証し、アカウントを連携してトークンを発行する。
     */
    @PostMapping("/link/confirm")
    @Operation(summary = "OAuth連携確認")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "連携完了")
    public ResponseEntity<ApiResponse<?>> confirmOAuthLinkage(
            @RequestParam String token) {

        return ResponseEntity.ok(authOAuthService.confirmOAuthLinkage(token));
    }
}
