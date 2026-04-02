package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.AuthErrorCode;
import com.mannschaft.app.auth.entity.EmailVerificationTokenEntity;
import com.mannschaft.app.auth.repository.EmailVerificationTokenRepository;
import com.mannschaft.app.auth.entity.PasswordResetTokenEntity;
import com.mannschaft.app.auth.repository.PasswordResetTokenRepository;
import com.mannschaft.app.auth.entity.RefreshTokenEntity;
import com.mannschaft.app.auth.repository.RefreshTokenRepository;
import com.mannschaft.app.auth.entity.TwoFactorAuthEntity;
import com.mannschaft.app.auth.repository.TwoFactorAuthRepository;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.dto.ConfirmPasswordResetRequest;
import com.mannschaft.app.auth.dto.LoginHistoryResponse;
import com.mannschaft.app.auth.dto.LoginRequest;
import com.mannschaft.app.auth.dto.LoginResponse;
import com.mannschaft.app.auth.dto.MessageResponse;
import com.mannschaft.app.auth.dto.MfaRequiredResponse;
import com.mannschaft.app.auth.dto.RegisterRequest;
import com.mannschaft.app.auth.dto.SessionResponse;
import com.mannschaft.app.auth.dto.TokenResponse;
import com.mannschaft.app.auth.event.AccountLockedEvent;
import com.mannschaft.app.auth.event.EmailVerificationResentEvent;
import com.mannschaft.app.auth.event.EmailVerifiedEvent;
import com.mannschaft.app.auth.event.LoginFailedEvent;
import com.mannschaft.app.auth.event.LoginSuccessEvent;
import com.mannschaft.app.auth.event.LogoutEvent;
import com.mannschaft.app.auth.event.LogoutEvent.LogoutType;
import com.mannschaft.app.auth.event.PasswordResetCompletedEvent;
import com.mannschaft.app.auth.event.PasswordResetRequestedEvent;
import com.mannschaft.app.auth.event.UserRegisteredEvent;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CursorPagedResponse;
import com.mannschaft.app.common.DomainEventPublisher;
import com.mannschaft.app.common.EncryptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 認証コアサービス。ユーザー登録・ログイン・ログアウト・Refresh Tokenローテーション・
 * パスワードリセット等の認証ロジックを提供する。
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserRepository userRepository;
    private final EmailVerificationTokenRepository emailVerificationTokenRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final TwoFactorAuthRepository twoFactorAuthRepository;
    private final AuthTokenService authTokenService;
    private final PasswordEncoder passwordEncoder;
    private final DomainEventPublisher eventPublisher;
    private final StringRedisTemplate redisTemplate;
    private final EncryptionService encryptionService;

    // レートリミット設定
    private static final int REGISTER_MAX_ATTEMPTS = 10;
    private static final Duration REGISTER_WINDOW = Duration.ofHours(1);
    private static final int LOGIN_MAX_ATTEMPTS = 10;
    private static final Duration LOGIN_WINDOW = Duration.ofMinutes(1);
    private static final int PASSWORD_RESET_MAX_ATTEMPTS = 3;
    private static final Duration PASSWORD_RESET_WINDOW = Duration.ofMinutes(1);

    // アカウントロック設定
    private static final int ACCOUNT_LOCK_THRESHOLD = 5;
    private static final Duration ACCOUNT_LOCK_DURATION = Duration.ofMinutes(30);
    private static final String ACCOUNT_LOCK_KEY_PREFIX = "mannschaft:auth:account_lock:";
    private static final String LOGIN_FAIL_COUNT_KEY_PREFIX = "mannschaft:auth:login_fail_count:";
    private static final String EMAIL_VERIFY_COOLDOWN_PREFIX = "mannschaft:auth:email_verify_cooldown:";
    private static final String MFA_SESSION_KEY_PREFIX = "mannschaft:auth:mfa_session:";

    // パスワードポリシー
    private static final int PASSWORD_MIN_LENGTH = 8;
    private static final Pattern UPPERCASE_PATTERN = Pattern.compile("[A-Z]");
    private static final Pattern LOWERCASE_PATTERN = Pattern.compile("[a-z]");
    private static final Pattern DIGIT_PATTERN = Pattern.compile("[0-9]");
    private static final Pattern SYMBOL_PATTERN = Pattern.compile("[^A-Za-z0-9]");

    // トークン有効期限
    private static final Duration EMAIL_VERIFICATION_EXPIRY = Duration.ofHours(24);
    private static final Duration PASSWORD_RESET_EXPIRY = Duration.ofMinutes(30);
    private static final Duration MFA_SESSION_EXPIRY = Duration.ofMinutes(5);

    // ========================================
    // 登録
    // ========================================

    /**
     * ユーザー登録を行う。
     * レートリミット確認 → email重複チェック → パスワードポリシー検証 → ユーザー作成 →
     * メール認証トークン生成 → イベント発行。
     *
     * @param req       登録リクエスト
     * @param ipAddress リクエスト元IPアドレス
     * @return 登録完了メッセージ
     */
    @Transactional
    public ApiResponse<MessageResponse> register(RegisterRequest req, String ipAddress) {
        // 1. レートリミットチェック
        String rateLimitKey = "mannschaft:auth:register_attempt:" + ipAddress;
        authTokenService.checkRateLimit(rateLimitKey, REGISTER_MAX_ATTEMPTS, REGISTER_WINDOW);

        // 2. email重複チェック
        if (userRepository.existsByEmail(req.getEmail())) {
            throw new BusinessException(AuthErrorCode.AUTH_004);
        }

        // 3. パスワードポリシー検証
        validatePasswordPolicy(req.getPassword());

        // 4. ユーザー作成
        UserEntity user = UserEntity.builder()
                .email(req.getEmail())
                .passwordHash(passwordEncoder.encode(req.getPassword()))
                .lastName(req.getLastName())
                .firstName(req.getFirstName())
                .lastNameHash(encryptionService.hmac(req.getLastName()))
                .firstNameHash(encryptionService.hmac(req.getFirstName()))
                .displayName(req.getDisplayName())
                .postalCode(req.getPostalCode())
                .locale(req.getLocale() != null ? req.getLocale() : "ja")
                .timezone(req.getTimezone() != null ? req.getTimezone() : "Asia/Tokyo")
                .status(UserEntity.UserStatus.PENDING_VERIFICATION)
                .isSearchable(true)
                .build();
        userRepository.save(user);

        // 5. メール認証トークン生成（SHA-256ハッシュをDB保存、平文はイベントで送信）
        String rawToken = generateSecureToken();
        String tokenHash = authTokenService.hashToken(rawToken);
        EmailVerificationTokenEntity verificationToken = EmailVerificationTokenEntity.builder()
                .userId(user.getId())
                .tokenHash(tokenHash)
                .expiresAt(LocalDateTime.now().plus(EMAIL_VERIFICATION_EXPIRY))
                .build();
        emailVerificationTokenRepository.save(verificationToken);

        // 6. イベント発行（メール送信は非同期リスナーが処理）
        eventPublisher.publish(new UserRegisteredEvent(
                user.getId(), user.getEmail(), user.getDisplayName(), rawToken));

        // 7. レスポンス
        return ApiResponse.of(new MessageResponse("確認メールを送信しました"));
    }

    // ========================================
    // メール確認
    // ========================================

    /**
     * メール認証トークンを検証し、ユーザーを有効化する。
     *
     * @param token 平文トークン
     * @return 認証完了メッセージ
     */
    @Transactional
    public ApiResponse<MessageResponse> verifyEmail(String token) {
        // 1. トークンをSHA-256ハッシュ化してDB検索
        String tokenHash = authTokenService.hashToken(token);
        EmailVerificationTokenEntity verificationToken = emailVerificationTokenRepository
                .findByTokenHash(tokenHash)
                .orElseThrow(() -> new BusinessException(AuthErrorCode.AUTH_005));

        // 2. 期限切れ / 使用済みチェック
        if (verificationToken.getUsedAt() != null) {
            throw new BusinessException(AuthErrorCode.AUTH_005);
        }
        if (verificationToken.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(AuthErrorCode.AUTH_005);
        }

        // 3. トークンを使用済みにする
        verificationToken.markUsed();

        // 4. ユーザーを有効化
        UserEntity user = userRepository.findById(verificationToken.getUserId())
                .orElseThrow(() -> new BusinessException(AuthErrorCode.AUTH_005));
        user.activate();

        // 5. イベント発行
        eventPublisher.publish(new EmailVerifiedEvent(user.getId(), user.getEmail()));

        return ApiResponse.of(new MessageResponse("メール認証が完了しました"));
    }

    /**
     * メール認証メールを再送信する。
     * クールダウン期間中は再送不可。ユーザー不在でも同一レスポンスを返す（情報漏洩防止）。
     *
     * @param email メールアドレス
     * @return 再送完了メッセージ
     */
    @Transactional
    public ApiResponse<MessageResponse> resendVerificationEmail(String email) {
        // 1. Valkeyクールダウンチェック（60秒）
        String cooldownKey = EMAIL_VERIFY_COOLDOWN_PREFIX + email;
        if (Boolean.TRUE.equals(redisTemplate.hasKey(cooldownKey))) {
            throw new BusinessException(AuthErrorCode.AUTH_006);
        }

        // クールダウンを設定（ユーザー有無に関わらず）
        redisTemplate.opsForValue().set(cooldownKey, "1", 60, TimeUnit.SECONDS);

        // 2. ユーザー検索（PENDING_VERIFICATION状態のみ）
        Optional<UserEntity> userOpt = userRepository.findByEmail(email);
        if (userOpt.isEmpty() || userOpt.get().getStatus() != UserEntity.UserStatus.PENDING_VERIFICATION) {
            // 情報漏洩防止: ユーザー不在でも同一レスポンス
            return ApiResponse.of(new MessageResponse("確認メールを送信しました"));
        }

        UserEntity user = userOpt.get();

        // 3. 新トークン生成
        String rawToken = generateSecureToken();
        String tokenHash = authTokenService.hashToken(rawToken);
        EmailVerificationTokenEntity verificationToken = EmailVerificationTokenEntity.builder()
                .userId(user.getId())
                .tokenHash(tokenHash)
                .expiresAt(LocalDateTime.now().plus(EMAIL_VERIFICATION_EXPIRY))
                .build();
        emailVerificationTokenRepository.save(verificationToken);

        // 4. イベント発行
        eventPublisher.publish(new EmailVerificationResentEvent(
                user.getId(), user.getEmail(), rawToken));

        return ApiResponse.of(new MessageResponse("確認メールを送信しました"));
    }

    // ========================================
    // ログイン
    // ========================================

    /**
     * ログイン処理を行う。
     * レートリミット → ユーザー検索 → ステータス確認 → アカウントロック確認 →
     * パスワード検証 → 2FA確認 → トークン発行。
     *
     * @param req       ログインリクエスト
     * @param ipAddress リクエスト元IPアドレス
     * @param userAgent User-Agent
     * @return ログイン成功レスポンス（LoginResponse）または 2FA要求レスポンス（MfaRequiredResponse）
     */
    @Transactional
    public ApiResponse<?> login(LoginRequest req, String ipAddress, String userAgent) {
        // 1. レートリミットチェック
        String rateLimitKey = "mannschaft:auth:login_attempt:" + req.getEmail() + ":" + ipAddress;
        authTokenService.checkRateLimit(rateLimitKey, LOGIN_MAX_ATTEMPTS, LOGIN_WINDOW);

        // 2. ユーザー検索（不在でもダミーbcrypt検証 → タイミング攻撃対策）
        Optional<UserEntity> userOpt = userRepository.findByEmail(req.getEmail());
        if (userOpt.isEmpty()) {
            // タイミング攻撃対策: ダミーのbcrypt検証を実行して処理時間を合わせる
            passwordEncoder.matches(req.getPassword(), "$2a$12$000000000000000000000uGHJKLMNOPQRSTUVWXYZ012345678901");
            eventPublisher.publish(new LoginFailedEvent(
                    req.getEmail(), ipAddress, userAgent, "USER_NOT_FOUND"));
            throw new BusinessException(AuthErrorCode.AUTH_009);
        }

        UserEntity user = userOpt.get();

        // 3. ステータス確認
        if (user.getStatus() == UserEntity.UserStatus.PENDING_VERIFICATION) {
            eventPublisher.publish(new LoginFailedEvent(
                    req.getEmail(), ipAddress, userAgent, "PENDING_VERIFICATION"));
            throw new BusinessException(AuthErrorCode.AUTH_002);
        }
        if (user.getStatus() == UserEntity.UserStatus.FROZEN) {
            eventPublisher.publish(new LoginFailedEvent(
                    req.getEmail(), ipAddress, userAgent, "FROZEN"));
            throw new BusinessException(AuthErrorCode.AUTH_003);
        }

        // 4. アカウントロックチェック
        String lockKey = ACCOUNT_LOCK_KEY_PREFIX + user.getId();
        if (Boolean.TRUE.equals(redisTemplate.hasKey(lockKey))) {
            eventPublisher.publish(new LoginFailedEvent(
                    req.getEmail(), ipAddress, userAgent, "ACCOUNT_LOCKED"));
            throw new BusinessException(AuthErrorCode.AUTH_003);
        }

        // 5. パスワード検証
        if (!passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
            // ログイン失敗回数をインクリメント
            handleLoginFailure(user.getId(), req.getEmail(), ipAddress, userAgent);
            throw new BusinessException(AuthErrorCode.AUTH_009);
        }

        // パスワード検証成功 → 失敗カウンタをリセット
        String failCountKey = LOGIN_FAIL_COUNT_KEY_PREFIX + user.getId();
        redisTemplate.delete(failCountKey);

        // 6. ARCHIVED状態 → 自動復帰
        if (user.getStatus() == UserEntity.UserStatus.ARCHIVED) {
            user.unarchive();
            log.info("ユーザー[{}]をアーカイブ状態から自動復帰しました", user.getId());
        }

        // 7. 二要素認証チェック
        Optional<TwoFactorAuthEntity> mfaOpt = twoFactorAuthRepository.findByUserId(user.getId());
        if (mfaOpt.isPresent() && Boolean.TRUE.equals(mfaOpt.get().getIsEnabled())) {
            // 2FA有効: MFAセッショントークンを生成してValkeyに保存
            String mfaSessionToken = UUID.randomUUID().toString();
            String mfaKey = MFA_SESSION_KEY_PREFIX + mfaSessionToken;
            redisTemplate.opsForValue().set(mfaKey, String.valueOf(user.getId()),
                    MFA_SESSION_EXPIRY.getSeconds(), TimeUnit.SECONDS);

            return ApiResponse.of(new MfaRequiredResponse(mfaSessionToken));
        }

        // 8. トークン発行（2FA無効の場合）
        return ApiResponse.of(issueLoginTokens(user, req, ipAddress, userAgent));
    }

    // ========================================
    // ログアウト
    // ========================================

    /**
     * 単一デバイスからのログアウトを行う。
     * Refresh Token失効 + JTIブラックリスト追加。
     *
     * @param refreshTokenHash Refresh TokenのSHA-256ハッシュ
     * @param jti              Access TokenのJTI
     * @param expEpoch         Access Tokenの有効期限（epoch秒）
     */
    @Transactional
    public void logout(String refreshTokenHash, String jti, long expEpoch) {
        // 1. RefreshToken失効
        refreshTokenRepository.findByTokenHash(refreshTokenHash)
                .ifPresent(token -> {
                    token.revoke();
                    Long userId = token.getUserId();

                    // 2. JTIブラックリスト追加（残存TTL）
                    long remainingTtl = expEpoch - (LocalDateTime.now().toEpochSecond(java.time.ZoneOffset.UTC));
                    authTokenService.addJtiToBlacklist(jti, remainingTtl);

                    // 3. イベント発行
                    eventPublisher.publish(new LogoutEvent(userId, 1, LogoutType.SESSION));
                });
    }

    /**
     * 全デバイスからのログアウトを行う。
     * 全RefreshToken失効 + Valkeyにuser_invalidated_at設定。
     *
     * @param userId ユーザーID
     */
    @Transactional
    public void logoutAllDevices(Long userId) {
        // 1. 全RefreshToken失効
        List<RefreshTokenEntity> activeTokens = refreshTokenRepository
                .findByUserIdAndRevokedAtIsNull(userId);
        int deviceCount = activeTokens.size();
        activeTokens.forEach(RefreshTokenEntity::revoke);

        // 2. user_invalidated_at設定（Valkey）
        authTokenService.setUserInvalidationTimestamp(userId);

        // 3. イベント発行
        eventPublisher.publish(new LogoutEvent(userId, deviceCount, LogoutType.ALL_SESSIONS));
    }

    /**
     * 特定デバイスからのログアウトを行う。
     *
     * @param userId         ユーザーID
     * @param refreshTokenId Refresh TokenのID
     */
    @Transactional
    public void logoutDevice(Long userId, Long refreshTokenId) {
        refreshTokenRepository.findById(refreshTokenId)
                .filter(token -> token.getUserId().equals(userId))
                .ifPresent(token -> {
                    token.revoke();
                    eventPublisher.publish(new LogoutEvent(userId, 1, LogoutType.SESSION, refreshTokenId));
                });
    }

    // ========================================
    // セッション
    // ========================================

    /**
     * ユーザーのアクティブセッション一覧を取得する。
     *
     * @param userId ユーザーID
     * @return セッション一覧
     */
    public ApiResponse<List<SessionResponse>> getSessions(Long userId) {
        List<RefreshTokenEntity> activeTokens = refreshTokenRepository
                .findByUserIdAndRevokedAtIsNull(userId);

        List<SessionResponse> sessions = activeTokens.stream()
                .filter(token -> token.getExpiresAt().isAfter(LocalDateTime.now()))
                .map(token -> new SessionResponse(
                        token.getId(),
                        token.getIpAddress(),
                        token.getUserAgent(),
                        false,
                        token.getCreatedAt(),
                        token.getLastUsedAt(),
                        false))
                .toList();

        return ApiResponse.of(sessions);
    }

    /**
     * ユーザーのログイン履歴をカーソルベースで取得する。
     *
     * @param userId ユーザーID
     * @param cursor カーソル（null=先頭から）
     * @param limit  取得件数
     * @return ログイン履歴
     */
    public CursorPagedResponse<LoginHistoryResponse> getLoginHistory(Long userId, String cursor, int limit) {
        // 監査ログからログイン関連イベントを取得
        // NOTE: AuditLogRepositoryにカーソルベースクエリメソッドを追加後に実装を完成させる
        List<LoginHistoryResponse> history = List.of();
        CursorPagedResponse.CursorMeta meta = new CursorPagedResponse.CursorMeta(null, false, limit);
        return CursorPagedResponse.of(history, meta);
    }

    // ========================================
    // Refresh Token ローテーション
    // ========================================

    /**
     * Refresh Tokenを検証し、新しいAccess Token + Refresh Tokenペアを発行する。
     * リプレイ攻撃検出時は全トークンを無効化する。
     *
     * @param rawRefreshToken   平文Refresh Token
     * @param deviceFingerprint デバイスフィンガープリント
     * @return 新しいトークンペア
     */
    @Transactional
    public ApiResponse<TokenResponse> refreshAccessToken(String rawRefreshToken, String deviceFingerprint) {
        // 1. SHA-256ハッシュ化 → DB検索
        String tokenHash = authTokenService.hashToken(rawRefreshToken);
        RefreshTokenEntity existingToken = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new BusinessException(AuthErrorCode.AUTH_007));

        // 2. revoked_at設定済み → リプレイ攻撃の疑い → 全トークン無効化
        if (existingToken.getRevokedAt() != null) {
            log.warn("リプレイ攻撃の疑い検出: userId={}, tokenId={}",
                    existingToken.getUserId(), existingToken.getId());
            logoutAllDevices(existingToken.getUserId());
            throw new BusinessException(AuthErrorCode.AUTH_029);
        }

        // 有効期限チェック
        if (existingToken.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(AuthErrorCode.AUTH_032);
        }

        // 3. デバイスフィンガープリント不一致 → WARNログのみ（ソフトモード）
        if (deviceFingerprint != null && existingToken.getDeviceFingerprint() != null
                && !deviceFingerprint.equals(existingToken.getDeviceFingerprint())) {
            log.warn("デバイスフィンガープリント不一致: userId={}, tokenId={}",
                    existingToken.getUserId(), existingToken.getId());
        }

        // 4. 旧トークンを失効
        existingToken.revoke();

        // 5. 新Access Token + 新Refresh Token発行
        Long userId = existingToken.getUserId();
        if (!userRepository.existsById(userId)) {
            throw new BusinessException(AuthErrorCode.AUTH_007);
        }

        String newAccessToken = authTokenService.issueAccessToken(userId, List.of("MEMBER"));
        String newRawRefreshToken = authTokenService.generateRefreshToken();
        String newTokenHash = authTokenService.hashToken(newRawRefreshToken);

        RefreshTokenEntity newToken = RefreshTokenEntity.builder()
                .userId(userId)
                .tokenHash(newTokenHash)
                .rememberMe(existingToken.getRememberMe())
                .deviceFingerprint(existingToken.getDeviceFingerprint())
                .ipAddress(existingToken.getIpAddress())
                .userAgent(existingToken.getUserAgent())
                .expiresAt(LocalDateTime.now().plusSeconds(authTokenService.getRefreshTokenExpirationSeconds()))
                .build();
        refreshTokenRepository.save(newToken);

        return ApiResponse.of(new TokenResponse(
                newAccessToken, newRawRefreshToken, authTokenService.getAccessTokenExpirationSeconds()));
    }

    // ========================================
    // パスワードリセット
    // ========================================

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
        String rawToken = generateSecureToken();
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
        validatePasswordPolicy(req.getNewPassword());

        // 3. パスワード更新
        UserEntity user = userRepository.findById(resetToken.getUserId())
                .orElseThrow(() -> new BusinessException(AuthErrorCode.AUTH_015));

        // UserEntityにはpasswordHashのsetterがないため、toBuilderで再構築
        UserEntity updatedUser = user.toBuilder()
                .passwordHash(passwordEncoder.encode(req.getNewPassword()))
                .build();
        userRepository.save(updatedUser);

        // トークンを使用済みにする
        resetToken.markUsed();

        // 4. 全RefreshToken失効 + 全デバイス無効化
        logoutAllDevices(user.getId());

        // 5. イベント発行
        eventPublisher.publish(new PasswordResetCompletedEvent(user.getId(), user.getEmail()));

        return ApiResponse.of(new MessageResponse("パスワードが正常に変更されました"));
    }

    // ========================================
    // ヘルパー（private）
    // ========================================

    /**
     * パスワードポリシーを検証する。
     * 8文字以上 + 大文字/小文字/数字/記号のうち3種以上必須。
     */
    private void validatePasswordPolicy(String password) {
        if (password == null || password.length() < PASSWORD_MIN_LENGTH) {
            throw new BusinessException(AuthErrorCode.AUTH_008);
        }

        int typeCount = 0;
        if (UPPERCASE_PATTERN.matcher(password).find()) typeCount++;
        if (LOWERCASE_PATTERN.matcher(password).find()) typeCount++;
        if (DIGIT_PATTERN.matcher(password).find()) typeCount++;
        if (SYMBOL_PATTERN.matcher(password).find()) typeCount++;

        if (typeCount < 3) {
            throw new BusinessException(AuthErrorCode.AUTH_008);
        }
    }

    /**
     * SecureRandom で32バイトを生成し、hex文字列として返す。
     */
    private String generateSecureToken() {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        return java.util.HexFormat.of().formatHex(bytes);
    }

    /**
     * ログイン失敗時の処理。失敗回数をインクリメントし、閾値到達でアカウントロック。
     */
    private void handleLoginFailure(Long userId, String email, String ipAddress, String userAgent) {
        String failCountKey = LOGIN_FAIL_COUNT_KEY_PREFIX + userId;
        Long failCount = redisTemplate.opsForValue().increment(failCountKey);
        if (failCount != null && failCount == 1L) {
            redisTemplate.expire(failCountKey, ACCOUNT_LOCK_DURATION.getSeconds(), TimeUnit.SECONDS);
        }

        // 閾値到達 → アカウントロック
        if (failCount != null && failCount >= ACCOUNT_LOCK_THRESHOLD) {
            String lockKey = ACCOUNT_LOCK_KEY_PREFIX + userId;
            redisTemplate.opsForValue().set(lockKey, "1",
                    ACCOUNT_LOCK_DURATION.getSeconds(), TimeUnit.SECONDS);
            log.warn("アカウントロック発動: userId={}, failCount={}", userId, failCount);
            LocalDateTime unlockAt = LocalDateTime.now().plus(ACCOUNT_LOCK_DURATION);
            eventPublisher.publish(new AccountLockedEvent(userId, "BRUTE_FORCE", unlockAt));
        }

        // ログイン失敗イベント発行
        eventPublisher.publish(new LoginFailedEvent(email, ipAddress, userAgent, "INVALID_PASSWORD"));
    }

    /**
     * ログイン成功時のトークン発行処理。
     * Access Token + Refresh Token を生成し、DBに保存する。
     */
    private LoginResponse issueLoginTokens(UserEntity user, LoginRequest req,
                                           String ipAddress, String userAgent) {
        // Access Token発行
        String accessToken = authTokenService.issueAccessToken(user.getId(), List.of("MEMBER"));

        // Refresh Token発行（DB保存）
        String rawRefreshToken = authTokenService.generateRefreshToken();
        String refreshTokenHash = authTokenService.hashToken(rawRefreshToken);

        RefreshTokenEntity refreshToken = RefreshTokenEntity.builder()
                .userId(user.getId())
                .tokenHash(refreshTokenHash)
                .rememberMe(req.isRememberMe())
                .deviceFingerprint(req.getDeviceFingerprint())
                .ipAddress(ipAddress)
                .userAgent(userAgent)
                .expiresAt(LocalDateTime.now().plusSeconds(authTokenService.getRefreshTokenExpirationSeconds()))
                .build();
        refreshTokenRepository.save(refreshToken);

        // 最終ログイン日時更新
        user.updateLastLoginAt();

        // ログイン成功イベント発行
        eventPublisher.publish(new LoginSuccessEvent(
                user.getId(), user.getEmail(), ipAddress, userAgent));

        // deleted_at設定済みの場合はpendingDeletionUntilを含める
        LocalDateTime pendingDeletionUntil = user.getDeletedAt() != null
                ? user.getDeletedAt().plusDays(30) : null;

        return new LoginResponse(
                accessToken,
                rawRefreshToken,
                authTokenService.getAccessTokenExpirationSeconds(),
                user.getId(),
                user.getDisplayName(),
                user.getEmail(),
                pendingDeletionUntil,
                false);
    }
}
