package com.mannschaft.app.auth.controller;

import com.mannschaft.app.auth.service.AuthService;
import com.mannschaft.app.auth.dto.ConfirmPasswordResetRequest;
import com.mannschaft.app.auth.dto.LoginResponse;
import com.mannschaft.app.auth.dto.RequestPasswordResetRequest;
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
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
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
     * ログイン成功時（2FA なし）は access_token を HttpOnly Cookie にセットする。
     * 2FA が必要な場合は MfaRequiredResponse を返し、Cookie はセットしない。
     */
    @PostMapping("/login")
    @Operation(summary = "ログイン")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "ログイン成功")
    public ResponseEntity<ApiResponse<?>> login(
            @Valid @RequestBody LoginRequest req,
            HttpServletRequest request,
            HttpServletResponse response) {

        String ipAddress = com.mannschaft.app.common.IpAddressUtils.getClientIp(request);
        String userAgent = request.getHeader("User-Agent");
        ApiResponse<?> apiResponse = authService.login(req, ipAddress, userAgent);

        // 2FA が不要でトークンが発行された場合のみ Cookie をセット
        if (apiResponse.getData() instanceof LoginResponse loginResponse) {
            response.addHeader(HttpHeaders.SET_COOKIE,
                    buildAccessTokenCookie(loginResponse.getAccessToken()).toString());
        }

        return ResponseEntity.ok(apiResponse);
    }

    /**
     * ログアウト（単一デバイス）。
     * access_token Cookie を削除する。
     */
    @PostMapping("/logout")
    @Operation(summary = "ログアウト")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "ログアウト成功")
    public ResponseEntity<Void> logout(
            @CookieValue(name = "refresh_token_hash", required = false) String refreshTokenHash,
            @RequestParam(required = false) String jti,
            @RequestParam(required = false, defaultValue = "0") long exp,
            HttpServletResponse response) {

        authService.logout(refreshTokenHash, jti, exp);
        response.addHeader(HttpHeaders.SET_COOKIE, clearAccessTokenCookie().toString());
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
     * access_token HttpOnly Cookie を生成する。
     * secure=false は開発環境用（HTTP を許可）。本番環境では HTTPS を前提に true に変更すること。
     *
     * @param token アクセストークン（JWT）
     * @return ResponseCookie
     */
    private ResponseCookie buildAccessTokenCookie(String token) {
        return ResponseCookie.from("access_token", token)
                .httpOnly(true)
                .secure(false)  // TODO: 本番環境では環境変数で true に切り替える
                .path("/")
                .sameSite("Strict")
                .maxAge(15 * 60)  // 15分（JWT アクセストークンの有効期限に合わせる）
                .build();
    }

    /**
     * access_token Cookie を削除するための Set-Cookie ヘッダーを生成する。
     * maxAge=0 を設定してブラウザに Cookie の即時削除を指示する。
     *
     * @return ResponseCookie（maxAge=0）
     */
    private ResponseCookie clearAccessTokenCookie() {
        return ResponseCookie.from("access_token", "")
                .httpOnly(true)
                .path("/")
                .maxAge(0)
                .build();
    }

    /**
     * アクセストークンリフレッシュ。
     * リフレッシュ成功時は新しい access_token を HttpOnly Cookie にセットする。
     */
    @PostMapping("/refresh")
    @Operation(summary = "アクセストークンリフレッシュ")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "リフレッシュ成功")
    public ResponseEntity<ApiResponse<TokenResponse>> refresh(
            @CookieValue(name = "refresh_token", required = false) String rawRefreshToken,
            @RequestParam(required = false) String deviceFingerprint,
            HttpServletResponse response) {

        ApiResponse<TokenResponse> apiResponse = authService.refreshAccessToken(rawRefreshToken, deviceFingerprint);
        if (apiResponse.getData() != null && apiResponse.getData().getAccessToken() != null) {
            response.addHeader(HttpHeaders.SET_COOKIE,
                    buildAccessTokenCookie(apiResponse.getData().getAccessToken()).toString());
        }
        return ResponseEntity.ok(apiResponse);
    }

    /**
     * パスワードリセット要求。
     */
    @PostMapping("/password-reset/request")
    @Operation(summary = "パスワードリセット要求")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "リセットメール送信完了")
    public ResponseEntity<ApiResponse<MessageResponse>> requestPasswordReset(
            @Valid @RequestBody RequestPasswordResetRequest req,
            HttpServletRequest request) {

        String ipAddress = com.mannschaft.app.common.IpAddressUtils.getClientIp(request);
        return ResponseEntity.ok(authService.requestPasswordReset(req.getEmail(), ipAddress));
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
