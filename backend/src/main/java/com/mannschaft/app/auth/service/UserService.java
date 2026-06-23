package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.AuthErrorCode;
import com.mannschaft.app.auth.DmReceiveFrom;
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
import com.mannschaft.app.auth.event.PasswordSetupEvent;
import com.mannschaft.app.auth.event.UserAnonymizedEvent;
import com.mannschaft.app.auth.event.WithdrawalCancelledEvent;
import com.mannschaft.app.auth.event.WithdrawalRequestedEvent;
import com.mannschaft.app.auth.util.PasswordPolicyValidator;
import com.mannschaft.app.weather.event.UserPostalCodeUpdatedEvent;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.DomainEventPublisher;
import com.mannschaft.app.common.EncryptionService;
import com.mannschaft.app.role.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * ユーザー管理サービス。プロフィール操作・パスワード変更・メール変更・退会を担当する。
 */
@Slf4j
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
    private final UserRoleRepository userRoleRepository;
    private final ParentalConsentService parentalConsentService;
    private final AccessControlService accessControlService;

    /**
     * ISO 3166-1 alpha-2 国コード: アルファベット大文字2文字
     */
    private static final Pattern COUNTRY_CODE_PATTERN = Pattern.compile("^[A-Z]{2}$");

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

        // TODO: userRoleRepository.isSystemAdmin() は role ドメイン直接参照。
        //   将来は accessControlService.isSystemAdmin(userId) に移行予定（既存 API の変更影響を考慮し現状維持）
        String systemRole = userRoleRepository.isSystemAdmin(userId) > 0 ? "SYSTEM_ADMIN" : null;

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
                user.getPostalCode(),
                user.getLocale(),
                user.getCountryCode(),
                user.getTimezone(),
                user.getStatus() != null ? user.getStatus().name() : null,
                hasPassword,
                is2faEnabled,
                webauthnCount,
                oauthProviders,
                user.getLastLoginAt(),
                user.getCreatedAt(),
                systemRole,
                user.isPublicProfileEnabled());

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

        // countryCode バリデーション（ISO 3166-1 alpha-2: アルファベット大文字2文字）
        if (req.getCountryCode() != null && !COUNTRY_CODE_PATTERN.matcher(req.getCountryCode()).matches()) {
            throw new BusinessException(AuthErrorCode.AUTH_040);
        }

        // F02.10: postalCode / countryCode の変更前の値を記録
        String oldPostalCode = user.getPostalCode();
        String oldCountryCode = user.getCountryCode();

        // 直接ミューテートで更新（toBuilder().build() は継承フィールド id を欠落させ INSERT 化→email 一意制約500。PR #1643 と同型の根治）
        String newLastName = req.getLastName() != null ? req.getLastName() : user.getLastName();
        String newFirstName = req.getFirstName() != null ? req.getFirstName() : user.getFirstName();
        String newPhoneNumber = req.getPhoneNumber() != null ? req.getPhoneNumber() : user.getPhoneNumber();
        DmReceiveFrom newDmReceiveFrom = req.getDmReceiveFrom() != null ? req.getDmReceiveFrom() : user.getDmReceiveFrom();
        user.applyProfileUpdate(
                newLastName,
                newFirstName,
                req.getLastNameKana() != null ? req.getLastNameKana() : user.getLastNameKana(),
                req.getFirstNameKana() != null ? req.getFirstNameKana() : user.getFirstNameKana(),
                req.getNickname() != null ? req.getNickname() : user.getDisplayName(),
                req.getNickname2() != null ? req.getNickname2() : user.getNickname2(),
                req.getIsSearchable() != null ? req.getIsSearchable() : user.getIsSearchable(),
                req.getAvatarUrl() != null ? req.getAvatarUrl() : user.getAvatarUrl(),
                newPhoneNumber,
                req.getPostalCode() != null ? req.getPostalCode() : user.getPostalCode(),
                encryptionService.hmac(newLastName),
                encryptionService.hmac(newFirstName),
                encryptionService.hmac(newPhoneNumber),
                req.getLocale() != null ? req.getLocale() : user.getLocale(),
                req.getCountryCode() != null ? req.getCountryCode() : user.getCountryCode(),
                req.getTimezone() != null ? req.getTimezone() : user.getTimezone(),
                newDmReceiveFrom);
        userRepository.save(user);

        // F02.10: postalCode または countryCode が変化した場合に WeatherLocationEventListener を起動
        if (!Objects.equals(oldPostalCode, user.getPostalCode())
                || !Objects.equals(oldCountryCode, user.getCountryCode())) {
            eventPublisher.publish(new UserPostalCodeUpdatedEvent(userId));
            log.debug("UserPostalCodeUpdatedEvent 発行: userId={}", userId);
        }

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

        // 直接ミューテート（toBuilder().build() は id 欠落で INSERT 化→500。PR #1643 と同型の根治）
        user.updatePasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        // イベント発行
        eventPublisher.publish(new PasswordSetupEvent(userId));

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

        // 6. パスワード更新（直接ミューテート。toBuilder().build() は id 欠落で INSERT 化→500。PR #1643 と同型の根治）
        user.updatePasswordHash(passwordEncoder.encode(req.getNewPassword()));
        userRepository.save(user);

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

        // 直接ミューテート（toBuilder().build() は id 欠落で INSERT 化→email 一意制約500。PR #1643 と同型の根治）
        user.updateEmail(emailChangeToken.getNewEmail());
        userRepository.save(user);

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
        // 唯一の SYSTEM_ADMIN であれば退会をブロック（role ドメイン参照は AccessControlService 経由に集約）
        accessControlService.checkNotLastSystemAdmin(userId);

        // F01.9: 唯一の保護者退会ブロック
        parentalConsentService.checkWithdrawalBlock(userId);

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
     * <ol>
     *   <li>deleted_at が NULL の場合 AUTH_032 例外</li>
     *   <li>deleted_at を NULL に戻す（退会キャンセル）</li>
     *   <li>WithdrawalCancelledEvent 発行（監査ログ記録用）</li>
     * </ol>
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

        // イベント発行（監査ログ AuditLogEventListener#handleWithdrawalCancelled が購読）
        eventPublisher.publish(new WithdrawalCancelledEvent(userId));

        return ApiResponse.of(MessageResponse.of("退会リクエストを取り消しました"));
    }

    /**
     * ユーザー退会処理（即時匿名化）。
     * <p>
     * 氏名・メールアドレス・アイコン等の個人情報（PII）を即時消去し論理削除する。
     * 投稿・履歴・統計データは user_id を保持したまま残す（統計価値 + GDPR対応の両立）。
     * </p>
     * <ol>
     *   <li>唯一の SYSTEM_ADMIN であれば退会をブロック</li>
     *   <li>全Refresh Token失効 + user_invalidated_at（セッション即時無効化）</li>
     *   <li>個人情報の匿名化 + 論理削除（email を UUID ダミー値で上書き）</li>
     *   <li>UserAnonymizedEvent 発行（監査ログ・後処理用）</li>
     * </ol>
     *
     * @param userId 退会対象ユーザーID
     */
    @Transactional
    public void withdrawUser(Long userId) {
        // 唯一の SYSTEM_ADMIN であれば退会をブロック（role ドメイン参照は AccessControlService 経由に集約）
        accessControlService.checkNotLastSystemAdmin(userId);

        UserEntity user = findUserOrThrow(userId);

        // 匿名化前にメールアドレスを保持（イベント発行用）
        String originalEmail = user.getEmail();

        // セッション・トークン類を即時無効化（一時データは削除してよい）
        revokeAllRefreshTokens(userId);
        authTokenService.setUserInvalidationTimestamp(userId);

        // 個人情報の匿名化 + 論理削除（CLAUDE.md「DB設計の原則 §4」二段階呼出）
        user.anonymize();
        user.softDelete();
        userRepository.save(user);

        // 監査ログ・後処理用イベント発行
        eventPublisher.publish(new UserAnonymizedEvent(userId, originalEmail));

        log.info("ユーザー退会（即時匿名化）完了: userId={}", userId);
    }

    /**
     * F19.1 Phase 6: プロフィール公開設定を更新する。
     *
     * @param userId  ユーザーID
     * @param enabled true にすると未ログインユーザーもプロフィールを閲覧できる
     */
    @Transactional
    public void updatePublicProfileEnabled(Long userId, boolean enabled) {
        UserEntity user = findUserOrThrow(userId);
        user.updatePublicProfileEnabled(enabled);
        userRepository.save(user);
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
     *
     * <p>登録時（{@link com.mannschaft.app.auth.util.PasswordPolicyValidator}）と同一ポリシー
     * （8文字以上 + 大文字/小文字/数字/記号のうち3種以上）に統一している。
     * 以前は変更時のみ「4種すべて必須」だったため、登録できたパスワードが変更時に弾かれる不整合があった。</p>
     */
    private void validatePasswordPolicy(String password) {
        PasswordPolicyValidator.validate(password);
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
