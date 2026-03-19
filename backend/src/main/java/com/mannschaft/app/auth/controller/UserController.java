package com.mannschaft.app.auth.controller;

import com.mannschaft.app.auth.dto.ChangePasswordRequest;
import com.mannschaft.app.auth.dto.LoginHistoryResponse;
import com.mannschaft.app.auth.dto.MessageResponse;
import com.mannschaft.app.auth.dto.OAuthProviderResponse;
import com.mannschaft.app.auth.dto.RequestEmailChangeRequest;
import com.mannschaft.app.auth.dto.RequestWithdrawalRequest;
import com.mannschaft.app.auth.dto.UpdateProfileRequest;
import com.mannschaft.app.auth.dto.UserProfileResponse;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.CursorPagedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * ユーザー管理コントローラー。プロフィール操作・パスワード管理・メール変更・退会・OAuth連携・ログイン履歴を提供する。
 */
@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "ユーザー管理")
@RequiredArgsConstructor
public class UserController {

    private final com.mannschaft.app.auth.service.UserService userService;
    private final com.mannschaft.app.auth.service.AuthOAuthService authOAuthService;
    private final com.mannschaft.app.auth.service.AuthService authService;

    /**
     * 認証済みユーザーのIDを取得する。
     * TODO: JWT Filter実装時に SecurityContext から取得するよう差し替える
     */
    private Long getAuthenticatedUserId() {
        // TODO: JWT Filter実装時に SecurityContextHolder から userId を取得する
        return 1L;
    }

    /**
     * 自分のプロフィールを取得する。
     */
    @GetMapping("/me")
    @Operation(summary = "プロフィール取得", description = "認証済みユーザーの自身のプロフィール情報を取得する")
    public ResponseEntity<ApiResponse<UserProfileResponse>> getMyProfile() {
        Long userId = getAuthenticatedUserId();
        ApiResponse<UserProfileResponse> response = userService.getUserProfile(userId);
        return ResponseEntity.ok(response);
    }

    /**
     * 自分のプロフィールを更新する。
     */
    @PutMapping("/me")
    @Operation(summary = "プロフィール更新", description = "認証済みユーザーの自身のプロフィール情報を更新する")
    public ResponseEntity<ApiResponse<UserProfileResponse>> updateMyProfile(
            @Valid @RequestBody UpdateProfileRequest req) {
        Long userId = getAuthenticatedUserId();
        ApiResponse<UserProfileResponse> response = userService.updateProfile(userId, req);
        return ResponseEntity.ok(response);
    }

    /**
     * パスワードを初期設定する（OAuth専用ユーザー向け）。
     */
    @PostMapping("/me/password/setup")
    @Operation(summary = "パスワード初期設定", description = "OAuth専用ユーザーがパスワードを新規設定する")
    public ResponseEntity<ApiResponse<MessageResponse>> setupPassword(
            @RequestParam String password) {
        Long userId = getAuthenticatedUserId();
        ApiResponse<MessageResponse> response = userService.setupPassword(userId, password);
        return ResponseEntity.ok(response);
    }

    /**
     * パスワードを変更する。
     */
    @PatchMapping("/me/password")
    @Operation(summary = "パスワード変更", description = "現在のパスワードを検証し、新しいパスワードに変更する")
    public ResponseEntity<ApiResponse<MessageResponse>> changePassword(
            @Valid @RequestBody ChangePasswordRequest req,
            HttpServletRequest httpRequest) {
        Long userId = getAuthenticatedUserId();
        String ipAddress = httpRequest.getRemoteAddr();
        userService.changePassword(userId, req, ipAddress);
        return ResponseEntity.ok(ApiResponse.of(MessageResponse.of("パスワードを変更しました")));
    }

    /**
     * メールアドレス変更をリクエストする。
     */
    @PatchMapping("/me/email")
    @Operation(summary = "メールアドレス変更リクエスト", description = "新しいメールアドレスへの確認メールを送信する")
    public ResponseEntity<ApiResponse<MessageResponse>> requestEmailChange(
            @Valid @RequestBody RequestEmailChangeRequest req) {
        Long userId = getAuthenticatedUserId();
        ApiResponse<MessageResponse> response = userService.requestEmailChange(userId, req);
        return ResponseEntity.ok(response);
    }

    /**
     * メールアドレス変更を確認する。
     */
    @PostMapping("/me/email/confirm")
    @Operation(summary = "メールアドレス変更確認", description = "確認トークンを検証してメールアドレスを変更する")
    public ResponseEntity<ApiResponse<MessageResponse>> confirmEmailChange(
            @RequestParam String token) {
        ApiResponse<MessageResponse> response = userService.confirmEmailChange(token);
        return ResponseEntity.ok(response);
    }

    /**
     * 退会をリクエストする（論理削除）。
     */
    @DeleteMapping("/me")
    @Operation(summary = "退会リクエスト", description = "退会をリクエストする（論理削除。30日間は取り消し可能）")
    public ResponseEntity<ApiResponse<MessageResponse>> requestWithdrawal(
            @Valid @RequestBody RequestWithdrawalRequest req) {
        Long userId = getAuthenticatedUserId();
        userService.requestWithdrawal(userId, req);
        return ResponseEntity.ok(ApiResponse.of(MessageResponse.of("退会リクエストを受け付けました")));
    }

    /**
     * 退会リクエストを取り消す。
     */
    @PostMapping("/me/withdrawal/cancel")
    @Operation(summary = "退会取り消し", description = "退会リクエストを取り消し、アカウントを復帰させる")
    public ResponseEntity<ApiResponse<MessageResponse>> cancelWithdrawal() {
        Long userId = getAuthenticatedUserId();
        ApiResponse<MessageResponse> response = userService.cancelWithdrawal(userId);
        return ResponseEntity.ok(response);
    }

    /**
     * 連携済みOAuthプロバイダ一覧を取得する。
     */
    @GetMapping("/me/oauth")
    @Operation(summary = "OAuth連携一覧取得", description = "連携済みのOAuthプロバイダ一覧を取得する")
    public ResponseEntity<ApiResponse<List<OAuthProviderResponse>>> getConnectedProviders() {
        Long userId = getAuthenticatedUserId();
        ApiResponse<List<OAuthProviderResponse>> response = authOAuthService.getConnectedProviders(userId);
        return ResponseEntity.ok(response);
    }

    /**
     * OAuthプロバイダの連携を解除する。
     */
    @DeleteMapping("/me/oauth/{provider}")
    @Operation(summary = "OAuth連携解除", description = "指定のOAuthプロバイダとの連携を解除する")
    public ResponseEntity<ApiResponse<MessageResponse>> disconnectProvider(
            @Parameter(description = "OAuthプロバイダ名 (GOOGLE, LINE, APPLE)")
            @PathVariable String provider) {
        Long userId = getAuthenticatedUserId();
        authOAuthService.disconnectProvider(userId, provider);
        return ResponseEntity.ok(ApiResponse.of(MessageResponse.of("OAuth連携を解除しました")));
    }

    /**
     * ログイン履歴を取得する。
     */
    @GetMapping("/me/login-history")
    @Operation(summary = "ログイン履歴取得", description = "認証済みユーザーのログイン履歴をカーソルベースで取得する")
    public ResponseEntity<CursorPagedResponse<LoginHistoryResponse>> getLoginHistory(
            @Parameter(description = "ページングカーソル（nullで先頭から）")
            @RequestParam(required = false) String cursor,
            @Parameter(description = "取得件数（デフォルト20）")
            @RequestParam(defaultValue = "20") int limit) {
        Long userId = getAuthenticatedUserId();
        CursorPagedResponse<LoginHistoryResponse> response = authService.getLoginHistory(userId, cursor, limit);
        return ResponseEntity.ok(response);
    }
}
