package com.mannschaft.app.auth.controller;

import com.mannschaft.app.auth.service.Auth2faService;
import com.mannschaft.app.auth.dto.BackupCodesResponse;
import com.mannschaft.app.auth.dto.MessageResponse;
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
import org.springframework.http.HttpStatus;
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
    private final com.mannschaft.app.auth.guardianship.AuthenticationCriticalOperationGuard authenticationCriticalOperationGuard;

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
    @Operation(summary = "TOTPログイン検証")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "TOTP検証成功")
    public ResponseEntity<ApiResponse<TokenResponse>> validateTotp(
            @Valid @RequestBody ValidateTotpLoginRequest req) {

        return ResponseEntity.ok(auth2faService.validateTotp(
                req.getMfaSessionToken(), req.getTotpCode()));
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
    public ResponseEntity<ApiResponse<BackupCodesResponse>> regenerateBackupCodes() {
        // F08.9 P3b: 後見切替セッション中はバックアップコード再生成を代理不可（03_security §3.2）
        authenticationCriticalOperationGuard.assertNotActingAs();
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(auth2faService.regenerateBackupCodes(userId));
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
