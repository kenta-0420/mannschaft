package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.AuthErrorCode;
import com.mannschaft.app.auth.MfaEmailOtpPurpose;
import com.mannschaft.app.auth.MfaVerificationMethod;
import com.mannschaft.app.auth.TwoFactorMethod;
import com.mannschaft.app.auth.event.LoginFailedEvent;
import com.mannschaft.app.auth.event.MfaBackupCodeUsedEvent;
import com.mannschaft.app.auth.event.MfaVerificationFailedEvent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.mannschaft.app.auth.dto.DisableTwoFactorRequest;
import com.mannschaft.app.auth.dto.MfaEmailSendResponse;
import com.mannschaft.app.auth.dto.MfaLoginTokenResponse;
import com.mannschaft.app.auth.dto.RegenerateBackupCodesRequest;
import com.mannschaft.app.auth.dto.TwoFactorStatusResponse;
import com.mannschaft.app.auth.entity.MfaRecoveryTokenEntity;
import com.mannschaft.app.auth.repository.MfaRecoveryTokenRepository;
import com.mannschaft.app.auth.entity.RefreshTokenEntity;
import com.mannschaft.app.auth.repository.RefreshTokenRepository;
import com.mannschaft.app.auth.entity.TwoFactorAuthEntity;
import com.mannschaft.app.auth.repository.TwoFactorAuthRepository;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.dto.BackupCodesResponse;
import com.mannschaft.app.auth.dto.MessageResponse;
import com.mannschaft.app.auth.dto.TokenResponse;
import com.mannschaft.app.auth.dto.TotpSetupResponse;
import com.mannschaft.app.auth.event.LoginSuccessEvent;
import com.mannschaft.app.auth.event.MfaDisabledEvent;
import com.mannschaft.app.auth.event.MfaEnabledEvent;
import com.mannschaft.app.auth.event.MfaRecoveryRequestedEvent;
import com.mannschaft.app.auth.event.MfaBackupCodesRegeneratedEvent;
import com.mannschaft.app.auth.event.MfaRecoveryCompletedEvent;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.DomainEventPublisher;
import com.mannschaft.app.common.EncryptionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.eatthepath.otp.TimeBasedOneTimePasswordGenerator;

import javax.crypto.spec.SecretKeySpec;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 二要素認証（TOTP）サービス。TOTP設定・検証・バックアップコード・MFAリカバリーを担当する。
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class Auth2faService {

    private final TwoFactorAuthRepository twoFactorAuthRepository;
    private final UserRepository userRepository;
    private final MfaRecoveryTokenRepository mfaRecoveryTokenRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final AuthTokenService authTokenService;
    private final PasswordEncoder passwordEncoder;
    private final StringRedisTemplate redisTemplate;
    private final DomainEventPublisher eventPublisher;
    private final EncryptionService encryptionService;
    private final RoleClaimResolver roleClaimResolver;
    private final StatusClaimResolver statusClaimResolver;
    private final MfaSessionService mfaSessionService;
    private final MfaEmailOtpService mfaEmailOtpService;
    private final MfaFailureAuditRecorder mfaFailureAuditRecorder;
    private final MfaEmailTwoFactorService mfaEmailTwoFactorService;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final String TOTP_USED_KEY_PREFIX = "mannschaft:auth:totp_used:";
    /**
     * TOTP 使用済み印の TTL。±1 ウィンドウ検証では 1 つのコードが最大 90 秒間有効でありうるため、
     * 90 秒保持して有効期間の終わりでの再利用を防ぐ（F01.1 §TOTP コード検証フロー / AC-6）。
     */
    private static final Duration TOTP_USED_TTL = Duration.ofSeconds(90);
    private static final String MFA_SESSION_KEY_PREFIX = MfaSessionService.SESSION_KEY_PREFIX;
    private static final String MFA_RECOVERY_RATE_KEY_PREFIX = "mannschaft:auth:mfa_recovery_attempt:";
    /** 2FA 無効化の試行回数（AC-30: 5 回/15 分）。 */
    private static final String MFA_DISABLE_RATE_KEY_PREFIX = "mannschaft:auth:mfa_disable_attempt:";
    /** バックアップコード再生成の試行回数（AC-44: パスワード総当たり対策として無効化と同じ上限）。 */
    private static final String MFA_REGENERATE_RATE_KEY_PREFIX = "mannschaft:auth:mfa_regenerate_attempt:";
    private static final int REAUTH_RATE_LIMIT = 5;
    private static final Duration REAUTH_RATE_WINDOW = Duration.ofMinutes(15);
    private static final int BACKUP_CODE_COUNT = 8;
    private static final int BACKUP_CODE_LENGTH = 8;

    /**
     * TOTP設定を開始する。秘密鍵を生成し、QRコードURLを返す。
     * <p>
     * TOTP秘密鍵はAES-256-GCMで暗号化して保存する。
     * </p>
     *
     * @param userId ユーザーID
     * @return TOTP設定レスポンス（秘密鍵 + QRコードURL）
     */
    @Transactional
    public ApiResponse<TotpSetupResponse> setupTotp(Long userId) {
        UserEntity user = findUserOrThrow(userId);

        // 既に有効な2FAが存在するかチェック
        twoFactorAuthRepository.findByUserId(userId).ifPresent(existing -> {
            if (existing.getIsEnabled()) {
                throw new BusinessException(AuthErrorCode.AUTH_017);
            }
            // 未有効化の設定を削除して再作成
            twoFactorAuthRepository.delete(existing);
        });

        // 1. TOTP秘密鍵生成（Base32エンコード）
        String secret = generateBase32Secret();

        // AES-256-GCM で秘密鍵を暗号化して保存
        String encodedSecret = encryptionService.encrypt(secret);

        // 2. TwoFactorAuthEntity作成（is_enabled=false）
        TwoFactorAuthEntity twoFactorAuth = TwoFactorAuthEntity.builder()
                .userId(userId)
                .totpSecret(encodedSecret)
                .backupCodes("[]")
                .isEnabled(false)
                .build();
        twoFactorAuthRepository.save(twoFactorAuth);

        // 3. QRコードURL生成
        String qrCodeUrl = String.format(
                "otpauth://totp/Mannschaft:%s?secret=%s&issuer=Mannschaft",
                user.getEmail(), secret
        );

        TotpSetupResponse response = new TotpSetupResponse(secret, qrCodeUrl);

        return ApiResponse.of(response);
    }

    /**
     * TOTP設定を検証し、二要素認証を有効化する。
     * <ol>
     *   <li>TOTP検証（±1ウィンドウ）</li>
     *   <li>使用済みコードチェック</li>
     *   <li>2FA有効化</li>
     *   <li>バックアップコード8個生成（bcryptハッシュ保存）</li>
     *   <li>MfaEnabledEvent発行</li>
     * </ol>
     *
     * @param userId   ユーザーID
     * @param totpCode TOTPコード
     * @return メッセージレスポンス（バックアップコードを含む）
     */
    @Transactional
    public ApiResponse<BackupCodesResponse> verifyTotpSetup(Long userId, String totpCode) {
        TwoFactorAuthEntity twoFactorAuth = twoFactorAuthRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(AuthErrorCode.AUTH_016));

        // CMP-261012-0910 AC-1: 有効化済みの再有効化を拒否する（バックアップコードの黙った全差し替え・
        // MfaEnabledEvent の二重発行を防ぐ）。方式切替は「無効化 → 再設定」でのみ行う。
        if (Boolean.TRUE.equals(twoFactorAuth.getIsEnabled())) {
            throw new BusinessException(AuthErrorCode.AUTH_078);
        }
        // TOTP の設定開始（setupTotp）を経ていない行（秘密鍵なし）は確定できない
        if (twoFactorAuth.getTotpSecret() == null) {
            throw new BusinessException(AuthErrorCode.AUTH_016);
        }

        // AES-256-GCM で秘密鍵を復号化
        String secret = encryptionService.decrypt(twoFactorAuth.getTotpSecret());

        // 1. TOTP検証
        if (!verifyTotpCode(secret, totpCode)) {
            throw new BusinessException(AuthErrorCode.AUTH_018);
        }

        // 2. 使用済み印を原子的に取る（取れなければ使用済み。AC-6）
        if (!markTotpUsed(userId, totpCode)) {
            throw new BusinessException(AuthErrorCode.AUTH_018);
        }

        // 3. 2FA有効化
        twoFactorAuth.enable();
        twoFactorAuthRepository.save(twoFactorAuth);

        // 4. バックアップコード生成
        List<String> plainCodes = generateBackupCodes();
        List<String> hashedCodes = plainCodes.stream()
                .map(passwordEncoder::encode)
                .collect(Collectors.toList());
        twoFactorAuth = twoFactorAuth.toBuilder()
                .backupCodes(toJson(hashedCodes))
                .build();
        twoFactorAuthRepository.save(twoFactorAuth);

        // CMP-261012-0910 AC-48: 再設定前に発行された回復トークンで新しい 2FA を外させない
        revokeUnusedRecoveryTokens(userId);

        // 5. イベント発行
        eventPublisher.publish(new MfaEnabledEvent(userId));

        // 6. 平文バックアップコードを返却
        return ApiResponse.of(new BackupCodesResponse(plainCodes));
    }

    /**
     * バックアップコードを再生成する。
     *
     * @param userId ユーザーID
     * @return 新しいバックアップコードレスポンス
     */
    @Transactional
    public ApiResponse<BackupCodesResponse> regenerateBackupCodes(Long userId) {
        TwoFactorAuthEntity twoFactorAuth = twoFactorAuthRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(AuthErrorCode.AUTH_016));

        if (!twoFactorAuth.getIsEnabled()) {
            throw new BusinessException(AuthErrorCode.AUTH_020);
        }

        // 新しい8個生成
        List<String> plainCodes = generateBackupCodes();
        List<String> hashedCodes = plainCodes.stream()
                .map(passwordEncoder::encode)
                .collect(Collectors.toList());

        TwoFactorAuthEntity updated = twoFactorAuth.toBuilder()
                .backupCodes(toJson(hashedCodes))
                .build();
        twoFactorAuthRepository.save(updated);

        return ApiResponse.of(new BackupCodesResponse(plainCodes));
    }

    /**
     * MFAリカバリーをリクエストする。リカバリーメールが送信される。
     *
     * @param mfaSessionToken MFAセッショントークン
     * @return メッセージレスポンス
     */
    @Transactional
    public ApiResponse<MessageResponse> requestMfaRecovery(String mfaSessionToken) {
        // MFAセッショントークンからuserIdを取得（削除はしない。リカバリーフローで再利用可能にする）
        if (mfaSessionToken == null || mfaSessionToken.isBlank()) {
            throw new BusinessException(AuthErrorCode.AUTH_019);
        }
        String sessionHash = authTokenService.hashToken(mfaSessionToken);
        String sessionKey = MFA_SESSION_KEY_PREFIX + sessionHash;
        String userIdStr = redisTemplate.opsForValue().get(sessionKey);
        if (userIdStr == null) {
            throw new BusinessException(AuthErrorCode.AUTH_019);
        }

        Long userId = Long.valueOf(userIdStr);

        // 1. レートリミット（24h/3回）
        authTokenService.checkRateLimit(
                MFA_RECOVERY_RATE_KEY_PREFIX + userId,
                3,
                Duration.ofHours(24)
        );

        UserEntity user = findUserOrThrow(userId);

        // CMP-261012-0910 AC-48: 再要求したら旧トークンは失効させ、最新の 1 通だけを有効にする
        revokeUnusedRecoveryTokens(userId);

        // 2. MfaRecoveryToken生成（1h有効）
        String rawToken = UUID.randomUUID().toString();
        String tokenHash = authTokenService.hashToken(rawToken);

        MfaRecoveryTokenEntity recoveryToken = MfaRecoveryTokenEntity.builder()
                .userId(userId)
                .tokenHash(tokenHash)
                .expiresAt(LocalDateTime.now().plusHours(1))
                .build();
        mfaRecoveryTokenRepository.save(recoveryToken);

        // 3. イベント発行（メール送信用）
        eventPublisher.publish(new MfaRecoveryRequestedEvent(userId, user.getEmail(), rawToken));

        return ApiResponse.of(MessageResponse.of("リカバリーメールを送信しました"));
    }

    /**
     * MFAリカバリーを確認し、2FAを無効化してトークンを発行する。
     *
     * @param token MFAリカバリートークン（平文）
     * @return トークンレスポンス
     */
    @Transactional
    public ApiResponse<TokenResponse> confirmMfaRecovery(String token) {
        if (token == null || token.isBlank()) {
            throw new BusinessException(AuthErrorCode.AUTH_023);
        }
        String tokenHash = authTokenService.hashToken(token);

        // 1. トークン検証（CMP-261012-0910 AC-48: 行ロックで同一トークンの並列確認を直列化し、成功を 1 件に限る）
        MfaRecoveryTokenEntity recoveryToken = mfaRecoveryTokenRepository.findByTokenHashForUpdate(tokenHash)
                .orElseThrow(() -> new BusinessException(AuthErrorCode.AUTH_023));

        if (recoveryToken.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(AuthErrorCode.AUTH_023);
        }

        if (recoveryToken.getUsedAt() != null) {
            throw new BusinessException(AuthErrorCode.AUTH_023);
        }

        recoveryToken.markUsed();
        mfaRecoveryTokenRepository.save(recoveryToken);

        Long userId = recoveryToken.getUserId();

        // 2. 2FAを無効化（CMP-261012-0910 AC-52: 有効フラグだけ倒すと秘密鍵・バックアップコードが残るため行ごと消す）。
        // 2FA が既に無い（無効化済み等）なら、このトークンでは何も外せない＝無効トークンと同じ扱い（AC-48）
        TwoFactorAuthEntity twoFactorAuth = twoFactorAuthRepository.findByUserIdForUpdate(userId)
                .filter(tfa -> Boolean.TRUE.equals(tfa.getIsEnabled()))
                .orElseThrow(() -> new BusinessException(AuthErrorCode.AUTH_023));
        TwoFactorMethod disabledMethod = twoFactorAuth.getMethod();
        removeTwoFactor(twoFactorAuth);

        // 3. 既存セッションを全て無効化（MFA無効化後の不正ログイン防止）
        // パスワードが漏洩していた場合、MFA無効化後に攻撃者が即ログインできてしまうため、
        // 全 Refresh Token を失効させ JWT の user_invalidated_at を更新する。
        List<RefreshTokenEntity> activeTokens = refreshTokenRepository.findByUserIdAndRevokedAtIsNull(userId);
        activeTokens.forEach(RefreshTokenEntity::revoke);
        refreshTokenRepository.saveAll(activeTokens);
        authTokenService.setUserInvalidationTimestamp(userId);

        // 4. Access Token + Refresh Token発行
        TokenResponse tokenResponse = issueTokenPair(userId);

        // 5. イベント発行（AC-52/AC-35/AC-37: 監査 MFA_DISABLED(reason=RECOVERY)・回復完了監査・本人宛通知）
        eventPublisher.publish(new MfaDisabledEvent(userId, disabledMethod, MfaDisabledEvent.REASON_RECOVERY));
        eventPublisher.publish(new MfaRecoveryCompletedEvent(userId));

        return ApiResponse.of(tokenResponse);
    }

    /**
     * ユーザーが二要素認証（TOTP）を有効化済みかを返す。
     *
     * <p>他ドメイン（role 等）が ADMIN 昇格前の 2FA 必須チェックに用いるための公開クエリ。
     * {@code auth.repository} を他ドメインから直接参照させない（D-3 ArchUnit・越境 Repository 依存禁止）
     * ため、Service 経由でプリミティブ（boolean）のみを返し Entity を漏らさない。</p>
     *
     * @param userId ユーザー ID
     * @return 2FA レコードが存在し {@code is_enabled = true} の場合のみ true（未設定・未有効化は false）
     */
    public boolean isTwoFactorEnabled(Long userId) {
        return twoFactorAuthRepository.findByUserId(userId)
                .map(TwoFactorAuthEntity::getIsEnabled)
                .orElse(false);
    }

    // =====================================================================
    // CMP-261012-0910 多要素認証(2FA)の穴埋め — API 表面
    // =====================================================================

    /**
     * 2FA ログイン検証（TOTP / バックアップコード / メールOTP。CMP-261012-0910 AC-6〜17・AC-46・AC-49）。
     *
     * <ol>
     *   <li>MFA セッション解決（不在・期限切れは同一の AUTH_073）</li>
     *   <li>ユーザーロック確認（ロック前に発行済みのセッションも全方式で AUTH_003）</li>
     *   <li>照合<b>前</b>に試行を原子 INCR（全手段合計 5 回）</li>
     *   <li>2FA 行を PESSIMISTIC_WRITE で取得し要素ごとに照合（バックアップコードは一致した 1 件を削除）</li>
     *   <li>失敗: 監査 MFA_FAILED を独立 TX で記録 → ユーザー単位の失敗計上（10 回/30 分でロック）→
     *       AUTH_074（5 回目は AUTH_075・セッション削除）</li>
     *   <li>成功: セッションキー DEL の戻り値で勝者を 1 人に決め（敗者は AUTH_073 で TX ロールバック）、トークン発行</li>
     * </ol>
     *
     * <p>D-3T 番人: トークン発行（RoleClaimResolver → role.UserRoleRepository）を含むため本メソッドは
     * 他ドメイン Repository に到達する TX 入口である。旧 {@code validateTotp} の凍結行を本メソッドへ置き換えた
     * （負債の件数は増やさない入れ替え）。</p>
     *
     * @param mfaSessionToken MFA セッショントークン
     * @param method          提示要素（null 不可。DTO 側で欠落→TOTP に解決済み）
     * @param code            コード（形式は DTO で検証済み。Service 直呼びでも不正形式は不一致として扱う）
     * @param ipAddress       リクエスト元 IP（LoginSuccessEvent・監査用）
     * @param userAgent       User-Agent（LoginSuccessEvent・監査用）
     * @return トークン＋使用要素＋バックアップコード残数
     */
    @Transactional
    public ApiResponse<MfaLoginTokenResponse> validateMfaLogin(
            String mfaSessionToken, MfaVerificationMethod method, String code,
            String ipAddress, String userAgent) {
        MfaSessionService.MfaSession session = mfaSessionService.resolve(mfaSessionToken);
        Long userId = session.userId();
        mfaSessionService.assertNotLocked(userId);
        long attempt = mfaSessionService.beginAttempt(session);

        // 行ロックをこの TX の最初の読取りで取る（先に通常読取りすると一次キャッシュの古い写しが返る）
        TwoFactorAuthEntity twoFactorAuth = twoFactorAuthRepository.findByUserIdForUpdate(userId)
                .filter(tfa -> Boolean.TRUE.equals(tfa.getIsEnabled()))
                .orElse(null);
        if (twoFactorAuth == null) {
            // ログイン後に 2FA が無効化された等。このセッションでは完了できない
            mfaSessionService.terminate(session);
            throw new BusinessException(AuthErrorCode.AUTH_073);
        }

        Integer backupCodesRemaining = null;
        boolean verified;
        switch (method) {
            case TOTP -> verified = verifyLoginTotp(twoFactorAuth, userId, code);
            case BACKUP_CODE -> {
                backupCodesRemaining = consumeBackupCode(twoFactorAuth, code);
                verified = backupCodesRemaining != null;
            }
            case EMAIL -> verified = mfaEmailOtpService.verifyAndConsume(
                    MfaEmailOtpPurpose.LOGIN, session.sessionHash(), code);
            default -> throw new IllegalStateException("未知の 2FA 要素: " + method);
        }

        if (!verified) {
            throw onVerificationFailure(session, attempt, method, ipAddress, userAgent);
        }

        // 勝者を 1 人に決める。敗者は AUTH_073 で TX ごとロールバック（バックアップコードの削除も戻る）
        if (!mfaSessionService.consume(session)) {
            throw new BusinessException(AuthErrorCode.AUTH_073);
        }
        mfaSessionService.resetUserFailures(userId);

        TokenResponse tokenResponse = issueTokenPair(userId);
        eventPublisher.publish(new LoginSuccessEvent(userId, ipAddress, userAgent, method.name()));
        if (backupCodesRemaining != null) {
            eventPublisher.publish(new MfaBackupCodeUsedEvent(userId, backupCodesRemaining, ipAddress, userAgent));
        }
        return ApiResponse.of(new MfaLoginTokenResponse(tokenResponse, method, backupCodesRemaining));
    }

    /**
     * ログイン時のメールOTP 送信（公開 EP。MFA セッションに紐付ける。AC-18〜21・AC-46）。
     *
     * <p>IP レート制限はセッション照合より<b>前</b>に掛ける（不正トークンでの総当たりも IP 上限で止める。AC-18）。
     * ロック中のユーザーはロック前に発行済みのセッションでも送信させない（AC-46）。
     * 送信本体（再送待ち・送信上限・enqueue・セッション延長）は {@link MfaEmailOtpService#send} が担う。</p>
     *
     * @param mfaSessionToken MFA セッショントークン（本文で受ける）
     * @param ipAddress       リクエスト元 IP（IP レート制限用）
     * @return マスク済みアドレス・有効期限・再送待ち秒数
     */
    @Transactional
    public ApiResponse<MfaEmailSendResponse> sendLoginEmailOtp(
            String mfaSessionToken, String ipAddress) {
        authTokenService.checkRateLimit(MfaEmailOtpKeys.ipSendRateKey(ipAddress),
                MfaEmailOtpKeys.IP_SEND_LIMIT, Duration.ofSeconds(MfaEmailOtpKeys.IP_SEND_WINDOW_SECONDS));
        MfaSessionService.MfaSession session = mfaSessionService.resolve(mfaSessionToken);
        mfaSessionService.assertNotLocked(session.userId());
        return ApiResponse.of(mfaEmailOtpService.send(
                MfaEmailOtpPurpose.LOGIN, session.sessionHash(), session.userId(), ipAddress));
    }

    /**
     * メール 2FA 設定開始（設定用メールOTP を送信。AC-25）。
     *
     * @param userId 認証済みユーザー ID
     * @return マスク済みアドレス・有効期限・再送待ち秒数
     */
    @Transactional
    public ApiResponse<MfaEmailSendResponse> startEmailSetup(Long userId) {
        return ApiResponse.of(mfaEmailTwoFactorService.startSetup(userId));
    }

    /**
     * メール 2FA 有効化（設定用メールOTP を照合し method=EMAIL で有効化。AC-25/AC-45）。
     *
     * @param userId 認証済みユーザー ID
     * @param code   設定用メールOTP
     * @return 平文バックアップコード 8 件
     */
    @Transactional
    public ApiResponse<BackupCodesResponse> enableEmailTwoFactor(Long userId, String code) {
        return ApiResponse.of(mfaEmailTwoFactorService.enable(userId, code));
    }

    /**
     * 無効化・再生成用の本人確認メールOTP 送信（認証済み・本人固定・用途別名前空間。AC-51）。
     *
     * @param userId 認証済みユーザー ID
     * @return マスク済みアドレス・有効期限・再送待ち秒数
     */
    @Transactional
    public ApiResponse<MfaEmailSendResponse> sendReauthEmailOtp(Long userId) {
        return ApiResponse.of(mfaEmailTwoFactorService.sendReauth(userId));
    }

    /**
     * ログイン中の 2FA 無効化（AC-30〜33）。
     *
     * @param userId 認証済みユーザー ID
     * @param req    本人確認（パスワード＋要素）
     * @return メッセージ
     */
    @Transactional
    public ApiResponse<MessageResponse> disableTwoFactor(
            Long userId, DisableTwoFactorRequest req) {
        // 5 回/15 分（成功・失敗を問わず計上。Valkey のため失敗 TX のロールバックでも戻らない）
        authTokenService.checkRateLimit(MFA_DISABLE_RATE_KEY_PREFIX + userId,
                REAUTH_RATE_LIMIT, REAUTH_RATE_WINDOW);
        TwoFactorAuthEntity twoFactorAuth = findEnabledForUpdateOrThrow(userId);
        UserEntity user = findUserOrThrow(userId);

        // 盗難アクセストークン対策: パスワード保持者はパスワード＋有効要素の両方（AC-30）
        if (hasPassword(user) && !passwordMatches(user, req.getCurrentPassword())) {
            throw new BusinessException(AuthErrorCode.AUTH_080);
        }
        if (!verifyReauthFactor(twoFactorAuth, userId, req.getMethod(), req.getCode())) {
            throw new BusinessException(AuthErrorCode.AUTH_080);
        }

        TwoFactorMethod disabledMethod = twoFactorAuth.getMethod();
        removeTwoFactor(twoFactorAuth);
        // セッションは維持する（裁可事項 3 / AC-33）。リフレッシュトークン失効・user_invalidated_at 更新はしない
        eventPublisher.publish(new MfaDisabledEvent(userId, disabledMethod, MfaDisabledEvent.REASON_USER));
        return ApiResponse.of(MessageResponse.of("2段階認証を無効にしました"));
    }

    /**
     * 2FA 設定状態の取得（本人のみ。秘密鍵・ハッシュは返さない。AC-4）。
     *
     * @param userId 認証済みユーザー ID
     * @return 有効状態・方式・バックアップコード残数
     */
    public ApiResponse<TwoFactorStatusResponse> getTwoFactorStatus(Long userId) {
        TwoFactorStatusResponse status = twoFactorAuthRepository.findByUserId(userId)
                .filter(tfa -> Boolean.TRUE.equals(tfa.getIsEnabled()))
                .map(tfa -> new TwoFactorStatusResponse(
                        true,
                        tfa.getMethod() == null ? TwoFactorMethod.TOTP : tfa.getMethod(),
                        readBackupCodeHashes(tfa).size()))
                .orElseGet(() -> new TwoFactorStatusResponse(false, null, 0));
        return ApiResponse.of(status);
    }

    /**
     * バックアップコード再生成（本人確認付き。AC-36/AC-44）。
     *
     * <p>本人確認は「現在のパスワード」または「有効な要素（TOTP / 本人確認用メールOTP / バックアップコード）」
     * （裁可事項 8）。提示されたものはすべて正しくなければならず、何も提示されなければ拒否する。
     * 失敗時はコードを 1 文字も変えず（TX ロールバック）、成功時のみ監査イベントを発行する（AC-36）。</p>
     *
     * @param userId 認証済みユーザー ID
     * @param req    本人確認（null は本人確認なし＝拒否）
     * @return 平文バックアップコード 8 件
     */
    @Transactional
    public ApiResponse<BackupCodesResponse> regenerateBackupCodes(
            Long userId, RegenerateBackupCodesRequest req) {
        authTokenService.checkRateLimit(MFA_REGENERATE_RATE_KEY_PREFIX + userId,
                REAUTH_RATE_LIMIT, REAUTH_RATE_WINDOW);
        TwoFactorAuthEntity twoFactorAuth = findEnabledForUpdateOrThrow(userId);
        UserEntity user = findUserOrThrow(userId);

        boolean passwordPresented = req != null && req.getCurrentPassword() != null
                && !req.getCurrentPassword().isEmpty();
        boolean factorPresented = req != null && (req.getMethod() != null || req.getCode() != null);
        if (!passwordPresented && !factorPresented) {
            throw new BusinessException(AuthErrorCode.AUTH_080);
        }
        if (passwordPresented && !(hasPassword(user) && passwordMatches(user, req.getCurrentPassword()))) {
            throw new BusinessException(AuthErrorCode.AUTH_080);
        }
        if (factorPresented && !verifyReauthFactor(twoFactorAuth, userId, req.getMethod(), req.getCode())) {
            throw new BusinessException(AuthErrorCode.AUTH_080);
        }

        // 行ロック済みの写しに新しい 8 件を書く（旧コードは全失効）
        List<String> plainCodes = generateBackupCodes();
        twoFactorAuth.replaceBackupCodes(toJson(plainCodes.stream()
                .map(passwordEncoder::encode)
                .collect(Collectors.toList())));
        twoFactorAuthRepository.save(twoFactorAuth);
        eventPublisher.publish(new MfaBackupCodesRegeneratedEvent(userId));
        return ApiResponse.of(new BackupCodesResponse(plainCodes));
    }

    /**
     * オーナー移譲（ADMIN 昇格）の 2FA 要件を満たす「強い 2FA」が有効か（AC-47。EMAIL 方式は不可）。
     *
     * @param userId ユーザー ID
     * @return TOTP 方式の 2FA が有効、または WebAuthn 登録済みなら true
     */
    public boolean isStrongTwoFactorEnabled(Long userId) {
        return twoFactorAuthRepository.countStrongSecondFactor(userId) > 0;
    }

    // === 無効化・再生成・回復の部品（CMP-261012-0910 丙） ===

    /** 2FA が有効な行を行ロック付きで取る。無い・未有効は AUTH_079（409）。 */
    private TwoFactorAuthEntity findEnabledForUpdateOrThrow(Long userId) {
        return twoFactorAuthRepository.findByUserIdForUpdate(userId)
                .filter(tfa -> Boolean.TRUE.equals(tfa.getIsEnabled()))
                .orElseThrow(() -> new BusinessException(AuthErrorCode.AUTH_079));
    }

    /** パスワードを持つユーザーか（OAuth 専用等はパスワード未設定）。 */
    private static boolean hasPassword(UserEntity user) {
        return user.getPasswordHash() != null && !user.getPasswordHash().isBlank();
    }

    /** 提示パスワードが現在のパスワードと一致するか（未提示は不一致）。 */
    private boolean passwordMatches(UserEntity user, String rawPassword) {
        if (rawPassword == null || rawPassword.isEmpty() || !hasPassword(user)) {
            return false;
        }
        return passwordEncoder.matches(rawPassword, user.getPasswordHash());
    }

    /**
     * 本人確認の要素を照合する（無効化・再生成用）。TOTP は使用済み印を取り、バックアップコードは一致した 1 件を消費し、
     * メールOTP は本人確認用（REAUTH・本人固定）のコードを消費する。未提示・不正形式は不一致。
     * 呼び出し側が {@link TwoFactorAuthRepository#findByUserIdForUpdate} で行ロック済みであること。
     */
    private boolean verifyReauthFactor(TwoFactorAuthEntity twoFactorAuth, Long userId,
                                       MfaVerificationMethod method, String code) {
        if (method == null || code == null) {
            return false;
        }
        return switch (method) {
            case TOTP -> verifyLoginTotp(twoFactorAuth, userId, code);
            case BACKUP_CODE -> consumeBackupCode(twoFactorAuth, code) != null;
            case EMAIL -> mfaEmailOtpService.verifyAndConsume(
                    MfaEmailOtpPurpose.REAUTH, MfaEmailOtpKeys.subjectOf(userId), code);
        };
    }

    /**
     * 2FA を完全に外す（無効化・回復の共通処理。AC-31/AC-52）。行を削除して秘密鍵・バックアップコードを残さず、
     * 発行済みのメールOTP（本人確認用・設定用）と未使用の回復トークンも失効させる（AC-48）。
     */
    private void removeTwoFactor(TwoFactorAuthEntity twoFactorAuth) {
        Long userId = twoFactorAuth.getUserId();
        twoFactorAuthRepository.delete(twoFactorAuth);
        twoFactorAuthRepository.flush();
        String subject = MfaEmailOtpKeys.subjectOf(userId);
        mfaEmailOtpService.invalidate(MfaEmailOtpPurpose.REAUTH, subject);
        mfaEmailOtpService.invalidate(MfaEmailOtpPurpose.SETUP, subject);
        revokeUnusedRecoveryTokens(userId);
    }

    /**
     * 未使用の回復トークンをすべて使用済みにする（再要求・無効化・再設定時。裁定: 旧回復トークンで 2FA を外させない）。
     */
    private void revokeUnusedRecoveryTokens(Long userId) {
        List<MfaRecoveryTokenEntity> unused = mfaRecoveryTokenRepository.findByUserIdAndUsedAtIsNull(userId);
        if (unused.isEmpty()) {
            return;
        }
        unused.forEach(MfaRecoveryTokenEntity::markUsed);
        mfaRecoveryTokenRepository.saveAll(unused);
    }

    // === 2FA ログイン検証の部品（CMP-261012-0910） ===

    /**
     * ログイン時の TOTP 照合。EMAIL 方式（秘密鍵なし）は不一致として扱う（NPE にしない）。
     * 一致したら使用済み印を原子的に取り、取れなければ（同一コードの再利用・並列）不一致とする。
     */
    private boolean verifyLoginTotp(TwoFactorAuthEntity twoFactorAuth, Long userId, String code) {
        if (twoFactorAuth.getTotpSecret() == null) {
            return false;
        }
        String secret = encryptionService.decrypt(twoFactorAuth.getTotpSecret());
        if (!verifyTotpCode(secret, code)) {
            return false;
        }
        return markTotpUsed(userId, code);
    }

    /**
     * TOTP 使用済み印を {@code SET NX} で原子的に取る（AC-6。旧実装の hasKey → set は並列で両者が通った）。
     *
     * @return 印を取れた（未使用だった）なら true
     */
    private boolean markTotpUsed(Long userId, String code) {
        return Boolean.TRUE.equals(redisTemplate.opsForValue()
                .setIfAbsent(TOTP_USED_KEY_PREFIX + userId + ":" + code, "1", TOTP_USED_TTL));
    }

    /**
     * バックアップコードを照合し、一致した 1 件を削除する（呼び出し側が PESSIMISTIC_WRITE で行を取得済みであること。AC-7〜11）。
     * 照合は一致した時点で打ち切る（最大 8 回のパスワードハッシュ照合。AC-54）。
     *
     * @return 一致した場合は削除後の残り件数、不一致・形式違反・全件使用済みは null
     */
    private Integer consumeBackupCode(TwoFactorAuthEntity twoFactorAuth, String code) {
        if (code == null || !code.matches("\\d{" + BACKUP_CODE_LENGTH + "}")) {
            return null;
        }
        List<String> hashes = new ArrayList<>(readBackupCodeHashes(twoFactorAuth));
        for (int i = 0; i < hashes.size(); i++) {
            if (passwordEncoder.matches(code, hashes.get(i))) {
                hashes.remove(i);
                twoFactorAuth.replaceBackupCodes(toJson(hashes));
                return hashes.size();
            }
        }
        return null;
    }

    /** 保存済みバックアップコード（ハッシュ）を読む。 */
    private List<String> readBackupCodeHashes(TwoFactorAuthEntity twoFactorAuth) {
        String json = twoFactorAuth.getBackupCodes();
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return JSON.readValue(json, STRING_LIST);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("バックアップコードの JSON 解析に失敗しました", e);
        }
    }

    /**
     * 照合失敗の後始末。監査を独立 TX で残し（AC-49）、試行・ユーザー単位の失敗を計上して（AC-14/16）、
     * 応答の例外を返す（呼び出し側が throw する。業務 TX はロールバックされる）。
     */
    private BusinessException onVerificationFailure(MfaSessionService.MfaSession session, long attempt,
                                                    MfaVerificationMethod method,
                                                    String ipAddress, String userAgent) {
        Long userId = session.userId();
        mfaFailureAuditRecorder.record(
                new MfaVerificationFailedEvent(userId, method, ipAddress, userAgent, attempt));
        MfaSessionService.FailureOutcome outcome = mfaSessionService.recordFailure(session, attempt);
        if (outcome.lockedNow()) {
            String email = userRepository.findById(userId).map(UserEntity::getEmail).orElse(null);
            // 注: LoginFailedEvent / AccountLockedEvent の監査・通知リスナーは AFTER_COMMIT のため、
            // 失敗 TX のロールバックで発火しない（既存のパスワード失敗ロックと同根の既知欠陥。MFA_FAILED 監査は
            // MfaFailureAuditRecorder が独立 TX で残す）。
            eventPublisher.publish(new LoginFailedEvent(email, ipAddress, userAgent, "MFA_FAILED"));
        }
        return new BusinessException(outcome.sessionExhausted() ? AuthErrorCode.AUTH_075 : AuthErrorCode.AUTH_074);
    }

    // === ヘルパーメソッド ===

    /**
     * TOTPコードを検証する。
     * <p>
     * TOTPライブラリ（例: com.eatthepath:java-otp）統合時に正式実装予定。
     * 現時点では±1ウィンドウ（30秒刻み）の検証ロジックのダミー実装。
     * </p>
     *
     * @param secret 秘密鍵（Base32エンコード済み）
     * @param code   検証するTOTPコード
     * @return 検証成功の場合true
     */
    boolean verifyTotpCode(String secret, String code) {
        if (code == null || !code.matches("\\d{6}")) {
            return false;
        }
        try {
            TimeBasedOneTimePasswordGenerator totp = new TimeBasedOneTimePasswordGenerator(
                    Duration.ofSeconds(30), 6);
            Key key = new SecretKeySpec(base32Decode(secret), totp.getAlgorithm());
            Instant now = Instant.now();

            // ±1ウィンドウ（前30秒〜後30秒）で検証
            for (int window = -1; window <= 1; window++) {
                Instant timestamp = now.plusSeconds(window * 30L);
                String expected = String.format("%06d", totp.generateOneTimePassword(key, timestamp));
                if (expected.equals(code)) {
                    return true;
                }
            }
            return false;
        } catch (InvalidKeyException e) {
            log.error("TOTP検証でキーエラー", e);
            return false;
        }
    }

    /**
     * バックアップコード8個を生成する。
     *
     * @return 平文バックアップコードのリスト
     */
    List<String> generateBackupCodes() {
        List<String> codes = new ArrayList<>(BACKUP_CODE_COUNT);
        for (int i = 0; i < BACKUP_CODE_COUNT; i++) {
            StringBuilder code = new StringBuilder(BACKUP_CODE_LENGTH);
            for (int j = 0; j < BACKUP_CODE_LENGTH; j++) {
                code.append(SECURE_RANDOM.nextInt(10));
            }
            codes.add(code.toString());
        }
        return codes;
    }

    /**
     * Base32エンコードされたTOTP秘密鍵を生成する。
     */
    private String generateBase32Secret() {
        byte[] bytes = new byte[20];
        SECURE_RANDOM.nextBytes(bytes);
        // Base32エンコード（RFC 4648）
        return base32Encode(bytes);
    }

    /**
     * Base32エンコード。
     */
    private String base32Encode(byte[] data) {
        String base32Chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        StringBuilder result = new StringBuilder();
        int buffer = 0;
        int bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                result.append(base32Chars.charAt((buffer >> (bitsLeft - 5)) & 0x1F));
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) {
            result.append(base32Chars.charAt((buffer << (5 - bitsLeft)) & 0x1F));
        }
        return result.toString();
    }

    /**
     * Base32デコード。
     */
    private byte[] base32Decode(String encoded) {
        String base32Chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        encoded = encoded.toUpperCase().replaceAll("[=\\s]", "");
        int buffer = 0;
        int bitsLeft = 0;
        byte[] result = new byte[encoded.length() * 5 / 8];
        int index = 0;
        for (char c : encoded.toCharArray()) {
            int val = base32Chars.indexOf(c);
            if (val < 0) {
                throw new IllegalArgumentException("Invalid Base32 character: " + c);
            }
            buffer = (buffer << 5) | val;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                result[index++] = (byte) (buffer >> (bitsLeft - 8));
                bitsLeft -= 8;
            }
        }
        return result;
    }

    /**
     * ユーザーを取得する。
     */
    private UserEntity findUserOrThrow(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(AuthErrorCode.AUTH_015));
    }

    /**
     * Access Token + Refresh Token のペアを発行する。
     */
    private TokenResponse issueTokenPair(Long userId) {
        // 認可基盤完全根治 Phase 1（§3.2）: RoleClaimResolver で SYSTEM_ADMIN を判定して roles に載せる。
        String accessToken = authTokenService.issueAccessToken(userId, roleClaimResolver.resolveRoles(userId),
                statusClaimResolver.isPendingParentalConsent(userId));
        String refreshToken = authTokenService.generateRefreshToken();
        String refreshTokenHash = authTokenService.hashToken(refreshToken);
        String refreshTokenJti = UUID.randomUUID().toString();

        // Refresh Token を保存
        RefreshTokenEntity refreshTokenEntity = RefreshTokenEntity.builder()
                .userId(userId)
                .tokenHash(refreshTokenHash)
                .jti(refreshTokenJti)
                .rememberMe(false)
                .expiresAt(LocalDateTime.now().plusDays(7))
                .build();
        RefreshTokenEntity saved = refreshTokenRepository.save(refreshTokenEntity);

        return new TokenResponse(accessToken, refreshToken, saved.getId(), 3600);
    }

    /**
     * リストをJSON文字列に変換する。
     */
    private String toJson(List<String> list) {
        try {
            return new ObjectMapper().writeValueAsString(list);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("バックアップコードのJSON変換に失敗しました", e);
        }
    }
}
