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
import com.mannschaft.app.common.security.IntentionallyPublic;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
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
     * Cookie の Secure 属性。本番（HTTPS）では true、ローカル開発（HTTP）では false。
     * 環境変数 MANNSCHAFT_COOKIE_SECURE で制御する。
     * 設計書: docs/security/02_cookie_and_session.md §2 / §2.1
     */
    @Value("${mannschaft.cookie.secure:false}")
    private boolean cookieSecure;

    /**
     * ユーザー登録。
     *
     * <p><b>公開根拠（{@link IntentionallyPublic} メソッド付与）</b>:
     * 本エンドポイントは {@code SecurityConfig} で {@code permitAll()} 済み。</p>
     *
     * <p><b>根拠</b>:
     * SecurityConfig.java:209-212 — requestMatchers("/api/v1/auth/login", "/api/v1/auth/register",
     * "/api/v1/auth/refresh", "/api/v1/auth/password-reset/**").permitAll()
     * </p>
     *
     * <p><b>公開してよいと判断した理由</b>:
     * ログイン・新規登録・トークン更新・パスワードリセットは<b>認証を確立する（または認証手段を回復する）ための入口</b>
     * であり、認証前に未認証で到達できなければ機能しない。資格情報・リセットトークンの検証は認証処理そのものが行う。<b>クラス付与は不可</b>
     * : 同クラスの {@code logout} / {@code getSessions} / {@code logoutDevice}
     * / {@code logoutAllDevices} / {@code updateSessionDeviceName}
     * / {@code verifyEmail} / {@code resendVerificationEmail} は認証必須のためクラスへ貼ると誤った証跡になる。
     * </p>
     *
     * <p>認可根治戦役 Wave5 監査済。</p>
     */
    @IntentionallyPublic
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
     *
     * <p><b>公開根拠（{@link IntentionallyPublic} メソッド付与）</b>:
     * 本エンドポイントは {@code SecurityConfig} で {@code permitAll()} 済み。</p>
     *
     * <p><b>根拠</b>:
     * SecurityConfig.java:209-212 — requestMatchers("/api/v1/auth/login", "/api/v1/auth/register",
     * "/api/v1/auth/refresh", "/api/v1/auth/password-reset/**").permitAll()
     * </p>
     *
     * <p><b>公開してよいと判断した理由</b>:
     * ログイン・新規登録・トークン更新・パスワードリセットは<b>認証を確立する（または認証手段を回復する）ための入口</b>
     * であり、認証前に未認証で到達できなければ機能しない。資格情報・リセットトークンの検証は認証処理そのものが行う。<b>クラス付与は不可</b>
     * : 同クラスの {@code logout} / {@code getSessions} / {@code logoutDevice}
     * / {@code logoutAllDevices} / {@code updateSessionDeviceName}
     * / {@code verifyEmail} / {@code resendVerificationEmail} は認証必須のためクラスへ貼ると誤った証跡になる。
     * </p>
     *
     * <p>認可根治戦役 Wave5 監査済。</p>
     */
    @IntentionallyPublic
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
            // refresh_token もデュアルモードで Cookie 発行（body にも残しモバイル互換を維持）
            // F01.1 §203 / docs/security/02_cookie_and_session.md §3
            if (loginResponse.getRefreshToken() != null) {
                response.addHeader(HttpHeaders.SET_COOKIE,
                        buildRefreshTokenCookie(loginResponse.getRefreshToken()).toString());
            }
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
        // refresh_token Cookie もクリア（発行と属性を揃える）
        response.addHeader(HttpHeaders.SET_COOKIE, clearRefreshTokenCookie().toString());
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
     * Secure 属性は {@code mannschaft.cookie.secure}（環境変数 MANNSCHAFT_COOKIE_SECURE）で制御する。
     * 本番は true（HTTPS 必須）、ローカル開発は false（HTTP 許可）。
     * 設計書: docs/security/02_cookie_and_session.md §2
     *
     * @param token アクセストークン（JWT）
     * @return ResponseCookie
     */
    private ResponseCookie buildAccessTokenCookie(String token) {
        return ResponseCookie.from("access_token", token)
                .httpOnly(true)
                .secure(cookieSecure)
                .path("/")
                .sameSite("Strict")
                .maxAge(15 * 60)  // 15分（JWT アクセストークンの有効期限に合わせる）
                .build();
    }

    /**
     * access_token Cookie を削除するための Set-Cookie ヘッダーを生成する。
     * maxAge=0 を設定してブラウザに Cookie の即時削除を指示する。
     * <p>
     * ブラウザは同名 Cookie でも属性（特に Secure / SameSite / Path）が発行時と異なると
     * 削除に失敗することがあるため、発行側（{@link #buildAccessTokenCookie}）と属性を揃える。
     * 設計書: docs/security/02_cookie_and_session.md §2.2
     *
     * @return ResponseCookie（maxAge=0）
     */
    private ResponseCookie clearAccessTokenCookie() {
        return ResponseCookie.from("access_token", "")
                .httpOnly(true)
                .secure(cookieSecure)
                .path("/")
                .sameSite("Strict")
                .maxAge(0)
                .build();
    }

    /**
     * refresh_token HttpOnly Cookie を生成する。
     * <p>
     * F01.1 のデュアルモード設計（Web=Cookie / モバイル=レスポンスボディ）に従い、
     * login / refresh 成功時に refresh_token を Set-Cookie で発行する。body での返却は
     * モバイル互換のため維持する（削除しない）。
     * <p>
     * Secure 属性は access_token と同じく {@code mannschaft.cookie.secure}（環境変数
     * MANNSCHAFT_COOKIE_SECURE）で制御する。maxAge は Refresh Token の有効期限
     * （{@link AuthTokenService#getRefreshTokenExpirationSeconds()}、既定 604800 秒=7日）に揃える。
     * これにより Cookie の寿命と DB トークンの有効期限が一致する。
     * 設計書: docs/security/02_cookie_and_session.md §2 / docs/features/F01.1_auth.md §203
     *
     * @param token リフレッシュトークン（平文）
     * @return ResponseCookie
     */
    private ResponseCookie buildRefreshTokenCookie(String token) {
        return ResponseCookie.from("refresh_token", token)
                .httpOnly(true)
                .secure(cookieSecure)
                .path("/")
                .sameSite("Strict")
                .maxAge(authTokenService.getRefreshTokenExpirationSeconds())
                .build();
    }

    /**
     * refresh_token Cookie を削除するための Set-Cookie ヘッダーを生成する。
     * maxAge=0 を設定してブラウザに Cookie の即時削除を指示する。
     * 発行側（{@link #buildRefreshTokenCookie}）と属性（Secure / SameSite / Path）を揃える。
     * 設計書: docs/security/02_cookie_and_session.md §2.2
     *
     * @return ResponseCookie（maxAge=0）
     */
    private ResponseCookie clearRefreshTokenCookie() {
        return ResponseCookie.from("refresh_token", "")
                .httpOnly(true)
                .secure(cookieSecure)
                .path("/")
                .sameSite("Strict")
                .maxAge(0)
                .build();
    }

    /**
     * アクセストークンリフレッシュ。
     * リフレッシュ成功時は新しい access_token を HttpOnly Cookie にセットする。
     *
     * <p><b>公開根拠（{@link IntentionallyPublic} メソッド付与）</b>:
     * 本エンドポイントは {@code SecurityConfig} で {@code permitAll()} 済み。</p>
     *
     * <p><b>根拠</b>:
     * SecurityConfig.java:209-212 — requestMatchers("/api/v1/auth/login", "/api/v1/auth/register",
     * "/api/v1/auth/refresh", "/api/v1/auth/password-reset/**").permitAll()
     * </p>
     *
     * <p><b>公開してよいと判断した理由</b>:
     * ログイン・新規登録・トークン更新・パスワードリセットは<b>認証を確立する（または認証手段を回復する）ための入口</b>
     * であり、認証前に未認証で到達できなければ機能しない。資格情報・リセットトークンの検証は認証処理そのものが行う。<b>クラス付与は不可</b>
     * : 同クラスの {@code logout} / {@code getSessions} / {@code logoutDevice}
     * / {@code logoutAllDevices} / {@code updateSessionDeviceName}
     * / {@code verifyEmail} / {@code resendVerificationEmail} は認証必須のためクラスへ貼ると誤った証跡になる。
     * </p>
     *
     * <p>認可根治戦役 Wave5 監査済。</p>
     */
    @IntentionallyPublic
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
            // ローテーションで新しい refresh_token を Cookie にセット（旧トークンは DB で失効済み）。
            // body にも残しモバイル互換を維持。F01.1 §203 / ローテーション §1229
            if (apiResponse.getData().getRefreshToken() != null) {
                response.addHeader(HttpHeaders.SET_COOKIE,
                        buildRefreshTokenCookie(apiResponse.getData().getRefreshToken()).toString());
            }
        }
        return ResponseEntity.ok(apiResponse);
    }

    /**
     * パスワードリセット要求。
     *
     * <p><b>公開根拠（{@link IntentionallyPublic} メソッド付与）</b>:
     * 本エンドポイントは {@code SecurityConfig} で {@code permitAll()} 済み。</p>
     *
     * <p><b>根拠</b>:
     * SecurityConfig.java:209-212 — requestMatchers("/api/v1/auth/login", "/api/v1/auth/register",
     * "/api/v1/auth/refresh", "/api/v1/auth/password-reset/**").permitAll()
     * </p>
     *
     * <p><b>公開してよいと判断した理由</b>:
     * ログイン・新規登録・トークン更新・パスワードリセットは<b>認証を確立する（または認証手段を回復する）ための入口</b>
     * であり、認証前に未認証で到達できなければ機能しない。資格情報・リセットトークンの検証は認証処理そのものが行う。<b>クラス付与は不可</b>
     * : 同クラスの {@code logout} / {@code getSessions} / {@code logoutDevice}
     * / {@code logoutAllDevices} / {@code updateSessionDeviceName}
     * / {@code verifyEmail} / {@code resendVerificationEmail} は認証必須のためクラスへ貼ると誤った証跡になる。
     * </p>
     *
     * <p>認可根治戦役 Wave5 監査済。</p>
     */
    @IntentionallyPublic
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
     *
     * <p><b>公開根拠（{@link IntentionallyPublic} メソッド付与）</b>:
     * 本エンドポイントは {@code SecurityConfig} で {@code permitAll()} 済み。</p>
     *
     * <p><b>根拠</b>:
     * SecurityConfig.java:209-212 — requestMatchers("/api/v1/auth/login", "/api/v1/auth/register",
     * "/api/v1/auth/refresh", "/api/v1/auth/password-reset/**").permitAll()
     * </p>
     *
     * <p><b>公開してよいと判断した理由</b>:
     * ログイン・新規登録・トークン更新・パスワードリセットは<b>認証を確立する（または認証手段を回復する）ための入口</b>
     * であり、認証前に未認証で到達できなければ機能しない。資格情報・リセットトークンの検証は認証処理そのものが行う。<b>クラス付与は不可</b>
     * : 同クラスの {@code logout} / {@code getSessions} / {@code logoutDevice}
     * / {@code logoutAllDevices} / {@code updateSessionDeviceName}
     * / {@code verifyEmail} / {@code resendVerificationEmail} は認証必須のためクラスへ貼ると誤った証跡になる。
     * </p>
     *
     * <p>認可根治戦役 Wave5 監査済。</p>
     */
    @IntentionallyPublic
    @PostMapping("/password-reset/confirm")
    @Operation(summary = "パスワードリセット確認")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "パスワードリセット完了")
    public ResponseEntity<ApiResponse<MessageResponse>> confirmPasswordReset(
            @Valid @RequestBody ConfirmPasswordResetRequest req) {

        return ResponseEntity.ok(authService.confirmPasswordReset(req));
    }
}
