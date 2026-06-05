package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.AuthErrorCode;
import com.mannschaft.app.auth.dto.ConfirmPasswordResetRequest;
import com.mannschaft.app.auth.dto.MessageResponse;
import com.mannschaft.app.auth.entity.PasswordResetTokenEntity;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.event.PasswordResetCompletedEvent;
import com.mannschaft.app.auth.event.PasswordResetRequestedEvent;
import com.mannschaft.app.auth.repository.PasswordResetTokenRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.util.PasswordPolicyValidator;
import com.mannschaft.app.auth.util.SecureTokenGenerator;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.DomainEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * パスワードリセットサービス。
 * リセット要求トークン発行と、リセット確認・パスワード更新・全デバイス無効化を担う。
 *
 * <p>セキュリティ重要: 全メソッドのロジック・トランザクション境界・イベント発行タイミング・
 * エラーコードは AuthService から移送した byte-identical な実装である。
 * リセット完了時の全デバイス無効化（logoutAllDevices）は {@link AuthSessionService} へ委譲する。</p>
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class AuthPasswordResetService {

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final AuthTokenService authTokenService;
    private final AuthSessionService authSessionService;
    private final PasswordEncoder passwordEncoder;
    private final DomainEventPublisher eventPublisher;

    // レートリミット設定
    private static final int PASSWORD_RESET_MAX_ATTEMPTS = 3;
    private static final Duration PASSWORD_RESET_WINDOW = Duration.ofMinutes(1);

    // トークン有効期限
    private static final Duration PASSWORD_RESET_EXPIRY = Duration.ofMinutes(30);

    /**
     * パスワードリセットを要求する。
     * ユーザー不在でも同一レスポンスを返す（情報漏洩防止）。
     *
     * <p>公開エンドポイント（{@code POST /auth/password-reset}）から呼ばれる経路。
     * IP 単位のレートリミット（{@value #PASSWORD_RESET_WINDOW} あたり
     * {@value #PASSWORD_RESET_MAX_ATTEMPTS} 回）を通したうえでトークンを発行する。</p>
     *
     * @param email     メールアドレス
     * @param ipAddress リクエスト元IPアドレス
     * @return リセットメール送信メッセージ
     */
    @Transactional
    public ApiResponse<MessageResponse> requestPasswordReset(String email, String ipAddress) {
        // 1. レートリミットチェック（公開 EP の濫用防止）
        String rateLimitKey = "mannschaft:auth:password_reset_attempt:" + ipAddress;
        authTokenService.checkRateLimit(rateLimitKey, PASSWORD_RESET_MAX_ATTEMPTS, PASSWORD_RESET_WINDOW);

        // 2. トークン発行（共通ロジック）
        issuePasswordResetToken(email);

        return ApiResponse.of(new MessageResponse("パスワードリセットメールを送信しました"));
    }

    /**
     * システムバッチ専用のパスワードリセット要求。<b>公開エンドポイントから呼んではならない。</b>
     *
     * <p>{@link com.mannschaft.app.auth.guardianship.GuardianshipSealUnsetPasswordBatchService
     * 封印時未設定メールバッチ} 等、信頼済みのサーバー内バッチからのみ呼び出す。
     * バッチは {@code @SchedulerLock} で単一実行が保証され、宛先も DB から導出した信頼済みアドレスのため、
     * IP 単位レートリミット（公開 EP 用の濫用防止）を意図的に <b>通さない</b>。
     * これにより 1 分間に 4 件以上の自動送付がレート制限で取りこぼされる事故を防ぐ。</p>
     *
     * <p>トークン生成・期限・イベント発行（{@link PasswordResetRequestedEvent} 経由の
     * F09.18 outbox 送付）など、レートリミット以外の挙動は公開メソッドと完全に共有する。</p>
     *
     * @param email メールアドレス（信頼済み・DB 由来）
     */
    @Transactional
    public void requestPasswordResetForSystemBatch(String email) {
        // レートリミットを通さずにトークンを発行する（バッチは信頼済み・単一実行保証）。
        issuePasswordResetToken(email);
    }

    /**
     * パスワードリセットトークンを発行し、リセットメール送付イベントを発行する共通ロジック。
     * ユーザー不在でも何もせず正常終了する（情報漏洩防止・公開/バッチ両経路で同一挙動）。
     */
    private void issuePasswordResetToken(String email) {
        // ユーザー検索（不在でも何もしない）
        Optional<UserEntity> userOpt = userRepository.findByEmail(email);
        if (userOpt.isEmpty()) {
            return;
        }

        UserEntity user = userOpt.get();

        // PasswordResetToken生成
        String rawToken = SecureTokenGenerator.generate();
        String tokenHash = authTokenService.hashToken(rawToken);
        PasswordResetTokenEntity resetToken = PasswordResetTokenEntity.builder()
                .userId(user.getId())
                .tokenHash(tokenHash)
                .expiresAt(LocalDateTime.now().plus(PASSWORD_RESET_EXPIRY))
                .build();
        passwordResetTokenRepository.save(resetToken);

        // イベント発行（PasswordResetRequestedEvent → F09.18 outbox 経由でメール送付）
        eventPublisher.publish(new PasswordResetRequestedEvent(
                user.getId(), user.getEmail(), rawToken));
    }

    /**
     * パスワードリセットを確認・実行する。
     * トークン検証 → パスワード更新 → 全RefreshToken失効 → 全デバイス無効化。
     *
     * @param req パスワードリセット確認リクエスト
     * @return 完了メッセージ
     */
    @Transactional
    public ApiResponse<MessageResponse> confirmPasswordReset(ConfirmPasswordResetRequest req) {
        // 1. トークン検証
        String tokenHash = authTokenService.hashToken(req.getToken());
        PasswordResetTokenEntity resetToken = passwordResetTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new BusinessException(AuthErrorCode.AUTH_015));

        if (resetToken.getUsedAt() != null) {
            throw new BusinessException(AuthErrorCode.AUTH_015);
        }
        if (resetToken.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(AuthErrorCode.AUTH_015);
        }

        // 2. パスワードポリシー検証
        PasswordPolicyValidator.validate(req.getNewPassword());

        // 3. パスワード更新
        UserEntity user = userRepository.findById(resetToken.getUserId())
                .orElseThrow(() -> new BusinessException(AuthErrorCode.AUTH_015));

        user.updatePasswordHash(passwordEncoder.encode(req.getNewPassword()));

        // トークンを使用済みにする
        resetToken.markUsed();

        // 4. 全RefreshToken失効 + 全デバイス無効化
        authSessionService.logoutAllDevices(user.getId());

        // 5. イベント発行
        eventPublisher.publish(new PasswordResetCompletedEvent(user.getId(), user.getEmail()));

        return ApiResponse.of(new MessageResponse("パスワードが正常に変更されました"));
    }
}
