package com.mannschaft.app.auth.controller;

import com.mannschaft.app.auth.dto.ChangePasswordRequest;
import com.mannschaft.app.auth.dto.LoginHistoryResponse;
import com.mannschaft.app.auth.dto.MessageResponse;
import com.mannschaft.app.auth.dto.OAuthProviderResponse;
import com.mannschaft.app.auth.dto.RequestEmailChangeRequest;
import com.mannschaft.app.auth.dto.RequestWithdrawalRequest;
import com.mannschaft.app.auth.dto.UpdateProfileRequest;
import com.mannschaft.app.auth.dto.UpdatePublicProfileRequest;
import com.mannschaft.app.auth.dto.UserProfileResponse;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.CursorPagedResponse;
import com.mannschaft.app.common.security.AuthorizedInService;
import com.mannschaft.app.common.security.SelfScopedEndpoint;
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
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.RestController;
import java.time.LocalDateTime;

import java.util.List;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.featuregate.AlwaysReachable;
import com.mannschaft.app.common.featuregate.AlwaysReachableCategory;

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
    private final com.mannschaft.app.auth.guardianship.AuthenticationCriticalOperationGuard authenticationCriticalOperationGuard;

    /**
     * 自分のプロフィールを取得する。
     *
     * <p><b>認可方式（{@link com.mannschaft.app.common.security.SelfScopedEndpoint} メソッド付与）</b>:
     * {@code SecurityUtils.getCurrentUserId()} のみで対象ユーザーを解決する。</p>
     *
     * <p>認可根治戦役 Wave5 監査済。</p>
     */
    @SelfScopedEndpoint(
            "SecurityUtils.getCurrentUserId() のみで取得対象ユーザーを解決する"
                    + "（UserController#getMyProfile）")
    @GetMapping("/me")
    @Operation(summary = "プロフィール取得", description = "認証済みユーザーの自身のプロフィール情報を取得する")
    public ResponseEntity<ApiResponse<UserProfileResponse>> getMyProfile() {
        Long userId = SecurityUtils.getCurrentUserId();
        ApiResponse<UserProfileResponse> response = userService.getUserProfile(userId);
        return ResponseEntity.ok(response);
    }

    /**
     * 自分のプロフィールを更新する。
     *
     * <p><b>認可方式（{@link com.mannschaft.app.common.security.SelfScopedEndpoint} メソッド付与）</b>:
     * {@code SecurityUtils.getCurrentUserId()} のみで更新対象ユーザーを解決する
     * （{@code UserService#updateProfile} が {@code findUserOrThrow(userId)} のみで対象を確定）。
     * リクエストボディに他ユーザーを指す識別子は含まれない。</p>
     *
     * <p>認可根治戦役 Wave5 監査済。</p>
     */
    @SelfScopedEndpoint(
            "SecurityUtils.getCurrentUserId() のみで更新対象ユーザーを解決する"
                    + "（UserController#updateMyProfile）")
    @PutMapping("/me")
    @Operation(summary = "プロフィール更新", description = "認証済みユーザーの自身のプロフィール情報を更新する")
    public ResponseEntity<ApiResponse<UserProfileResponse>> updateMyProfile(
            @Valid @RequestBody UpdateProfileRequest req) {
        Long userId = SecurityUtils.getCurrentUserId();
        ApiResponse<UserProfileResponse> response = userService.updateProfile(userId, req);
        return ResponseEntity.ok(response);
    }

    /**
     * パスワードを初期設定する（OAuth専用ユーザー向け）。
     *
     * <p><b>認可方式（{@link com.mannschaft.app.common.security.SelfScopedEndpoint} メソッド付与）</b>:
     * {@code SecurityUtils.getCurrentUserId()} のみで対象ユーザーを解決する。</p>
     *
     * <p>認可根治戦役 Wave5 監査済。</p>
     */
    @SelfScopedEndpoint(
            "SecurityUtils.getCurrentUserId() のみで設定対象ユーザーを解決する"
                    + "（UserController#setupPassword）")
    @PostMapping("/me/password/setup")
    @Operation(summary = "パスワード初期設定", description = "OAuth専用ユーザーがパスワードを新規設定する")
    public ResponseEntity<ApiResponse<MessageResponse>> setupPassword(
            @RequestParam String password) {
        Long userId = SecurityUtils.getCurrentUserId();
        ApiResponse<MessageResponse> response = userService.setupPassword(userId, password);
        return ResponseEntity.ok(response);
    }

    /**
     * パスワードを変更する。
     *
     * <p><b>認可方式（{@link com.mannschaft.app.common.security.SelfScopedEndpoint} メソッド付与）</b>:
     * {@code SecurityUtils.getCurrentUserId()} のみで対象ユーザーを解決する。
     * 後見切替セッション中は {@code assertNotActingAs()} が代理実行を拒否する。</p>
     *
     * <p>認可根治戦役 Wave5 監査済。</p>
     */
    @SelfScopedEndpoint(
            "SecurityUtils.getCurrentUserId() のみで変更対象ユーザーを解決する"
                    + "（UserController#changePassword）")
    @PatchMapping("/me/password")
    @Operation(summary = "パスワード変更", description = "現在のパスワードを検証し、新しいパスワードに変更する")
    public ResponseEntity<ApiResponse<MessageResponse>> changePassword(
            @Valid @RequestBody ChangePasswordRequest req,
            HttpServletRequest httpRequest) {
        // F08.9 P3b: 後見切替セッション中はパスワード変更を代理不可（03_security §3.2）
        authenticationCriticalOperationGuard.assertNotActingAs();
        Long userId = SecurityUtils.getCurrentUserId();
        String ipAddress = httpRequest.getRemoteAddr();
        userService.changePassword(userId, req, ipAddress);
        return ResponseEntity.ok(ApiResponse.of(MessageResponse.of("パスワードを変更しました")));
    }

    /**
     * メールアドレス変更をリクエストする。
     *
     * <p><b>認可方式（{@link com.mannschaft.app.common.security.SelfScopedEndpoint} メソッド付与）</b>:
     * {@code SecurityUtils.getCurrentUserId()} のみで対象ユーザーを解決する。
     * 後見切替セッション中は {@code assertNotActingAs()} が代理実行を拒否する。</p>
     *
     * <p>認可根治戦役 Wave5 監査済。</p>
     */
    @SelfScopedEndpoint(
            "SecurityUtils.getCurrentUserId() のみで変更対象ユーザーを解決する"
                    + "（UserController#requestEmailChange）")
    @PatchMapping("/me/email")
    @Operation(summary = "メールアドレス変更リクエスト", description = "新しいメールアドレスへの確認メールを送信する")
    public ResponseEntity<ApiResponse<MessageResponse>> requestEmailChange(
            @Valid @RequestBody RequestEmailChangeRequest req) {
        // F08.9 P3b: 後見切替セッション中はメール変更を代理不可（03_security §3.2）
        authenticationCriticalOperationGuard.assertNotActingAs();
        Long userId = SecurityUtils.getCurrentUserId();
        ApiResponse<MessageResponse> response = userService.requestEmailChange(userId, req);
        return ResponseEntity.ok(response);
    }

    /**
     * メールアドレス変更を確認する。
     *
     * <p><b>認可方式（{@link com.mannschaft.app.common.security.AuthorizedInService} メソッド付与）</b>:
     * リクエストは操作者の識別子を受け取らず、メール送信時に払い出した {@code EmailChangeTokenEntity} の
     * ワンタイム capability トークンのみで対象ユーザーを解決する。{@code UserService#confirmEmailChange}
     * がハッシュ突合・有効期限・使用済みフラグを検証してから反映するため、トークンを知る者だけが
     * 対象ユーザーのメールアドレスを変更できる。後見切替セッション中は {@code assertNotActingAs()} が
     * トークン経由の迂回実行も拒否する。</p>
     *
     * <p>認可根治戦役 Wave5 監査済。</p>
     */
    @AuthorizedInService
    @PostMapping("/me/email/confirm")
    @Operation(summary = "メールアドレス変更確認", description = "確認トークンを検証してメールアドレスを変更する")
    public ResponseEntity<ApiResponse<MessageResponse>> confirmEmailChange(
            @RequestParam String token) {
        // F08.9 P3b: 後見切替セッション中はメール変更確認を代理不可（03_security §3.2）。トークンベースの迂回経路を塞ぐ
        authenticationCriticalOperationGuard.assertNotActingAs();
        ApiResponse<MessageResponse> response = userService.confirmEmailChange(token);
        return ResponseEntity.ok(response);
    }

    /**
     * 退会をリクエストする（論理削除）。
     */
    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "本人の退会要求をGate状態にかかわらず受け付けるため")
    @DeleteMapping("/me")
    @Operation(summary = "退会リクエスト", description = "退会をリクエストする（論理削除。30日間は取り消し可能）")
    public ResponseEntity<ApiResponse<MessageResponse>> requestWithdrawal(
            @Valid @RequestBody RequestWithdrawalRequest req) {
        // F08.9 P3b: 後見切替セッション中は退会を代理不可（03_security §3.2）
        authenticationCriticalOperationGuard.assertNotActingAs();
        Long userId = SecurityUtils.getCurrentUserId();
        userService.requestWithdrawal(userId, req);
        return ResponseEntity.ok(ApiResponse.of(MessageResponse.of("退会リクエストを受け付けました")));
    }

    /**
     * 退会リクエストを取り消す。
     *
     * <p><b>認可方式（{@link com.mannschaft.app.common.security.SelfScopedEndpoint} メソッド付与）</b>:
     * {@code SecurityUtils.getCurrentUserId()} のみで対象ユーザーを解決する。
     * 後見切替セッション中は {@code assertNotActingAs()} が代理実行を拒否する。</p>
     *
     * <p>認可根治戦役 Wave5 監査済。</p>
     */
    @SelfScopedEndpoint(
            "SecurityUtils.getCurrentUserId() のみで取消対象ユーザーを解決する"
                    + "（UserController#cancelWithdrawal）")
    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "本人が退会猶予期間中に取消できる安全経路を維持するため")
    @PostMapping("/me/withdrawal/cancel")
    @Operation(summary = "退会取り消し", description = "退会リクエストを取り消し、アカウントを復帰させる")
    public ResponseEntity<ApiResponse<MessageResponse>> cancelWithdrawal() {
        // F08.9 P3b: 後見切替セッション中は退会取消を代理不可（03_security §3.2）
        authenticationCriticalOperationGuard.assertNotActingAs();
        Long userId = SecurityUtils.getCurrentUserId();
        ApiResponse<MessageResponse> response = userService.cancelWithdrawal(userId);
        return ResponseEntity.ok(response);
    }

    /**
     * 連携済みOAuthプロバイダ一覧を取得する。
     *
     * <p><b>認可方式（{@link com.mannschaft.app.common.security.SelfScopedEndpoint} メソッド付与）</b>:
     * {@code SecurityUtils.getCurrentUserId()} のみで対象ユーザーを解決する。</p>
     *
     * <p>認可根治戦役 Wave5 監査済。</p>
     */
    @SelfScopedEndpoint(
            "SecurityUtils.getCurrentUserId() のみで取得対象ユーザーを解決する"
                    + "（UserController#getConnectedProviders）")
    @GetMapping("/me/oauth")
    @Operation(summary = "OAuth連携一覧取得", description = "連携済みのOAuthプロバイダ一覧を取得する")
    public ResponseEntity<ApiResponse<List<OAuthProviderResponse>>> getConnectedProviders() {
        Long userId = SecurityUtils.getCurrentUserId();
        ApiResponse<List<OAuthProviderResponse>> response = authOAuthService.getConnectedProviders(userId);
        return ResponseEntity.ok(response);
    }

    /**
     * OAuthプロバイダの連携を解除する。
     *
     * <p><b>認可方式（{@link com.mannschaft.app.common.security.SelfScopedEndpoint} メソッド付与）</b>:
     * パス変数 {@code provider} はプロバイダ種別（GOOGLE/LINE/APPLE）を指すのみで他ユーザーを指す
     * 識別子ではなく、対象ユーザーは {@code SecurityUtils.getCurrentUserId()} のみで解決される
     * （{@code AuthOAuthService#disconnectProvider} が {@code findByUserId(userId)} のみで対象を絞る）。</p>
     *
     * <p>認可根治戦役 Wave5 監査済。</p>
     */
    @SelfScopedEndpoint(
            "SecurityUtils.getCurrentUserId() のみで対象ユーザーを解決し、"
                    + "provider はプロバイダ種別に過ぎない（UserController#disconnectProvider）")
    @DeleteMapping("/me/oauth/{provider}")
    @Operation(summary = "OAuth連携解除", description = "指定のOAuthプロバイダとの連携を解除する")
    public ResponseEntity<ApiResponse<MessageResponse>> disconnectProvider(
            @Parameter(description = "OAuthプロバイダ名 (GOOGLE, LINE, APPLE)")
            @PathVariable String provider) {
        Long userId = SecurityUtils.getCurrentUserId();
        authOAuthService.disconnectProvider(userId, provider);
        return ResponseEntity.ok(ApiResponse.of(MessageResponse.of("OAuth連携を解除しました")));
    }

    /**
     * F19.1 Phase 6: プロフィール公開設定を更新する。
     *
     * <p>{@code public_profile_enabled = true} にすると未ログインユーザーも
     * {@code GET /api/v1/public/users/{userId}} でプロフィールを閲覧できる。</p>
     *
     * <p><b>認可方式（{@link com.mannschaft.app.common.security.SelfScopedEndpoint} メソッド付与）</b>:
     * {@code SecurityUtils.getCurrentUserId()} のみで更新対象ユーザーを解決する。
     * {@link UpdateProfileRequest} を用いる {@link #updateMyProfile} と異なり本 EP 専用の
     * {@link UpdatePublicProfileRequest} は {@code publicProfileEnabled} フラグのみを持ち、
     * 他フィールドの取り違えが起きない構造になっている。</p>
     *
     * <p>認可根治戦役 Wave5 監査済。</p>
     */
    @SelfScopedEndpoint(
            "SecurityUtils.getCurrentUserId() のみで更新対象ユーザーを解決する"
                    + "（UserController#updatePublicProfile）")
    @PatchMapping("/me/public-profile")
    @Operation(summary = "プロフィール公開設定更新",
            description = "public_profile_enabled フラグを更新する。true で未ログインユーザーに公開、false で非公開。")
    public ResponseEntity<Void> updatePublicProfile(
            @Valid @RequestBody UpdatePublicProfileRequest req) {
        Long userId = SecurityUtils.getCurrentUserId();
        userService.updatePublicProfileEnabled(userId, req.getPublicProfileEnabled());
        return ResponseEntity.noContent().build();
    }

    /**
     * ログイン履歴を取得する。
     *
     * <p><b>認可方式（{@link com.mannschaft.app.common.security.SelfScopedEndpoint} メソッド付与）</b>:
     * {@code SecurityUtils.getCurrentUserId()} のみで対象ユーザーを解決する。</p>
     *
     * <p>認可根治戦役 Wave5 監査済。</p>
     */
    @SelfScopedEndpoint(
            "SecurityUtils.getCurrentUserId() のみで取得対象ユーザーを解決する"
                    + "（UserController#getLoginHistory）")
    @GetMapping("/me/login-history")
    @Operation(summary = "ログイン履歴取得", description = "認証済みユーザーのログイン履歴をカーソルベースで取得する")
    public ResponseEntity<CursorPagedResponse<LoginHistoryResponse>> getLoginHistory(
            @Parameter(description = "ページングカーソル（nullで先頭から）")
            @RequestParam(required = false) String cursor,
            @Parameter(description = "取得件数（デフォルト5）")
            @RequestParam(defaultValue = "5") int limit,
            @Parameter(description = "開始日時（yyyy-MM-dd'T'HH:mm:ss 形式）")
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime from,
            @Parameter(description = "終了日時（yyyy-MM-dd'T'HH:mm:ss 形式）")
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime to) {
        Long userId = SecurityUtils.getCurrentUserId();
        CursorPagedResponse<LoginHistoryResponse> response = authService.getLoginHistory(userId, cursor, limit, from, to);
        return ResponseEntity.ok(response);
    }
}
