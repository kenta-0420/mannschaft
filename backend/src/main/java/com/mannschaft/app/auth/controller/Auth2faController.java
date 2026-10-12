package com.mannschaft.app.auth.controller;

import com.mannschaft.app.auth.service.Auth2faService;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.auth.dto.BackupCodesResponse;
import com.mannschaft.app.auth.dto.DisableTwoFactorRequest;
import com.mannschaft.app.auth.dto.EnableEmailTwoFactorRequest;
import com.mannschaft.app.auth.dto.MessageResponse;
import com.mannschaft.app.auth.dto.MfaEmailSendRequest;
import com.mannschaft.app.auth.dto.MfaEmailSendResponse;
import com.mannschaft.app.auth.dto.MfaLoginTokenResponse;
import com.mannschaft.app.auth.dto.RegenerateBackupCodesRequest;
import com.mannschaft.app.auth.dto.TwoFactorStatusResponse;
import com.mannschaft.app.common.IpAddressUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import com.mannschaft.app.auth.dto.TokenResponse;
import com.mannschaft.app.auth.dto.TotpSetupResponse;
import com.mannschaft.app.auth.dto.ValidateTotpLoginRequest;
import com.mannschaft.app.auth.dto.VerifyTotpRequest;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.security.IntentionallyPublic;
import com.mannschaft.app.common.security.SelfScopedEndpoint;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.mannschaft.app.common.SecurityUtils;

/**
 * 二要素認証（TOTP）コントローラー。
 * TOTP設定・検証・バックアップコード再生成・MFAリカバリーのエンドポイントを提供する。
 */
@RestController
@RequestMapping("/api/v1/auth/2fa")
@Tag(name = "2段階認証")
@RequiredArgsConstructor
public class Auth2faController {

    private final Auth2faService auth2faService;
    private final AuthTokenService authTokenService;
    private final com.mannschaft.app.auth.guardianship.AuthenticationCriticalOperationGuard authenticationCriticalOperationGuard;

    @Value("${mannschaft.cookie.secure:false}")
    private boolean cookieSecure;

    /**
     * TOTP設定開始。秘密鍵とQRコードURLを返す。
     *
     * <p><b>認可方式（{@link com.mannschaft.app.common.security.SelfScopedEndpoint} メソッド付与）</b>:
     * {@code SecurityUtils.getCurrentUserId()} のみで対象ユーザーを解決する
     * （{@code Auth2faService#setupTotp} が {@code findByUserId(userId)} のみで判定）。
     * 後見切替セッション中は {@code assertNotActingAs()} が代理実行を拒否する。</p>
     *
     * <p>認可根治戦役 Wave5 監査済。</p>
     */
    @SelfScopedEndpoint(
            "SecurityUtils.getCurrentUserId() のみで設定対象ユーザーを解決する"
                    + "（Auth2faController#setupTotp）")
    @PostMapping("/setup")
    @Operation(summary = "TOTP設定開始")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "TOTP設定開始成功")
    public ResponseEntity<ApiResponse<TotpSetupResponse>> setupTotp() {
        // F08.9 P3b: 後見切替セッション中は2FA設定を代理不可（03_security §3.2）
        authenticationCriticalOperationGuard.assertNotActingAs();
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.status(HttpStatus.CREATED).body(auth2faService.setupTotp(userId));
    }

    /**
     * TOTPコードを検証し、二要素認証を有効化する。
     *
     * <p><b>認可方式（{@link com.mannschaft.app.common.security.SelfScopedEndpoint} メソッド付与）</b>:
     * {@code SecurityUtils.getCurrentUserId()} のみで対象ユーザーを解決する
     * （{@code Auth2faService#verifyTotpSetup} が {@code findByUserId(userId)} のみで判定）。</p>
     *
     * <p>認可根治戦役 Wave5 監査済。</p>
     */
    @SelfScopedEndpoint(
            "SecurityUtils.getCurrentUserId() のみで検証対象ユーザーを解決する"
                    + "（Auth2faController#verifyTotpSetup）")
    @PostMapping("/verify")
    @Operation(summary = "TOTP設定検証・有効化")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "TOTP有効化成功")
    public ResponseEntity<ApiResponse<BackupCodesResponse>> verifyTotpSetup(
            @Valid @RequestBody VerifyTotpRequest req) {
        // F08.9 P3b: 後見切替セッション中は2FA設定検証を代理不可（03_security §3.2）
        authenticationCriticalOperationGuard.assertNotActingAs();
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(auth2faService.verifyTotpSetup(userId, req.getTotpCode()));
    }

    /**
     * MFAセッショントークンを使用してTOTPを検証し、トークンを発行する。
     *
     * <p><b>公開根拠（{@link IntentionallyPublic} メソッド付与）</b>:
     * 本エンドポイントは {@code SecurityConfig} で {@code permitAll()} 済み。</p>
     *
     * <p><b>根拠</b>:
     * SecurityConfig — requestMatchers("/api/v1/auth/2fa/validate",
     * "/api/v1/auth/2fa/recovery/request", "/api/v1/auth/2fa/recovery/confirm").permitAll()
     * </p>
     *
     * <p><b>公開してよいと判断した理由</b>:
     * 2FA ログインフローは<b>本ログインが完了する前の段階</b>で呼ばれるため、既存セッション（認証済み principal）
     * が存在せず認証を課せない。MFA セッショントークンの検証は認証処理そのものが行う。<b>クラス付与は不可</b>: 同クラスの
     * {@code setupTotp} / {@code verifyTotpSetup} / {@code regenerateBackupCodes}
     * は認証必須のためクラスへ貼ると誤った証跡になる。
     * </p>
     *
     * <p>認可根治戦役 Wave5 監査済。</p>
     */
    @IntentionallyPublic({
            "/api/v1/auth/2fa/validate",
            "/api/v1/auth/2fa/recovery/request",
            "/api/v1/auth/2fa/recovery/confirm"
    })
    @PostMapping("/validate")
    @Operation(summary = "2FAログイン検証（TOTP / バックアップコード / メールOTP）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "2FA検証成功")
    public ResponseEntity<ApiResponse<MfaLoginTokenResponse>> validateTotp(
            @Valid @RequestBody ValidateTotpLoginRequest req,
            HttpServletRequest request) {
        String ipAddress = IpAddressUtils.getClientIp(request);
        String userAgent = request.getHeader("User-Agent");
        ApiResponse<MfaLoginTokenResponse> response = auth2faService.validateMfaLogin(
                req.getMfaSessionToken(), req.effectiveMethod(), req.effectiveCode(), ipAddress, userAgent);
        TokenResponse tokens = response.getData();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, buildAccessTokenCookie(tokens.getAccessToken()).toString())
                .header(HttpHeaders.SET_COOKIE, buildRefreshTokenCookie(tokens.getRefreshToken()).toString())
                .body(response);
    }

    private ResponseCookie buildAccessTokenCookie(String token) {
        return ResponseCookie.from("access_token", token)
                .httpOnly(true)
                .secure(cookieSecure)
                .path("/")
                .sameSite("Strict")
                .maxAge(15 * 60)
                .build();
    }

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
     * バックアップコードを再生成する。
     *
     * <p><b>認可方式（{@link com.mannschaft.app.common.security.SelfScopedEndpoint} メソッド付与）</b>:
     * {@code SecurityUtils.getCurrentUserId()} のみで対象ユーザーを解決する
     * （{@code Auth2faService#regenerateBackupCodes} が {@code findByUserId(userId)} のみで判定）。
     * 後見切替セッション中は {@code assertNotActingAs()} が代理実行を拒否する。</p>
     *
     * <p>認可根治戦役 Wave5 監査済。</p>
     */
    @SelfScopedEndpoint(
            "SecurityUtils.getCurrentUserId() のみで再生成対象ユーザーを解決する"
                    + "（Auth2faController#regenerateBackupCodes）")
    @PostMapping("/backup-codes/regenerate")
    @Operation(summary = "バックアップコード再生成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "再生成成功")
    public ResponseEntity<ApiResponse<BackupCodesResponse>> regenerateBackupCodes(
            @RequestBody(required = false) RegenerateBackupCodesRequest req) {
        // F08.9 P3b: 後見切替セッション中はバックアップコード再生成を代理不可（03_security §3.2）
        authenticationCriticalOperationGuard.assertNotActingAs();
        Long userId = SecurityUtils.getCurrentUserId();
        // CMP-261012-0910 AC-44: 本人確認（PW or 有効要素）を本文で受ける。骨格段階は本文省略も許容。
        return ResponseEntity.ok(auth2faService.regenerateBackupCodes(userId, req));
    }

    /**
     * ログイン時のメールOTP を送信する（CMP-261012-0910 AC-19〜21）。
     *
     * <p><b>公開根拠（{@link IntentionallyPublic} メソッド付与）</b>:
     * 本エンドポイントは {@code SecurityConfig} で {@code permitAll()} 済み。</p>
     *
     * <p><b>根拠</b>:
     * SecurityConfig — requestMatchers(..., "/api/v1/auth/2fa/email/send", ...).permitAll()
     * </p>
     *
     * <p><b>公開してよいと判断した理由</b>:
     * 2FA ログインフローの途中（本ログイン完了前）で呼ばれるため既存セッションが無く認証を課せない。
     * 呼び出し者の特定は本文の MFA セッショントークン（推測不能・5 分 TTL・パスワード認証済みの証跡）で行い、
     * 送信先は当該ユーザーの登録メールアドレスに固定される（宛先を指定させない）。
     * MFA セッショントークンはクエリ文字列ではなく本文で受ける（AC-18。アクセスログ・Referer への漏えい防止）。
     * メール送信という副作用を伴うため、Service 層で IP・セッション・ユーザー単位のレート制限を課す（AC-20）。</p>
     */
    @IntentionallyPublic({"/api/v1/auth/2fa/email/send"})
    @PostMapping("/email/send")
    @Operation(summary = "2FAログイン用メールOTP送信")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "送信受付")
    public ResponseEntity<ApiResponse<MfaEmailSendResponse>> sendLoginEmailOtp(
            @Valid @RequestBody MfaEmailSendRequest req,
            HttpServletRequest request) {
        String ipAddress = IpAddressUtils.getClientIp(request);
        return ResponseEntity.ok(auth2faService.sendLoginEmailOtp(req.getMfaSessionToken(), ipAddress));
    }

    /**
     * メール 2FA の設定を開始する（設定用メールOTP を登録アドレスへ送信。CMP-261012-0910 AC-25）。
     *
     * <p><b>認可方式（{@link SelfScopedEndpoint} メソッド付与）</b>:
     * {@code SecurityUtils.getCurrentUserId()} のみで対象ユーザーを解決する。
     * 後見切替セッション中は {@code assertNotActingAs()} が代理実行を拒否する。</p>
     */
    @SelfScopedEndpoint(
            "SecurityUtils.getCurrentUserId() のみで設定対象ユーザーを解決する"
                    + "（Auth2faController#startEmailSetup）")
    @PostMapping("/email/setup")
    @Operation(summary = "メール2FA設定開始（設定用コード送信）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "送信受付")
    public ResponseEntity<ApiResponse<MfaEmailSendResponse>> startEmailSetup() {
        authenticationCriticalOperationGuard.assertNotActingAs();
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(auth2faService.startEmailSetup(userId));
    }

    /**
     * 設定用メールOTP を照合し、メール 2FA を有効化する（CMP-261012-0910 AC-25/AC-45）。
     *
     * <p><b>認可方式（{@link SelfScopedEndpoint} メソッド付与）</b>:
     * {@code SecurityUtils.getCurrentUserId()} のみで対象ユーザーを解決する。
     * 後見切替セッション中は {@code assertNotActingAs()} が代理実行を拒否する。</p>
     */
    @SelfScopedEndpoint(
            "SecurityUtils.getCurrentUserId() のみで有効化対象ユーザーを解決する"
                    + "（Auth2faController#enableEmailTwoFactor）")
    @PostMapping("/email/enable")
    @Operation(summary = "メール2FA有効化")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "有効化成功")
    public ResponseEntity<ApiResponse<BackupCodesResponse>> enableEmailTwoFactor(
            @Valid @RequestBody EnableEmailTwoFactorRequest req) {
        authenticationCriticalOperationGuard.assertNotActingAs();
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(auth2faService.enableEmailTwoFactor(userId, req.getCode()));
    }

    /**
     * 無効化・バックアップコード再生成用の本人確認メールOTP を送信する（CMP-261012-0910 AC-51）。
     *
     * <p><b>認可方式（{@link SelfScopedEndpoint} メソッド付与）</b>:
     * {@code SecurityUtils.getCurrentUserId()} のみで対象ユーザーを解決し、送信先は本人の登録アドレスに固定。
     * 後見切替セッション中は {@code assertNotActingAs()} が代理実行を拒否する。</p>
     */
    @SelfScopedEndpoint(
            "SecurityUtils.getCurrentUserId() のみで本人確認対象ユーザーを解決する"
                    + "（Auth2faController#sendReauthEmailOtp）")
    @PostMapping("/reauth/email/send")
    @Operation(summary = "本人確認用メールOTP送信（無効化・再生成用）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "送信受付")
    public ResponseEntity<ApiResponse<MfaEmailSendResponse>> sendReauthEmailOtp() {
        authenticationCriticalOperationGuard.assertNotActingAs();
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(auth2faService.sendReauthEmailOtp(userId));
    }

    /**
     * ログイン中に 2FA を無効化する（CMP-261012-0910 AC-30〜33）。
     *
     * <p><b>認可方式（{@link SelfScopedEndpoint} メソッド付与）</b>:
     * {@code SecurityUtils.getCurrentUserId()} のみで対象ユーザーを解決する。
     * 後見切替セッション中は {@code assertNotActingAs()} が代理実行を拒否する。
     * 盗難アクセストークン対策としてパスワード＋有効要素の両方を要求する（Service 層）。</p>
     */
    @SelfScopedEndpoint(
            "SecurityUtils.getCurrentUserId() のみで無効化対象ユーザーを解決する"
                    + "（Auth2faController#disableTwoFactor）")
    @PostMapping("/disable")
    @Operation(summary = "2FA無効化")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "無効化成功")
    public ResponseEntity<ApiResponse<MessageResponse>> disableTwoFactor(
            @Valid @RequestBody DisableTwoFactorRequest req) {
        authenticationCriticalOperationGuard.assertNotActingAs();
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(auth2faService.disableTwoFactor(userId, req));
    }

    /**
     * 自分の 2FA 設定状態を返す（CMP-261012-0910 AC-4）。秘密鍵・ハッシュは返さない。
     *
     * <p><b>認可方式（{@link SelfScopedEndpoint} メソッド付与）</b>:
     * {@code SecurityUtils.getCurrentUserId()} のみで対象ユーザーを解決する（参照のみのため代理閲覧は許容）。</p>
     */
    @SelfScopedEndpoint(
            "SecurityUtils.getCurrentUserId() のみで参照対象ユーザーを解決する"
                    + "（Auth2faController#getTwoFactorStatus）")
    @GetMapping("/status")
    @Operation(summary = "2FA設定状態取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<TwoFactorStatusResponse>> getTwoFactorStatus() {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(auth2faService.getTwoFactorStatus(userId));
    }

    /**
     * MFAリカバリーをリクエストする。リカバリーメールが送信される。
     *
     * <p><b>公開根拠（{@link IntentionallyPublic} メソッド付与）</b>:
     * 本エンドポイントは {@code SecurityConfig} で {@code permitAll()} 済み。</p>
     *
     * <p><b>根拠</b>:
     * SecurityConfig — requestMatchers("/api/v1/auth/2fa/validate",
     * "/api/v1/auth/2fa/recovery/request", "/api/v1/auth/2fa/recovery/confirm").permitAll()
     * </p>
     *
     * <p><b>公開してよいと判断した理由</b>:
     * 2FA ログインフローは<b>本ログインが完了する前の段階</b>で呼ばれるため、既存セッション（認証済み principal）
     * が存在せず認証を課せない。MFA セッショントークンの検証は認証処理そのものが行う。<b>クラス付与は不可</b>: 同クラスの
     * {@code setupTotp} / {@code verifyTotpSetup} / {@code regenerateBackupCodes}
     * は認証必須のためクラスへ貼ると誤った証跡になる。
     * </p>
     *
     * <p>認可根治戦役 Wave5 監査済。</p>
     */
    @IntentionallyPublic({
            "/api/v1/auth/2fa/validate",
            "/api/v1/auth/2fa/recovery/request",
            "/api/v1/auth/2fa/recovery/confirm"
    })
    @PostMapping("/recovery/request")
    @Operation(summary = "MFAリカバリー要求")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "リカバリーメール送信完了")
    public ResponseEntity<ApiResponse<MessageResponse>> requestMfaRecovery(
            @RequestParam String mfaSessionToken) {

        return ResponseEntity.ok(auth2faService.requestMfaRecovery(mfaSessionToken));
    }

    /**
     * MFAリカバリーを確認し、2FAを無効化してトークンを発行する。
     *
     * <p><b>公開根拠（{@link IntentionallyPublic} メソッド付与）</b>:
     * 本エンドポイントは {@code SecurityConfig} で {@code permitAll()} 済み。</p>
     *
     * <p><b>根拠</b>:
     * SecurityConfig — requestMatchers("/api/v1/auth/2fa/validate",
     * "/api/v1/auth/2fa/recovery/request", "/api/v1/auth/2fa/recovery/confirm").permitAll()
     * </p>
     *
     * <p><b>公開してよいと判断した理由</b>:
     * 2FA ログインフローは<b>本ログインが完了する前の段階</b>で呼ばれるため、既存セッション（認証済み principal）
     * が存在せず認証を課せない。MFA セッショントークンの検証は認証処理そのものが行う。<b>クラス付与は不可</b>: 同クラスの
     * {@code setupTotp} / {@code verifyTotpSetup} / {@code regenerateBackupCodes}
     * は認証必須のためクラスへ貼ると誤った証跡になる。
     * </p>
     *
     * <p>認可根治戦役 Wave5 監査済。</p>
     */
    @IntentionallyPublic({
            "/api/v1/auth/2fa/validate",
            "/api/v1/auth/2fa/recovery/request",
            "/api/v1/auth/2fa/recovery/confirm"
    })
    @PostMapping("/recovery/confirm")
    @Operation(summary = "MFAリカバリー確認")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "リカバリー完了")
    public ResponseEntity<ApiResponse<TokenResponse>> confirmMfaRecovery(
            @RequestParam String token) {

        return ResponseEntity.ok(auth2faService.confirmMfaRecovery(token));
    }
}
