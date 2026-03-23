package com.mannschaft.app.auth.controller;

import com.mannschaft.app.auth.service.AuthService;
import com.mannschaft.app.auth.dto.ConfirmPasswordResetRequest;
import com.mannschaft.app.auth.dto.LoginRequest;
import com.mannschaft.app.auth.dto.MessageResponse;
import com.mannschaft.app.auth.dto.RegisterRequest;
import com.mannschaft.app.auth.dto.SessionResponse;
import com.mannschaft.app.auth.dto.TokenResponse;
import com.mannschaft.app.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import com.mannschaft.app.common.SecurityUtils;

/**
 * 認証コアコントローラー。
 * ユーザー登録・ログイン・ログアウト・セッション管理・トークンリフレッシュ・パスワードリセットのエンドポイントを提供する。
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "認証")
@RequiredArgsConstructor
public class AuthLoginController {

    private final AuthService authService;

    /**
     * ユーザー登録。
     */
    @PostMapping("/register")
    @Operation(summary = "ユーザー登録")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "登録成功")
    public ResponseEntity<ApiResponse<MessageResponse>> register(
            @Valid @RequestBody RegisterRequest req,
            HttpServletRequest request) {

        String ipAddress = com.mannschaft.app.common.IpAddressUtils.getClientIp(request);
        ApiResponse<MessageResponse> response = authService.register(req, ipAddress);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * メール認証。
     */
    @PostMapping("/verify-email")
    @Operation(summary = "メール認証トークン検証")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "認証成功")
    public ResponseEntity<ApiResponse<MessageResponse>> verifyEmail(
            @RequestParam String token) {

        return ResponseEntity.ok(authService.verifyEmail(token));
    }

    /**
     * メール認証メール再送信。
     */
    @PostMapping("/verify-email/resend")
    @Operation(summary = "メール認証メール再送信")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "再送信完了")
    public ResponseEntity<ApiResponse<MessageResponse>> resendVerificationEmail(
            @RequestParam String email) {

        return ResponseEntity.ok(authService.resendVerificationEmail(email));
    }

    /**
     * ログイン。
     */
    @PostMapping("/login")
    @Operation(summary = "ログイン")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "ログイン成功")
    public ResponseEntity<ApiResponse<?>> login(
            @Valid @RequestBody LoginRequest req,
            HttpServletRequest request) {

        String ipAddress = com.mannschaft.app.common.IpAddressUtils.getClientIp(request);
        String userAgent = request.getHeader("User-Agent");
        return ResponseEntity.ok(authService.login(req, ipAddress, userAgent));
    }

    /**
     * ログアウト（単一デバイス）。
     */
    @PostMapping("/logout")
    @Operation(summary = "ログアウト")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "ログアウト成功")
    public ResponseEntity<Void> logout(
            @CookieValue(name = "refresh_token_hash", required = false) String refreshTokenHash,
            @RequestParam(required = false) String jti,
            @RequestParam(required = false, defaultValue = "0") long exp) {

        authService.logout(refreshTokenHash, jti, exp);
        return ResponseEntity.ok().build();
    }

    /**
     * 全デバイスからログアウト。
     */
    @DeleteMapping("/sessions")
    @Operation(summary = "全デバイスからログアウト")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "全デバイスログアウト成功")
    public ResponseEntity<Void> logoutAllDevices() {

        Long userId = SecurityUtils.getCurrentUserId();
        authService.logoutAllDevices(userId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 特定デバイスからログアウト。
     */
    @DeleteMapping("/sessions/{id}")
    @Operation(summary = "特定デバイスからログアウト")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "デバイスログアウト成功")
    public ResponseEntity<Void> logoutDevice(@PathVariable Long id) {

        Long userId = SecurityUtils.getCurrentUserId();
        authService.logoutDevice(userId, id);
        return ResponseEntity.noContent().build();
    }

    /**
     * アクティブセッション一覧取得。
     */
    @GetMapping("/sessions")
    @Operation(summary = "アクティブセッション一覧取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<SessionResponse>>> getSessions() {

        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(authService.getSessions(userId));
    }

    /**
     * アクセストークンリフレッシュ。
     */
    @PostMapping("/refresh")
    @Operation(summary = "アクセストークンリフレッシュ")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "リフレッシュ成功")
    public ResponseEntity<ApiResponse<TokenResponse>> refresh(
            @CookieValue(name = "refresh_token", required = false) String rawRefreshToken,
            @RequestParam(required = false) String deviceFingerprint) {

        return ResponseEntity.ok(authService.refreshAccessToken(rawRefreshToken, deviceFingerprint));
    }

    /**
     * パスワードリセット要求。
     */
    @PostMapping("/password-reset/request")
    @Operation(summary = "パスワードリセット要求")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "リセットメール送信完了")
    public ResponseEntity<ApiResponse<MessageResponse>> requestPasswordReset(
            @RequestParam String email,
            HttpServletRequest request) {

        String ipAddress = com.mannschaft.app.common.IpAddressUtils.getClientIp(request);
        return ResponseEntity.ok(authService.requestPasswordReset(email, ipAddress));
    }

    /**
     * パスワードリセット確認。
     */
    @PostMapping("/password-reset/confirm")
    @Operation(summary = "パスワードリセット確認")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "パスワードリセット完了")
    public ResponseEntity<ApiResponse<MessageResponse>> confirmPasswordReset(
            @Valid @RequestBody ConfirmPasswordResetRequest req) {

        return ResponseEntity.ok(authService.confirmPasswordReset(req));
    }
}
