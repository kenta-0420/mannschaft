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
     * @param email     メールアドレス
     * @param ipAddress リクエスト元IPアドレス
     * @return リセットメール送信メッセージ
     */
    @Transactional
    public ApiResponse<MessageResponse> requestPasswordReset(String email, String ipAddress) {
        // 1. レートリミットチェック
        String rateLimitKey = "mannschaft:auth:password_reset_attempt:" + ipAddress;
        authTokenService.checkRateLimit(rateLimitKey, PASSWORD_RESET_MAX_ATTEMPTS, PASSWORD_RESET_WINDOW);

        // 2. ユーザー検索（不在でも同一レスポンス）
        Optional<UserEntity> userOpt = userRepository.findByEmail(email);
        if (userOpt.isEmpty()) {
            return ApiResponse.of(new MessageResponse("パスワードリセットメールを送信しました"));
        }

        UserEntity user = userOpt.get();

        // 3. PasswordResetToken生成
        String rawToken = SecureTokenGenerator.generate();
        String tokenHash = authTokenService.hashToken(rawToken);
        PasswordResetTokenEntity resetToken = PasswordResetTokenEntity.builder()
                .userId(user.getId())
                .tokenHash(tokenHash)
                .expiresAt(LocalDateTime.now().plus(PASSWORD_RESET_EXPIRY))
                .build();
        passwordResetTokenRepository.save(resetToken);

        // 4. イベント発行
        eventPublisher.publish(new PasswordResetRequestedEvent(
                user.getId(), user.getEmail(), rawToken));

        return ApiResponse.of(new MessageResponse("パスワードリセットメールを送信しました"));
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
