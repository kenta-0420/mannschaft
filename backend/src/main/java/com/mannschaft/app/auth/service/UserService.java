package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.AuthErrorCode;
import com.mannschaft.app.auth.entity.EmailChangeTokenEntity;
import com.mannschaft.app.auth.repository.EmailChangeTokenRepository;
import com.mannschaft.app.auth.repository.OAuthAccountRepository;
import com.mannschaft.app.auth.entity.RefreshTokenEntity;
import com.mannschaft.app.auth.repository.RefreshTokenRepository;
import com.mannschaft.app.auth.entity.TwoFactorAuthEntity;
import com.mannschaft.app.auth.repository.TwoFactorAuthRepository;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.repository.WebAuthnCredentialRepository;
import com.mannschaft.app.auth.dto.ChangePasswordRequest;
import com.mannschaft.app.auth.dto.MessageResponse;
import com.mannschaft.app.auth.dto.RequestEmailChangeRequest;
import com.mannschaft.app.auth.dto.RequestWithdrawalRequest;
import com.mannschaft.app.auth.dto.UpdateProfileRequest;
import com.mannschaft.app.auth.dto.UserProfileResponse;
import com.mannschaft.app.auth.event.EmailChangedEvent;
import com.mannschaft.app.auth.event.EmailChangeRequestedEvent;
import com.mannschaft.app.auth.event.PasswordChangedEvent;
import com.mannschaft.app.auth.event.WithdrawalRequestedEvent;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.DomainEventPublisher;
import com.mannschaft.app.common.EncryptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * ユーザー管理サービス。プロフィール操作・パスワード変更・メール変更・退会を担当する。
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final EmailChangeTokenRepository emailChangeTokenRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final OAuthAccountRepository oauthAccountRepository;
    private final TwoFactorAuthRepository twoFactorAuthRepository;
    private final WebAuthnCredentialRepository webauthnCredentialRepository;
    private final AuthTokenService authTokenService;
    private final PasswordEncoder passwordEncoder;
    private final DomainEventPublisher eventPublisher;
    private final EncryptionService encryptionService;

    /**
     * パスワードポリシー: 8文字以上、大文字・小文字・数字・記号をそれぞれ1文字以上含む
     */
    private static final Pattern PASSWORD_POLICY = Pattern.compile(
            "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?]).{8,}$"
    );

    /**
     * ユーザープロフィールを取得する。
     *
     * @param userId ユーザーID
     * @return プロフィールレスポンス
     */
    public ApiResponse<UserProfileResponse> getUserProfile(Long userId) {
        UserEntity user = findUserOrThrow(userId);

        // 付加情報を収集
        boolean hasPassword = user.getPasswordHash() != null;
        boolean is2faEnabled = twoFactorAuthRepository.findByUserId(userId)
                .map(TwoFactorAuthEntity::getIsEnabled)
                .orElse(false);
        int webauthnCount = webauthnCredentialRepository.findByUserId(userId).size();
        List<String> oauthProviders = oauthAccountRepository.findByUserId(userId).stream()
                .map(oa -> oa.getProvider().name())
                .collect(Collectors.toList());

        UserProfileResponse response = new UserProfileResponse(
                user.getId(),
                user.getEmail(),
                user.getLastName(),
                user.getFirstName(),
                user.getLastNameKana(),
                user.getFirstNameKana(),
                user.getDisplayName(),
                user.getNickname2(),
                user.getIsSearchable(),
                user.getAvatarUrl(),
                user.getPhoneNumber(),
                user.getLocale(),
                user.getTimezone(),
                user.getStatus() != null ? user.getStatus().name() : null,
                hasPassword,
                is2faEnabled,
                webauthnCount,
                oauthProviders,
                user.getLastLoginAt(),
                user.getCreatedAt());

        return ApiResponse.of(response);
    }

    /**
     * ユーザープロフィールを更新する。
     *
     * @param userId ユーザーID
     * @param req    更新リクエスト
     * @return 更新後のプロフィールレスポンス
     */
    @Transactional
    public ApiResponse<UserProfileResponse> updateProfile(Long userId, UpdateProfileRequest req) {
        UserEntity user = findUserOrThrow(userId);

        // Builder パターンで更新（Entityに@Setterは使わない）
        String newLastName = req.getLastName() != null ? req.getLastName() : user.getLastName();
        String newFirstName = req.getFirstName() != null ? req.getFirstName() : user.getFirstName();
        String newPhoneNumber = req.getPhoneNumber() != null ? req.getPhoneNumber() : user.getPhoneNumber();
        UserEntity updated = user.toBuilder()
                .lastName(newLastName)
                .firstName(newFirstName)
                .lastNameKana(req.getLastNameKana() != null ? req.getLastNameKana() : user.getLastNameKana())
                .firstNameKana(req.getFirstNameKana() != null ? req.getFirstNameKana() : user.getFirstNameKana())
                .displayName(req.getDisplayName() != null ? req.getDisplayName() : user.getDisplayName())
                .nickname2(req.getNickname2() != null ? req.getNickname2() : user.getNickname2())
                .isSearchable(req.getIsSearchable() != null ? req.getIsSearchable() : user.getIsSearchable())
                .avatarUrl(req.getAvatarUrl() != null ? req.getAvatarUrl() : user.getAvatarUrl())
                .phoneNumber(newPhoneNumber)
                .postalCode(req.getPostalCode() != null ? req.getPostalCode() : user.getPostalCode())
                .lastNameHash(encryptionService.hmac(newLastName))
                .firstNameHash(encryptionService.hmac(newFirstName))
                .phoneNumberHash(encryptionService.hmac(newPhoneNumber))
                .locale(req.getLocale() != null ? req.getLocale() : user.getLocale())
                .timezone(req.getTimezone() != null ? req.getTimezone() : user.getTimezone())
                .build();
        userRepository.save(updated);

        return getUserProfile(userId);
    }

    /**
     * OAuth専用ユーザー向けにパスワードを設定する。
     * password_hash が NULL の場合のみ許可する。
     *
     * @param userId      ユーザーID
     * @param newPassword 新しいパスワード
     * @return メッセージレスポンス
     */
    @Transactional
    public ApiResponse<MessageResponse> setupPassword(Long userId, String newPassword) {
        UserEntity user = findUserOrThrow(userId);

        // パスワードが既に設定されている場合はエラー
        if (user.getPasswordHash() != null) {
            throw new BusinessException(AuthErrorCode.AUTH_011);
        }

        // パスワードポリシー検証
        validatePasswordPolicy(newPassword);

        UserEntity updated = user.toBuilder()
                .passwordHash(passwordEncoder.encode(newPassword))
                .build();
        userRepository.save(updated);

        return ApiResponse.of(MessageResponse.of("パスワードを設定しました"));
    }

    /**
     * パスワードを変更する。
     * <ol>
     *   <li>レートリミットチェック（1分5回）</li>
     *   <li>現在のパスワード検証</li>
     *   <li>パスワード未設定チェック</li>
     *   <li>同一パスワードチェック</li>
     *   <li>パスワードポリシー検証</li>
     *   <li>パスワード更新 + 全Refresh Token失効 + user_invalidated_at</li>
     *   <li>PasswordChangedEvent発行</li>
     * </ol>
     *
     * @param userId    ユーザーID
     * @param req       パスワード変更リクエスト
     * @param ipAddress リクエスト元IPアドレス
     */
    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest req, String ipAddress) {
        // 1. レートリミット
        authTokenService.checkRateLimit(
                "mannschaft:auth:password_change_attempt:" + userId,
                5,
                Duration.ofMinutes(1)
        );

        UserEntity user = findUserOrThrow(userId);

        // 3. パスワード未設定チェック（OAuth専用ユーザー）
        if (user.getPasswordHash() == null) {
            throw new BusinessException(AuthErrorCode.AUTH_011);
        }

        // 2. 現在のパスワード検証
        if (!passwordEncoder.matches(req.getCurrentPassword(), user.getPasswordHash())) {
            throw new BusinessException(AuthErrorCode.AUTH_010);
        }

        // 4. 同一パスワードチェック
        if (passwordEncoder.matches(req.getNewPassword(), user.getPasswordHash())) {
            throw new BusinessException(AuthErrorCode.AUTH_009);
        }

        // 5. パスワードポリシー検証
        validatePasswordPolicy(req.getNewPassword());

        // 6. パスワード更新
        UserEntity updated = user.toBuilder()
                .passwordHash(passwordEncoder.encode(req.getNewPassword()))
                .build();
        userRepository.save(updated);

        // 全Refresh Token失効
        revokeAllRefreshTokens(userId);

        // user_invalidated_at 設定（全アクセストークン強制失効）
        authTokenService.setUserInvalidationTimestamp(userId);

        // 7. イベント発行
        eventPublisher.publish(new PasswordChangedEvent(userId, ipAddress));
    }

    /**
     * メールアドレス変更をリクエストする。確認メールが送信される。
     * <ol>
     *   <li>レートリミット（1分3回）</li>
     *   <li>新メール重複チェック</li>
     *   <li>パスワード検証</li>
     *   <li>EmailChangeToken生成（24h有効）</li>
     *   <li>EmailChangeRequestedEvent発行</li>
     * </ol>
     *
     * @param userId ユーザーID
     * @param req    メールアドレス変更リクエスト
     * @return メッセージレスポンス
     */
    @Transactional
    public ApiResponse<MessageResponse> requestEmailChange(Long userId, RequestEmailChangeRequest req) {
        // 1. レートリミット
        authTokenService.checkRateLimit(
                "mannschaft:auth:email_change_attempt:" + userId,
                3,
                Duration.ofMinutes(1)
        );

        // 2. 新メール重複チェック
        if (userRepository.existsByEmail(req.getNewEmail())) {
            throw new BusinessException(AuthErrorCode.AUTH_013);
        }

        UserEntity user = findUserOrThrow(userId);

        // 3. パスワード検証
        if (user.getPasswordHash() == null) {
            throw new BusinessException(AuthErrorCode.AUTH_011);
        }
        if (!passwordEncoder.matches(req.getCurrentPassword(), user.getPasswordHash())) {
            throw new BusinessException(AuthErrorCode.AUTH_010);
        }

        // 4. EmailChangeToken生成（24h有効）
        String rawToken = UUID.randomUUID().toString();
        String tokenHash = authTokenService.hashToken(rawToken);

        EmailChangeTokenEntity emailChangeToken = EmailChangeTokenEntity.builder()
                .userId(userId)
                .newEmail(req.getNewEmail())
                .tokenHash(tokenHash)
                .expiresAt(LocalDateTime.now().plusHours(24))
                .build();
        emailChangeTokenRepository.save(emailChangeToken);

        // 5. イベント発行（メール送信をトリガー）
        eventPublisher.publish(new EmailChangeRequestedEvent(userId, req.getNewEmail(), rawToken));

        return ApiResponse.of(MessageResponse.of("確認メールを送信しました"));
    }

    /**
     * メールアドレス変更を確認する。
     * <ol>
     *   <li>トークン検証</li>
     *   <li>新メール重複再チェック</li>
     *   <li>メール更新 + 全Refresh Token失効 + user_invalidated_at</li>
     *   <li>EmailChangedEvent発行</li>
     * </ol>
     *
     * @param token メールアドレス変更トークン（平文）
     * @return メッセージレスポンス
     */
    @Transactional
    public ApiResponse<MessageResponse> confirmEmailChange(String token) {
        String tokenHash = authTokenService.hashToken(token);

        // 1. トークン検証
        EmailChangeTokenEntity emailChangeToken = emailChangeTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new BusinessException(AuthErrorCode.AUTH_012));

        // 有効期限チェック
        if (emailChangeToken.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(AuthErrorCode.AUTH_012);
        }

        // 使用済みチェック
        if (emailChangeToken.getUsedAt() != null) {
            throw new BusinessException(AuthErrorCode.AUTH_012);
        }

        // 2. 新メール重複再チェック
        if (userRepository.existsByEmail(emailChangeToken.getNewEmail())) {
            throw new BusinessException(AuthErrorCode.AUTH_013);
        }

        // 3. メール更新
        UserEntity user = findUserOrThrow(emailChangeToken.getUserId());
        String oldEmail = user.getEmail();

        UserEntity updated = user.toBuilder()
                .email(emailChangeToken.getNewEmail())
                .build();
        userRepository.save(updated);

        // トークンを使用済みにする
        emailChangeToken.markUsed();
        emailChangeTokenRepository.save(emailChangeToken);

        // 全Refresh Token失効
        revokeAllRefreshTokens(user.getId());

        // user_invalidated_at 設定
        authTokenService.setUserInvalidationTimestamp(user.getId());

        // 4. イベント発行
        eventPublisher.publish(new EmailChangedEvent(user.getId(), oldEmail, emailChangeToken.getNewEmail()));

        return ApiResponse.of(MessageResponse.of("メールアドレスを変更しました"));
    }

    /**
     * 退会をリクエストする（論理削除）。
     * <ol>
     *   <li>レートリミット</li>
     *   <li>パスワード検証（OAuth専用ユーザーはスキップ）</li>
     *   <li>退会処理（deleted_at設定）</li>
     *   <li>全Refresh Token失効 + user_invalidated_at</li>
     *   <li>WithdrawalRequestedEvent発行</li>
     * </ol>
     *
     * @param userId ユーザーID
     * @param req    退会リクエスト
     */
    @Transactional
    public void requestWithdrawal(Long userId, RequestWithdrawalRequest req) {
        // 1. レートリミット
        authTokenService.checkRateLimit(
                "mannschaft:auth:withdrawal_attempt:" + userId,
                3,
                Duration.ofMinutes(1)
        );

        UserEntity user = findUserOrThrow(userId);

        // 2. パスワード検証（OAuth専用ユーザーはスキップ）
        if (user.getPasswordHash() != null) {
            if (req.getCurrentPassword() == null || !passwordEncoder.matches(req.getCurrentPassword(), user.getPasswordHash())) {
                throw new BusinessException(AuthErrorCode.AUTH_010);
            }
        }

        // 3. 退会処理
        user.requestDeletion();
        userRepository.save(user);

        // 4. 全Refresh Token失効 + user_invalidated_at
        revokeAllRefreshTokens(userId);
        authTokenService.setUserInvalidationTimestamp(userId);

        // 5. イベント発行
        eventPublisher.publish(new WithdrawalRequestedEvent(userId, user.getEmail()));
    }

    /**
     * 退会リクエストを取り消す。
     *
     * @param userId ユーザーID
     * @return メッセージレスポンス
     */
    @Transactional
    public ApiResponse<MessageResponse> cancelWithdrawal(Long userId) {
        UserEntity user = findUserOrThrow(userId);

        // deleted_at が NULL の場合、退会リクエストが存在しない
        if (user.getDeletedAt() == null) {
            throw new BusinessException(AuthErrorCode.AUTH_032);
        }

        user.cancelDeletion();
        userRepository.save(user);

        return ApiResponse.of(MessageResponse.of("退会リクエストを取り消しました"));
    }

    // === ヘルパーメソッド ===

    /**
     * ユーザーを取得する。見つからない場合は AUTH_015 をスロー。
     */
    private UserEntity findUserOrThrow(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(AuthErrorCode.AUTH_015));
    }

    /**
     * パスワードポリシーを検証する。違反時は AUTH_008 をスロー。
     */
    private void validatePasswordPolicy(String password) {
        if (!PASSWORD_POLICY.matcher(password).matches()) {
            throw new BusinessException(AuthErrorCode.AUTH_008);
        }
    }

    /**
     * 指定ユーザーの全Refresh Tokenを失効させる。
     */
    private void revokeAllRefreshTokens(Long userId) {
        List<RefreshTokenEntity> tokens = refreshTokenRepository.findByUserIdAndRevokedAtIsNull(userId);
        tokens.forEach(RefreshTokenEntity::revoke);
        refreshTokenRepository.saveAll(tokens);
    }
}
