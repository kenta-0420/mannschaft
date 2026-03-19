package com.mannschaft.app.auth;

import com.mannschaft.app.auth.Auth2faService;
import com.mannschaft.app.auth.dto.BackupCodesResponse;
import com.mannschaft.app.auth.dto.MessageResponse;
import com.mannschaft.app.auth.dto.TokenResponse;
import com.mannschaft.app.auth.dto.TotpSetupResponse;
import com.mannschaft.app.auth.dto.ValidateTotpLoginRequest;
import com.mannschaft.app.auth.dto.VerifyTotpRequest;
import com.mannschaft.app.common.ApiResponse;
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

    /**
     * TOTP設定開始。秘密鍵とQRコードURLを返す。
     */
    @PostMapping("/setup")
    @Operation(summary = "TOTP設定開始")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "TOTP設定開始成功")
    public ResponseEntity<ApiResponse<TotpSetupResponse>> setupTotp() {

        // TODO: JwtAuthenticationFilter実装時にSecurityContextHolderから取得に変更
        Long userId = 1L;
        return ResponseEntity.status(HttpStatus.CREATED).body(auth2faService.setupTotp(userId));
    }

    /**
     * TOTPコードを検証し、二要素認証を有効化する。
     */
    @PostMapping("/verify")
    @Operation(summary = "TOTP設定検証・有効化")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "TOTP有効化成功")
    public ResponseEntity<ApiResponse<BackupCodesResponse>> verifyTotpSetup(
            @Valid @RequestBody VerifyTotpRequest req) {

        // TODO: JwtAuthenticationFilter実装時にSecurityContextHolderから取得に変更
        Long userId = 1L;
        return ResponseEntity.ok(auth2faService.verifyTotpSetup(userId, req.getTotpCode()));
    }

    /**
     * MFAセッショントークンを使用してTOTPを検証し、トークンを発行する。
     */
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
     */
    @PostMapping("/backup-codes/regenerate")
    @Operation(summary = "バックアップコード再生成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "再生成成功")
    public ResponseEntity<ApiResponse<BackupCodesResponse>> regenerateBackupCodes() {

        // TODO: JwtAuthenticationFilter実装時にSecurityContextHolderから取得に変更
        Long userId = 1L;
        return ResponseEntity.ok(auth2faService.regenerateBackupCodes(userId));
    }

    /**
     * MFAリカバリーをリクエストする。リカバリーメールが送信される。
     */
    @PostMapping("/recovery/request")
    @Operation(summary = "MFAリカバリー要求")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "リカバリーメール送信完了")
    public ResponseEntity<ApiResponse<MessageResponse>> requestMfaRecovery(
            @RequestParam String mfaSessionToken) {

        return ResponseEntity.ok(auth2faService.requestMfaRecovery(mfaSessionToken));
    }

    /**
     * MFAリカバリーを確認し、2FAを無効化してトークンを発行する。
     */
    @PostMapping("/recovery/confirm")
    @Operation(summary = "MFAリカバリー確認")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "リカバリー完了")
    public ResponseEntity<ApiResponse<TokenResponse>> confirmMfaRecovery(
            @RequestParam String token) {

        return ResponseEntity.ok(auth2faService.confirmMfaRecovery(token));
    }
}
