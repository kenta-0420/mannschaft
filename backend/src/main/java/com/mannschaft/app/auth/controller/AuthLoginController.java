package com.mannschaft.app.auth.controller;

import com.mannschaft.app.auth.service.AuthService;
import com.mannschaft.app.auth.dto.ConfirmPasswordResetRequest;
import com.mannschaft.app.auth.dto.LoginRequest;
import com.mannschaft.app.auth.dto.MessageResponse;
import com.mannschaft.app.auth.dto.RegisterRequest;
import com.mannschaft.app.auth.dto.ResendVerificationRequest;
import com.mannschaft.app.auth.dto.SessionResponse;
import com.mannschaft.app.auth.dto.TokenResponse;
import com.mannschaft.app.auth.dto.VerifyEmailRequest;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.mannschaft.app.auth.dto.UpdateSessionDeviceNameRequest;
import com.mannschaft.app.auth.service.AuthTokenService;

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
    private final AuthTokenService authTokenService;

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
            @Valid @RequestBody VerifyEmailRequest req) {

        return ResponseEntity.ok(authService.verifyEmail(req.getToken()));
    }

    /**
     * メール認証メール再送信。
     */
    @PostMapping("/verify-email/resend")
    @Operation(summary = "メール認証メール再送信")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "再送信完了")
    public ResponseEntity<ApiResponse<MessageResponse>> resendVerificationEmail(
            @Valid @RequestBody ResendVerificationRequest req) {

        return ResponseEntity.ok(authService.resendVerificationEmail(req.getEmail()));
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
     * 全デバイスからログアウト（F12.4）。
     * keepCurrent=true で現セッションを保持して他を一括無効化。デフォルト false（後方互換）。
     */
    @DeleteMapping("/sessions")
    @Operation(summary = "全デバイスからログアウト")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "全デバイスログアウト成功")
    public ResponseEntity<Void> logoutAllDevices(
            @CookieValue(name = "refresh_token", required = false) String rawRefreshToken,
            @RequestParam(required = false) Long currentSessionId,
            @RequestParam(required = false, defaultValue = "false") boolean keepCurrent) {

        Long userId = SecurityUtils.getCurrentUserId();
        String currentTokenHash = hashRefreshToken(rawRefreshToken);
        authService.logoutAllDevices(userId, currentTokenHash, currentSessionId, keepCurrent);
        return ResponseEntity.noContent().build();
    }

    /**
     * 特定デバイスからログアウト（F12.4）。
     * 現セッションの無効化は 409 Conflict で拒否する。
     */
    @DeleteMapping("/sessions/{id}")
    @Operation(summary = "特定デバイスからログアウト")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "デバイスログアウト成功")
    public ResponseEntity<Void> logoutDevice(
            @PathVariable Long id,
            @CookieValue(name = "refresh_token", required = false) String rawRefreshToken,
            @RequestParam(required = false) Long currentSessionId) {

        Long userId = SecurityUtils.getCurrentUserId();
        String currentTokenHash = hashRefreshToken(rawRefreshToken);
        authService.logoutDevice(userId, id, currentTokenHash, currentSessionId);
        return ResponseEntity.noContent().build();
    }

    /**
     * アクティブセッション一覧取得（F12.4）。
     * isCurrent=true を先頭、以降 lastUsedAt 降順でソートして返却する。
     */
    @GetMapping("/sessions")
    @Operation(summary = "アクティブセッション一覧取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<SessionResponse>>> getSessions(
            @CookieValue(name = "refresh_token", required = false) String rawRefreshToken,
            @RequestParam(required = false) Long currentSessionId) {

        Long userId = SecurityUtils.getCurrentUserId();
        String currentTokenHash = hashRefreshToken(rawRefreshToken);
        return ResponseEntity.ok(authService.getSessions(userId, currentTokenHash, currentSessionId));
    }

    /**
     * セッションのデバイス名変更（F12.4）。
     */
    @PatchMapping("/sessions/{id}")
    @Operation(summary = "セッションのデバイス名変更")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "デバイス名変更成功")
    public ResponseEntity<ApiResponse<SessionResponse>> updateSessionDeviceName(
            @PathVariable Long id,
            @Valid @RequestBody UpdateSessionDeviceNameRequest request) {

        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(authService.updateSessionDeviceName(userId, id, request.getDeviceName()));
    }

    /**
     * Refresh Token の SHA-256 ハッシュを計算する。null の場合は null を返す。
     */
    private String hashRefreshToken(String rawRefreshToken) {
        if (rawRefreshToken == null) {
            return null;
        }
        return authTokenService.hashToken(rawRefreshToken);
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
