package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.AuthErrorCode;
import com.mannschaft.app.auth.dto.ConfirmPasswordResetRequest;
import com.mannschaft.app.auth.dto.LoginHistoryResponse;
import com.mannschaft.app.auth.dto.LoginRequest;
import com.mannschaft.app.auth.dto.LoginResponse;
import com.mannschaft.app.auth.dto.MessageResponse;
import com.mannschaft.app.auth.dto.MfaRequiredResponse;
import com.mannschaft.app.auth.dto.RegisterRequest;
import com.mannschaft.app.auth.dto.SessionResponse;
import com.mannschaft.app.auth.dto.TokenResponse;
import com.mannschaft.app.auth.entity.RefreshTokenEntity;
import com.mannschaft.app.auth.entity.TwoFactorAuthEntity;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.event.AccountLockedEvent;
import com.mannschaft.app.auth.event.LoginFailedEvent;
import com.mannschaft.app.auth.event.LoginSuccessEvent;
import com.mannschaft.app.auth.repository.RefreshTokenRepository;
import com.mannschaft.app.auth.repository.TwoFactorAuthRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.util.UserAgentParser;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CursorPagedResponse;
import com.mannschaft.app.common.DomainEventPublisher;
import com.mannschaft.app.common.util.SessionHashUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
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

/**
 * 認証コアサービス（ファサード）。
 * 公開 API はリファクタ前と完全同一。ログイン処理本体と、登録 / セッション / Refresh Token
 * ローテーション / パスワードリセットの各サブサービスへの委譲を担う。
 *
 * <p>セキュリティ重要: 公開メソッドのシグネチャ・呼び出し順序・引数・戻り値・例外型・エラーコード・
 * 監査ログ記録タイミングはリファクタ前から不変。ログイン本体ロジックは byte-identical に保存し、
 * その他のロジックはサブサービスへ移送した（移送先でも byte-identical）。</p>
 *
 * <p>分割構成:</p>
 * <ul>
 *   <li>{@link AuthRegistrationService} — register / verifyEmail / resendVerificationEmail</li>
 *   <li>{@link AuthSessionService} — logout / logoutAllDevices / logoutDevice /
 *       getSessions / updateSessionDeviceName / getLoginHistory / enforceMaxActiveSessions</li>
 *   <li>{@link AuthTokenRotationService} — refreshAccessToken</li>
 *   <li>{@link AuthPasswordResetService} — requestPasswordReset / confirmPasswordReset</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    // ログイン処理に直接必要な依存
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final TwoFactorAuthRepository twoFactorAuthRepository;
    private final AuthTokenService authTokenService;
    private final PasswordEncoder passwordEncoder;
    private final DomainEventPublisher eventPublisher;
    private final StringRedisTemplate redisTemplate;
    private final NewDeviceDetectionService newDeviceDetectionService;
    private final RoleClaimResolver roleClaimResolver;

    // サブサービス（委譲先）
    private final AuthRegistrationService authRegistrationService;
    private final AuthSessionService authSessionService;
    private final AuthTokenRotationService authTokenRotationService;
    private final AuthPasswordResetService authPasswordResetService;

    // ログインに使うレートリミット設定
    private static final int LOGIN_MAX_ATTEMPTS = 10;
    private static final Duration LOGIN_WINDOW = Duration.ofMinutes(1);

    // アカウントロック設定
    private static final int ACCOUNT_LOCK_THRESHOLD = 5;
    private static final Duration ACCOUNT_LOCK_DURATION = Duration.ofMinutes(30);
    private static final String ACCOUNT_LOCK_KEY_PREFIX = "mannschaft:auth:account_lock:";
    private static final String LOGIN_FAIL_COUNT_KEY_PREFIX = "mannschaft:auth:login_fail_count:";
    private static final String MFA_SESSION_KEY_PREFIX = "mannschaft:auth:mfa_session:";

    // トークン有効期限
    private static final Duration MFA_SESSION_EXPIRY = Duration.ofMinutes(5);

    // ========================================
    // 登録（AuthRegistrationService へ委譲）
    // ========================================

    /**
     * ユーザー登録を行う。詳細は {@link AuthRegistrationService#register} を参照。
     */
    @Transactional
    public ApiResponse<MessageResponse> register(RegisterRequest req, String ipAddress) {
        return authRegistrationService.register(req, ipAddress);
    }

    // ========================================
    // メール確認（AuthRegistrationService へ委譲）
    // ========================================

    /**
     * メール認証トークンを検証し、ユーザーを有効化する。
     * 詳細は {@link AuthRegistrationService#verifyEmail} を参照。
     */
    @Transactional
    public ApiResponse<MessageResponse> verifyEmail(String token) {
        return authRegistrationService.verifyEmail(token);
    }

    /**
     * メール認証メールを再送信する。
     * 詳細は {@link AuthRegistrationService#resendVerificationEmail} を参照。
     */
    @Transactional
    public ApiResponse<MessageResponse> resendVerificationEmail(String email) {
        return authRegistrationService.resendVerificationEmail(email);
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
        boolean reactivated = false;
        if (userOpt.isEmpty()) {
            // 退会処理中アカウントの確認（@SQLRestriction バイパス）
            Optional<UserEntity> pendingDeletionOpt = userRepository.findByEmailIncludingDeleted(req.getEmail());
            if (pendingDeletionOpt.isPresent() && pendingDeletionOpt.get().getDeletedAt() != null) {
                UserEntity pendingUser = pendingDeletionOpt.get();
                if (passwordEncoder.matches(req.getPassword(), pendingUser.getPasswordHash())) {
                    // 正しいパスワード → 退会取り消しして通常ログインへ
                    // 旧アルゴリズム（BCrypt）なら Argon2id へ透過的に再ハッシュ（段階移行）
                    upgradePasswordHashIfNeeded(pendingUser, req.getPassword());
                    pendingUser.cancelDeletion();
                    userRepository.save(pendingUser);
                    authTokenService.clearUserInvalidationTimestamp(pendingUser.getId());
                    log.info("ユーザー[{}]が退会処理中にログインしたため退会を自動取り消しました", pendingUser.getId());
                    userOpt = Optional.of(pendingUser);
                    reactivated = true;
                } else {
                    eventPublisher.publish(new LoginFailedEvent(
                            req.getEmail(), ipAddress, userAgent, "PENDING_DELETION_WRONG_PW"));
                    throw new BusinessException(AuthErrorCode.AUTH_009);
                }
            } else {
                // タイミング攻撃対策: ダミーのbcrypt検証を実行して処理時間を合わせる
                passwordEncoder.matches(req.getPassword(), "$2a$12$000000000000000000000uGHJKLMNOPQRSTUVWXYZ012345678901");
                eventPublisher.publish(new LoginFailedEvent(
                        req.getEmail(), ipAddress, userAgent, "USER_NOT_FOUND"));
                throw new BusinessException(AuthErrorCode.AUTH_009);
            }
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

        // 4. アカウントロックチェック（Valkey障害時はfail-openでスキップ）
        String lockKey = ACCOUNT_LOCK_KEY_PREFIX + user.getId();
        try {
            if (Boolean.TRUE.equals(redisTemplate.hasKey(lockKey))) {
                eventPublisher.publish(new LoginFailedEvent(
                        req.getEmail(), ipAddress, userAgent, "ACCOUNT_LOCKED"));
                throw new BusinessException(AuthErrorCode.AUTH_003);
            }
        } catch (BusinessException e) {
            throw e;
        } catch (DataAccessException e) {
            log.warn("アカウントロックチェックをスキップ（Valkey接続失敗）: {}", e.getMessage());
        }

        // 5. パスワード検証
        if (!passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
            // ログイン失敗回数をインクリメント
            handleLoginFailure(user.getId(), req.getEmail(), ipAddress, userAgent);
            throw new BusinessException(AuthErrorCode.AUTH_009);
        }

        // パスワード検証成功 → 失敗カウンタをリセット（Valkey障害時はサイレント）
        String failCountKey = LOGIN_FAIL_COUNT_KEY_PREFIX + user.getId();
        try {
            redisTemplate.delete(failCountKey);
        } catch (DataAccessException e) {
            log.warn("ログイン失敗カウンタリセットをスキップ（Valkey接続失敗）: {}", e.getMessage());
        }

        // パスワードハッシュの段階移行: 旧アルゴリズム（BCrypt）なら Argon2id へ透過的に再ハッシュ。
        // 既存ユーザーはログインのたびに自動で Argon2id へ移行する（強制リセット不要・ユーザー影響なし）。
        upgradePasswordHashIfNeeded(user, req.getPassword());

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
        return ApiResponse.of(issueLoginTokens(user, req, ipAddress, userAgent, reactivated));
    }

    // ========================================
    // ログアウト / セッション（AuthSessionService へ委譲）
    // ========================================

    /**
     * 単一デバイスからのログアウトを行う。
     * 詳細は {@link AuthSessionService#logout} を参照。
     */
    @Transactional
    public void logout(String refreshTokenHash, String jti, long expEpoch) {
        authSessionService.logout(refreshTokenHash, jti, expEpoch);
    }

    /**
     * 全デバイスからのログアウトを行う（後方互換）。
     * 詳細は {@link AuthSessionService#logoutAllDevices(Long)} を参照。
     */
    @Transactional
    public void logoutAllDevices(Long userId) {
        authSessionService.logoutAllDevices(userId);
    }

    /**
     * 全デバイスからのログアウトを行う。
     * 詳細は {@link AuthSessionService#logoutAllDevices(Long, String, Long, boolean)} を参照。
     */
    @Transactional
    public void logoutAllDevices(Long userId, String currentTokenHash, Long currentSessionId, boolean keepCurrent) {
        authSessionService.logoutAllDevices(userId, currentTokenHash, currentSessionId, keepCurrent);
    }

    /**
     * 特定デバイスからのログアウトを行う。
     * 詳細は {@link AuthSessionService#logoutDevice} を参照。
     */
    @Transactional
    public void logoutDevice(Long userId, Long refreshTokenId, String currentTokenHash, Long currentSessionId) {
        authSessionService.logoutDevice(userId, refreshTokenId, currentTokenHash, currentSessionId);
    }

    /**
     * ユーザーのアクティブセッション一覧を取得する。
     * 詳細は {@link AuthSessionService#getSessions} を参照。
     */
    public ApiResponse<List<SessionResponse>> getSessions(Long userId, String currentTokenHash, Long currentSessionId) {
        return authSessionService.getSessions(userId, currentTokenHash, currentSessionId);
    }

    /**
     * セッションのデバイス名を更新する。
     * 詳細は {@link AuthSessionService#updateSessionDeviceName} を参照。
     */
    @Transactional
    public ApiResponse<SessionResponse> updateSessionDeviceName(Long userId, Long sessionId, String newDeviceName) {
        return authSessionService.updateSessionDeviceName(userId, sessionId, newDeviceName);
    }

    /**
     * ユーザーのログイン履歴をカーソルベースで取得する。
     * 詳細は {@link AuthSessionService#getLoginHistory} を参照。
     */
    public CursorPagedResponse<LoginHistoryResponse> getLoginHistory(Long userId, String cursor, int limit, LocalDateTime from, LocalDateTime to) {
        return authSessionService.getLoginHistory(userId, cursor, limit, from, to);
    }

    // ========================================
    // Refresh Token ローテーション（AuthTokenRotationService へ委譲）
    // ========================================

    /**
     * Refresh Tokenを検証し、新しいAccess Token + Refresh Tokenペアを発行する。
     * 詳細は {@link AuthTokenRotationService#refreshAccessToken} を参照。
     */
    @Transactional
    public ApiResponse<TokenResponse> refreshAccessToken(String rawRefreshToken, String deviceFingerprint) {
        return authTokenRotationService.refreshAccessToken(rawRefreshToken, deviceFingerprint);
    }

    // ========================================
    // パスワードリセット（AuthPasswordResetService へ委譲）
    // ========================================

    /**
     * パスワードリセットを要求する。
     * 詳細は {@link AuthPasswordResetService#requestPasswordReset} を参照。
     */
    @Transactional
    public ApiResponse<MessageResponse> requestPasswordReset(String email, String ipAddress) {
        return authPasswordResetService.requestPasswordReset(email, ipAddress);
    }

    /**
     * パスワードリセットを確認・実行する。
     * 詳細は {@link AuthPasswordResetService#confirmPasswordReset} を参照。
     */
    @Transactional
    public ApiResponse<MessageResponse> confirmPasswordReset(ConfirmPasswordResetRequest req) {
        return authPasswordResetService.confirmPasswordReset(req);
    }

    // ========================================
    // ヘルパー（private: ログイン処理用）
    // ========================================

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
     * パスワードハッシュのアルゴリズム段階移行（透過的再ハッシュ）。
     *
     * <p>{@link PasswordEncoder#upgradeEncoding} が true（=保存済みハッシュが旧アルゴリズム、
     * 具体的には {@code {id}} プレフィックスのない生 BCrypt）の場合のみ、
     * 検証済みの平文パスワードを既定アルゴリズム（Argon2id）で再エンコードして保存する。</p>
     *
     * <p>呼び出し条件: <b>パスワード検証に成功した直後のみ</b>。平文 {@code rawPassword} は
     * すでに {@code passwordEncoder.matches} で照合済みのものを渡すこと。これにより
     * 既存ユーザーはログインのたびに透過的に Argon2id へ移行し、強制リセットは不要。</p>
     *
     * <p>{@link #login} は {@code @Transactional} 境界内であり、ここでの save は
     * 既存のアカウントロック / レートリミット / イベント発行ロジックに影響しない。</p>
     *
     * 設計書: docs/security/02_cookie_and_session.md §5 / docs/features/F01.1_auth.md
     *
     * @param user        対象ユーザー（管理対象エンティティ）
     * @param rawPassword 検証済みの平文パスワード
     */
    private void upgradePasswordHashIfNeeded(UserEntity user, String rawPassword) {
        if (passwordEncoder.upgradeEncoding(user.getPasswordHash())) {
            user.updatePasswordHash(passwordEncoder.encode(rawPassword));
            userRepository.save(user);
            log.info("パスワードハッシュを Argon2id へ段階移行しました: userId={}", user.getId());
        }
    }

    /**
     * ログイン成功時のトークン発行処理。
     * Access Token + Refresh Token を生成し、DBに保存する。
     */
    private LoginResponse issueLoginTokens(UserEntity user, LoginRequest req,
                                           String ipAddress, String userAgent, boolean reactivated) {
        // Access Token発行
        // 認可基盤完全根治 Phase 1（§3.2）: 固定 MEMBER ではなく user_roles から SYSTEM_ADMIN を
        // 判定した roles を載せる（RoleClaimResolver に一元化。5発行経路で同一ロジック）。
        String accessToken = authTokenService.issueAccessToken(user.getId(), roleClaimResolver.resolveRoles(user.getId()));

        // Refresh Token発行（DB保存）
        String rawRefreshToken = authTokenService.generateRefreshToken();
        String refreshTokenHash = authTokenService.hashToken(rawRefreshToken);
        // jti を生成して RefreshToken に紐付ける（session_hash の基点）
        String refreshTokenJti = UUID.randomUUID().toString();

        RefreshTokenEntity refreshToken = RefreshTokenEntity.builder()
                .userId(user.getId())
                .tokenHash(refreshTokenHash)
                .jti(refreshTokenJti)
                .rememberMe(req.isRememberMe())
                .deviceFingerprint(req.getDeviceFingerprint())
                .ipAddress(ipAddress)
                .userAgent(userAgent)
                .expiresAt(LocalDateTime.now().plusSeconds(authTokenService.getRefreshTokenExpirationSeconds()))
                .build();
        RefreshTokenEntity savedToken = refreshTokenRepository.save(refreshToken);
        // session_hash を計算（SHA-256(refresh_token_jti)）
        String sessionHash = SessionHashUtil.hash(refreshTokenJti);

        // セッション上限チェック（F12.4 §5.7）
        authSessionService.enforceMaxActiveSessions(user.getId());

        // 新規デバイスログイン検知（F12.4 §5.5、非同期実行）
        String deviceFingerprint = req.getDeviceFingerprint() != null
                ? req.getDeviceFingerprint()
                : authTokenService.hashToken(userAgent != null ? userAgent : "");
        String deviceName = UserAgentParser.parse(userAgent).deviceName();
        String locale = user.getLocale() != null ? user.getLocale() : "ja";
        newDeviceDetectionService.checkAndNotify(user.getId(), ipAddress, deviceFingerprint, deviceName, locale);

        // 最終ログイン日時更新
        user.updateLastLoginAt();

        // ログイン成功イベント発行（sessionHash = SHA-256(refresh_token_jti)）
        eventPublisher.publish(new LoginSuccessEvent(
                user.getId(), user.getEmail(), ipAddress, userAgent, sessionHash));

        // deleted_at設定済みの場合はpendingDeletionUntilを含める
        LocalDateTime pendingDeletionUntil = user.getDeletedAt() != null
                ? user.getDeletedAt().plusDays(30) : null;

        return new LoginResponse(
                accessToken,
                rawRefreshToken,
                authTokenService.getAccessTokenExpirationSeconds(),
                user.getId(),
                user.getLastName() + " " + user.getFirstName(),
                user.getEmail(),
                pendingDeletionUntil,
                reactivated);
    }
}
