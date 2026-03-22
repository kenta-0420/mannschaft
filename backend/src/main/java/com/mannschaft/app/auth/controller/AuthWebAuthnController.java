package com.mannschaft.app.auth.controller;

import com.mannschaft.app.auth.service.AuthWebAuthnService;
import com.mannschaft.app.auth.dto.MessageResponse;
import com.mannschaft.app.auth.dto.TokenResponse;
import com.mannschaft.app.auth.dto.UpdateWebAuthnCredentialRequest;
import com.mannschaft.app.auth.dto.WebAuthnCredentialResponse;
import com.mannschaft.app.auth.dto.WebAuthnLoginBeginResponse;
import com.mannschaft.app.auth.dto.WebAuthnLoginCompleteRequest;
import com.mannschaft.app.auth.dto.WebAuthnRegisterBeginResponse;
import com.mannschaft.app.auth.dto.WebAuthnRegisterCompleteRequest;
import com.mannschaft.app.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import com.mannschaft.app.common.SecurityUtils;

/**
 * WebAuthn（パスキー・FIDO2）コントローラー。
 * 資格情報の登録・ログイン・管理のエンドポイントを提供する。
 */
@RestController
@RequestMapping("/api/v1/auth/webauthn")
@Tag(name = "WebAuthn")
@RequiredArgsConstructor
public class AuthWebAuthnController {

    private final AuthWebAuthnService authWebAuthnService;

    /**
     * WebAuthn登録開始。チャレンジを生成して返す。
     */
    @PostMapping("/register/begin")
    @Operation(summary = "WebAuthn登録開始")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "チャレンジ生成成功")
    public ResponseEntity<ApiResponse<WebAuthnRegisterBeginResponse>> beginRegister() {

        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(authWebAuthnService.beginRegister(userId));
    }

    /**
     * WebAuthn登録完了。
     */
    @PostMapping("/register/complete")
    @Operation(summary = "WebAuthn登録完了")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "資格情報登録成功")
    public ResponseEntity<ApiResponse<MessageResponse>> completeRegister(
            @Valid @RequestBody WebAuthnRegisterCompleteRequest req) {

        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.status(HttpStatus.CREATED).body(
                authWebAuthnService.completeRegister(userId, req));
    }

    /**
     * WebAuthnログイン開始。登録済みcredential一覧とチャレンジを返す。
     */
    @PostMapping("/login/begin")
    @Operation(summary = "WebAuthnログイン開始")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "チャレンジ生成成功")
    public ResponseEntity<ApiResponse<WebAuthnLoginBeginResponse>> beginLogin(
            @RequestParam String email) {

        return ResponseEntity.ok(authWebAuthnService.beginLogin(email));
    }

    /**
     * WebAuthnログイン完了。
     */
    @PostMapping("/login/complete")
    @Operation(summary = "WebAuthnログイン完了")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "ログイン成功")
    public ResponseEntity<ApiResponse<TokenResponse>> completeLogin(
            @Valid @RequestBody WebAuthnLoginCompleteRequest req,
            HttpServletRequest request) {

        // TODO: X-Forwarded-For対応
        String ipAddress = request.getRemoteAddr();
        String userAgent = request.getHeader("User-Agent");
        return ResponseEntity.ok(authWebAuthnService.completeLogin(req, ipAddress, userAgent));
    }

    /**
     * 登録済みWebAuthn資格情報一覧取得。
     */
    @GetMapping("/credentials")
    @Operation(summary = "WebAuthn資格情報一覧取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<WebAuthnCredentialResponse>>> getCredentials() {

        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(authWebAuthnService.getCredentials(userId));
    }

    /**
     * WebAuthn資格情報のデバイス名更新。
     */
    @PatchMapping("/credentials/{id}")
    @Operation(summary = "WebAuthn資格情報デバイス名更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<WebAuthnCredentialResponse>> updateCredentialName(
            @PathVariable Long id,
            @Valid @RequestBody UpdateWebAuthnCredentialRequest req) {

        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(authWebAuthnService.updateCredentialName(userId, id, req));
    }

    /**
     * WebAuthn資格情報削除。
     */
    @DeleteMapping("/credentials/{id}")
    @Operation(summary = "WebAuthn資格情報削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteCredential(@PathVariable Long id) {

        Long userId = SecurityUtils.getCurrentUserId();
        authWebAuthnService.deleteCredential(userId, id);
        return ResponseEntity.noContent().build();
    }
}
