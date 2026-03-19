package com.mannschaft.app.auth;

import com.mannschaft.app.auth.dto.BackupCodesResponse;
import com.mannschaft.app.auth.dto.MessageResponse;
import com.mannschaft.app.auth.dto.TokenResponse;
import com.mannschaft.app.auth.dto.TotpSetupResponse;
import com.mannschaft.app.auth.event.LoginSuccessEvent;
import com.mannschaft.app.auth.event.MfaDisabledEvent;
import com.mannschaft.app.auth.event.MfaEnabledEvent;
import com.mannschaft.app.auth.event.MfaRecoveryRequestedEvent;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.DomainEventPublisher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
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

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String TOTP_USED_KEY_PREFIX = "mannschaft:auth:totp_used:";
    private static final String MFA_SESSION_KEY_PREFIX = "mannschaft:auth:mfa_session_token:";
    private static final String MFA_RECOVERY_RATE_KEY_PREFIX = "mannschaft:auth:mfa_recovery_attempt:";
    private static final int BACKUP_CODE_COUNT = 8;
    private static final int BACKUP_CODE_LENGTH = 8;

    /**
     * TOTP設定を開始する。秘密鍵を生成し、QRコードURLを返す。
     * <p>
     * ※ 実際のAES-256暗号化は将来対応。現時点ではBase64エンコードで保存。
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

        // TODO: AES-256暗号化で保存する（現時点ではBase64エンコード）
        String encodedSecret = Base64.getEncoder().encodeToString(secret.getBytes());

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

        // 秘密鍵をデコード
        // TODO: AES-256復号化に変更する
        String secret = new String(Base64.getDecoder().decode(twoFactorAuth.getTotpSecret()));

        // 1. TOTP検証
        if (!verifyTotpCode(secret, totpCode)) {
            throw new BusinessException(AuthErrorCode.AUTH_018);
        }

        // 2. 使用済みチェック
        String usedKey = TOTP_USED_KEY_PREFIX + userId + ":" + totpCode;
        if (Boolean.TRUE.equals(redisTemplate.hasKey(usedKey))) {
            throw new BusinessException(AuthErrorCode.AUTH_018);
        }
        // コードを使用済みとしてマーク（60秒有効 = TOTPの1ウィンドウ分余裕）
        redisTemplate.opsForValue().set(usedKey, "1", 60, TimeUnit.SECONDS);

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

        // 5. イベント発行
        eventPublisher.publish(new MfaEnabledEvent(userId));

        // 6. 平文バックアップコードを返却
        return ApiResponse.of(new BackupCodesResponse(plainCodes));
    }

    /**
     * MFAセッショントークンを使用してTOTPを検証し、トークンを発行する。
     *
     * @param mfaSessionToken MFAセッショントークン
     * @param totpCode        TOTPコード
     * @return トークンレスポンス
     */
    public ApiResponse<TokenResponse> validateTotp(String mfaSessionToken, String totpCode) {
        // 1. Valkey: MFAセッショントークンからuserIdを取得
        String sessionHash = authTokenService.hashToken(mfaSessionToken);
        String sessionKey = MFA_SESSION_KEY_PREFIX + sessionHash;
        String userIdStr = redisTemplate.opsForValue().get(sessionKey);
        if (userIdStr == null) {
            throw new BusinessException(AuthErrorCode.AUTH_019);
        }
        // セッショントークンを削除（一度きり使用）
        redisTemplate.delete(sessionKey);

        Long userId = Long.valueOf(userIdStr);

        TwoFactorAuthEntity twoFactorAuth = twoFactorAuthRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(AuthErrorCode.AUTH_016));

        // 秘密鍵をデコード
        // TODO: AES-256復号化に変更する
        String secret = new String(Base64.getDecoder().decode(twoFactorAuth.getTotpSecret()));

        // 2. TOTP検証 + 使用済みチェック
        if (!verifyTotpCode(secret, totpCode)) {
            throw new BusinessException(AuthErrorCode.AUTH_018);
        }
        String usedKey = TOTP_USED_KEY_PREFIX + userId + ":" + totpCode;
        if (Boolean.TRUE.equals(redisTemplate.hasKey(usedKey))) {
            throw new BusinessException(AuthErrorCode.AUTH_018);
        }
        redisTemplate.opsForValue().set(usedKey, "1", 60, TimeUnit.SECONDS);

        // 3. Access Token + Refresh Token発行
        TokenResponse tokenResponse = issueTokenPair(userId);

        // 4. イベント発行
        // validateTotpはipAddress/userAgentを受け取らないため、ここではnullとする
        eventPublisher.publish(new LoginSuccessEvent(userId, "TOTP", null, null));

        return ApiResponse.of(tokenResponse);
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
        String tokenHash = authTokenService.hashToken(token);

        // 1. トークン検証
        MfaRecoveryTokenEntity recoveryToken = mfaRecoveryTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new BusinessException(AuthErrorCode.AUTH_023));

        if (recoveryToken.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(AuthErrorCode.AUTH_023);
        }

        if (recoveryToken.getUsedAt() != null) {
            throw new BusinessException(AuthErrorCode.AUTH_023);
        }

        recoveryToken.markUsed();
        mfaRecoveryTokenRepository.save(recoveryToken);

        // 2. 2FAを無効化
        TwoFactorAuthEntity twoFactorAuth = twoFactorAuthRepository.findByUserId(recoveryToken.getUserId())
                .orElseThrow(() -> new BusinessException(AuthErrorCode.AUTH_016));

        twoFactorAuth.disable();
        twoFactorAuthRepository.save(twoFactorAuth);

        // 3. Access Token + Refresh Token発行
        TokenResponse tokenResponse = issueTokenPair(recoveryToken.getUserId());

        // 4. イベント発行
        eventPublisher.publish(new MfaDisabledEvent(recoveryToken.getUserId()));

        return ApiResponse.of(tokenResponse);
    }

    // === ヘルパーメソッド ===

    /**
     * TOTPコードを検証する。
     * <p>
     * ※ TODO: TOTPライブラリ（例: com.eatthepath:java-otp）統合時に正式実装する。
     * 現時点では±1ウィンドウ（30秒刻み）の検証ロジックのダミー実装。
     * </p>
     *
     * @param secret 秘密鍵（Base32エンコード済み）
     * @param code   検証するTOTPコード
     * @return 検証成功の場合true
     */
    boolean verifyTotpCode(String secret, String code) {
        // TODO: TOTPライブラリを統合して正式実装する
        // ±1ウィンドウ（30秒刻み）で検証する想定
        // 現時点ではコードが6桁数字であることのみ検証
        if (code == null || !code.matches("\\d{6}")) {
            return false;
        }
        log.warn("TOTP検証はダミー実装です。TOTPライブラリ統合後に正式実装してください。");
        return true;
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
        String accessToken = authTokenService.issueAccessToken(userId, List.of("MEMBER"));
        String refreshToken = authTokenService.generateRefreshToken();
        String refreshTokenHash = authTokenService.hashToken(refreshToken);

        // Refresh Token を保存
        RefreshTokenEntity refreshTokenEntity = RefreshTokenEntity.builder()
                .userId(userId)
                .tokenHash(refreshTokenHash)
                .rememberMe(false)
                .expiresAt(LocalDateTime.now().plusDays(7))
                .build();
        refreshTokenRepository.save(refreshTokenEntity);

        return new TokenResponse(accessToken, refreshToken, 3600);
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
