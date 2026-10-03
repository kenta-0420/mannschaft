package com.mannschaft.app.auth.controller;

import com.mannschaft.app.auth.service.AuthWebAuthnService;
import com.mannschaft.app.auth.dto.MessageResponse;
import com.mannschaft.app.auth.dto.TokenResponse;
import com.mannschaft.app.auth.dto.UpdateWebAuthnCredentialRequest;
import com.mannschaft.app.auth.dto.WebAuthnCredentialResponse;
import com.mannschaft.app.auth.dto.WebAuthnLoginBeginResponse;
import com.mannschaft.app.auth.dto.WebAuthnLoginCompleteRequest;
import com.mannschaft.app.auth.dto.WebAuthnReauthenticateBeginResponse;
import com.mannschaft.app.auth.dto.WebAuthnReauthenticateCompleteRequest;
import com.mannschaft.app.auth.dto.WebAuthnReauthenticateCompleteResponse;
import com.mannschaft.app.auth.dto.WebAuthnRegisterBeginResponse;
import com.mannschaft.app.auth.dto.WebAuthnRegisterCompleteRequest;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.security.AuthorizedInService;
import com.mannschaft.app.common.security.IntentionallyPublic;
import com.mannschaft.app.common.security.SelfScopedEndpoint;
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
     *
     * <p><b>認可方式（{@link com.mannschaft.app.common.security.SelfScopedEndpoint} メソッド付与）</b>:
     * {@code SecurityUtils.getCurrentUserId()} のみでチャレンジ保存キーを解決し、
     * リクエストに他人の識別子を指定する余地が無い。</p>
     *
     * <p>認可根治戦役 Wave5 監査済。</p>
     */
    @SelfScopedEndpoint(
            "SecurityUtils.getCurrentUserId() のみでチャレンジ保存先を解決する"
                    + "（AuthWebAuthnController#beginRegister）")
    @PostMapping("/register/begin")
    @Operation(summary = "WebAuthn登録開始")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "チャレンジ生成成功")
    public ResponseEntity<ApiResponse<WebAuthnRegisterBeginResponse>> beginRegister() {

        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(authWebAuthnService.beginRegister(userId));
    }

    /**
     * WebAuthn登録完了。
     *
     * <p><b>認可方式（{@link com.mannschaft.app.common.security.SelfScopedEndpoint} メソッド付与）</b>:
     * 新規資格情報は {@code SecurityUtils.getCurrentUserId()} 配下のチャレンジキーとの突合でのみ紐付けられ、
     * リクエストボディに紐付け先ユーザーを指定する項目は存在しない。</p>
     *
     * <p>認可根治戦役 Wave5 監査済。</p>
     */
    @SelfScopedEndpoint(
            "SecurityUtils.getCurrentUserId() のみで登録先ユーザーを解決する"
                    + "（AuthWebAuthnController#completeRegister）")
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
     *
     * <p><b>公開根拠（{@link IntentionallyPublic} メソッド付与）</b>:
     * 本エンドポイントは {@code SecurityConfig} で {@code permitAll()} 済み。</p>
     *
     * <p><b>根拠</b>:
     * SecurityConfig — requestMatchers("/api/v1/auth/webauthn/login/begin",
     * "/api/v1/auth/webauthn/login/complete").permitAll()
     * </p>
     *
     * <p><b>公開してよいと判断した理由</b>:
     * WebAuthn パスキーログインは<b>第一要素として未認証で呼ばれる</b>ため公開必須。チャレンジ・署名の検証は認証処理そのものが行う。
     * <b>クラス付与は不可</b>: 同クラスの register / reauthenticate / credentials
     * 系 7 メソッドは認証必須のためクラスへ貼ると誤った証跡になる。
     * </p>
     *
     * <p>認可根治戦役 Wave5 監査済。</p>
     */
    @IntentionallyPublic({
            "/api/v1/auth/webauthn/login/begin",
            "/api/v1/auth/webauthn/login/complete"
    })
    @PostMapping("/login/begin")
    @Operation(summary = "WebAuthnログイン開始")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "チャレンジ生成成功")
    public ResponseEntity<ApiResponse<WebAuthnLoginBeginResponse>> beginLogin(
            @RequestParam String email) {

        return ResponseEntity.ok(authWebAuthnService.beginLogin(email));
    }

    /**
     * WebAuthnログイン完了。
     *
     * <p><b>公開根拠（{@link IntentionallyPublic} メソッド付与）</b>:
     * 本エンドポイントは {@code SecurityConfig} で {@code permitAll()} 済み。</p>
     *
     * <p><b>根拠</b>:
     * SecurityConfig — requestMatchers("/api/v1/auth/webauthn/login/begin",
     * "/api/v1/auth/webauthn/login/complete").permitAll()
     * </p>
     *
     * <p><b>公開してよいと判断した理由</b>:
     * WebAuthn パスキーログインは<b>第一要素として未認証で呼ばれる</b>ため公開必須。チャレンジ・署名の検証は認証処理そのものが行う。
     * <b>クラス付与は不可</b>: 同クラスの register / reauthenticate / credentials
     * 系 7 メソッドは認証必須のためクラスへ貼ると誤った証跡になる。
     * </p>
     *
     * <p>認可根治戦役 Wave5 監査済。</p>
     */
    @IntentionallyPublic({
            "/api/v1/auth/webauthn/login/begin",
            "/api/v1/auth/webauthn/login/complete"
    })
    @PostMapping("/login/complete")
    @Operation(summary = "WebAuthnログイン完了")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "ログイン成功")
    public ResponseEntity<ApiResponse<TokenResponse>> completeLogin(
            @Valid @RequestBody WebAuthnLoginCompleteRequest req,
            HttpServletRequest request) {

        String ipAddress = com.mannschaft.app.common.IpAddressUtils.getClientIp(request);
        String userAgent = request.getHeader("User-Agent");
        return ResponseEntity.ok(authWebAuthnService.completeLogin(req, ipAddress, userAgent));
    }

    /**
     * F18 提示モード追加保護: WebAuthn 再認証開始（設計書 §9.6 / POINT_CARD_009）。
     *
     * <p>認証済みユーザー本人を対象に再認証用チャレンジを発行する。
     * AT/RT は発行されない。完了 API がフラグだけを 5 分 TTL で記録する。
     * レート制限は {@link com.mannschaft.app.auth.AuthWebAuthnReauthRateLimitFilter} で 10/分。
     *
     * <p><b>認可方式（{@link com.mannschaft.app.common.security.SelfScopedEndpoint} メソッド付与）</b>:
     * {@code SecurityUtils.getCurrentUserId()} のみでチャレンジ保存キーを解決する。</p>
     *
     * <p>認可根治戦役 Wave5 監査済。</p>
     */
    @SelfScopedEndpoint(
            "SecurityUtils.getCurrentUserId() のみでチャレンジ保存先を解決する"
                    + "（AuthWebAuthnController#beginReauthenticate）")
    @PostMapping("/reauthenticate-begin")
    @Operation(summary = "WebAuthn 再認証開始（提示モード追加保護用）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "チャレンジ生成成功")
    public ResponseEntity<ApiResponse<WebAuthnReauthenticateBeginResponse>> beginReauthenticate() {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(authWebAuthnService.beginReauthenticate(userId));
    }

    /**
     * F18 提示モード追加保護: WebAuthn 再認証完了（設計書 §9.6 / POINT_CARD_009）。
     *
     * <p>署名検証 + sign_count 増分検証 + 「再認証済みフラグ」を 5 分 TTL で記録。
     * <strong>AT/RT は再発行しない</strong>。提示モード開始 API が本フラグを 1 回限りで消費する。
     *
     * <p><b>認可方式（{@link com.mannschaft.app.common.security.SelfScopedEndpoint} メソッド付与）</b>:
     * {@code SecurityUtils.getCurrentUserId()} のみでチャレンジ・再認証フラグの対象を解決する。</p>
     *
     * <p>認可根治戦役 Wave5 監査済。</p>
     */
    @SelfScopedEndpoint(
            "SecurityUtils.getCurrentUserId() のみで再認証対象ユーザーを解決する"
                    + "（AuthWebAuthnController#completeReauthenticate）")
    @PostMapping("/reauthenticate-complete")
    @Operation(summary = "WebAuthn 再認証完了（提示モード追加保護用）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "再認証成功")
    public ResponseEntity<ApiResponse<WebAuthnReauthenticateCompleteResponse>> completeReauthenticate(
            @Valid @RequestBody WebAuthnReauthenticateCompleteRequest req) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(authWebAuthnService.completeReauthenticate(userId, req));
    }

    /**
     * 登録済みWebAuthn資格情報一覧取得。
     *
     * <p><b>認可方式（{@link com.mannschaft.app.common.security.SelfScopedEndpoint} メソッド付与）</b>:
     * {@code SecurityUtils.getCurrentUserId()} のみを検索条件に用いる
     * （{@code AuthWebAuthnService#getCredentials} が {@code findByUserId(userId)} のみで解決）。</p>
     *
     * <p>認可根治戦役 Wave5 監査済。</p>
     */
    @SelfScopedEndpoint(
            "SecurityUtils.getCurrentUserId() のみで検索対象ユーザーを解決する"
                    + "（AuthWebAuthnController#getCredentials）")
    @GetMapping("/credentials")
    @Operation(summary = "WebAuthn資格情報一覧取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<WebAuthnCredentialResponse>>> getCredentials() {

        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(authWebAuthnService.getCredentials(userId));
    }

    /**
     * WebAuthn資格情報のデバイス名更新。
     *
     * <p><b>認可方式（{@link com.mannschaft.app.common.security.AuthorizedInService} メソッド付与）</b>:
     * {@code AuthWebAuthnService#updateCredentialName} がパス変数 {@code id} で
     * {@code WebAuthnCredentialEntity} を取得し、{@code credential.getUserId().equals(userId)} で
     * 所有者が呼出ユーザーであることを検証してから改名する。</p>
     *
     * <p>認可根治戦役 Wave5 監査済。</p>
     */
    @AuthorizedInService
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
     *
     * <p><b>認可方式（{@link com.mannschaft.app.common.security.AuthorizedInService} メソッド付与）</b>:
     * {@code AuthWebAuthnService#deleteCredential} がパス変数 {@code id} で
     * {@code WebAuthnCredentialEntity} を取得し、{@code credential.getUserId().equals(userId)} で
     * 所有者が呼出ユーザーであることを検証してから削除する。</p>
     *
     * <p>認可根治戦役 Wave5 監査済。</p>
     */
    @AuthorizedInService
    @DeleteMapping("/credentials/{id}")
    @Operation(summary = "WebAuthn資格情報削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteCredential(@PathVariable Long id) {

        Long userId = SecurityUtils.getCurrentUserId();
        authWebAuthnService.deleteCredential(userId, id);
        return ResponseEntity.noContent().build();
    }
}
