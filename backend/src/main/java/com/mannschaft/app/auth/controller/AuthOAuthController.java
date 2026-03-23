package com.mannschaft.app.auth.controller;

import com.mannschaft.app.auth.service.AuthOAuthService;
import com.mannschaft.app.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * OAuth認証コントローラー。
 * OAuthプロバイダ連携によるログイン・アカウント連携確認のエンドポイントを提供する。
 */
@RestController
@RequestMapping("/api/v1/auth/oauth")
@Tag(name = "OAuth")
@RequiredArgsConstructor
public class AuthOAuthController {

    private final AuthOAuthService authOAuthService;

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
