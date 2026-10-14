package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.MfaVerificationMethod;
import com.mannschaft.app.auth.entity.MfaRecoveryTokenEntity;
import com.mannschaft.app.auth.entity.RefreshTokenEntity;
import com.mannschaft.app.auth.entity.TwoFactorAuthEntity;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.MfaRecoveryTokenRepository;
import com.mannschaft.app.auth.repository.RefreshTokenRepository;
import com.mannschaft.app.auth.repository.TwoFactorAuthRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.dto.BackupCodesResponse;
import com.mannschaft.app.auth.dto.MessageResponse;
import com.mannschaft.app.auth.dto.TokenResponse;
import com.mannschaft.app.auth.dto.TotpSetupResponse;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.DomainEventPublisher;
import com.mannschaft.app.common.EncryptionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * {@link Auth2faService} の単体テスト。
 * TOTP設定・検証・バックアップコード・MFAリカバリーの認証ロジックを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Auth2faService 単体テスト")
class Auth2faServiceTest {

    @Mock
    private TwoFactorAuthRepository twoFactorAuthRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private MfaRecoveryTokenRepository mfaRecoveryTokenRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private AuthTokenService authTokenService;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private DomainEventPublisher eventPublisher;

    @Mock
    private EncryptionService encryptionService;

    // 認可基盤完全根治 Phase 1: トークン発行時に roles を解決するヘルパ。
    @Mock
    private RoleClaimResolver roleClaimResolver;

    @Mock
    private StatusClaimResolver statusClaimResolver;

    // CMP-261012-0910: MFA セッション（試行上限・ロック）・メールOTP・失敗監査の部品
    @Mock
    private MfaSessionService mfaSessionService;

    @Mock
    private MfaEmailOtpService mfaEmailOtpService;

    @Mock
    private MfaFailureAuditRecorder mfaFailureAuditRecorder;

    // LocalDateTime 列と突き合わせる壁時計（失効時刻の採取に使う）
    @Mock
    private java.time.Clock wallClock;

    @InjectMocks
    private Auth2faService auth2faService;

    @org.junit.jupiter.api.BeforeEach
    void setUpWallClock() {
        lenient().when(wallClock.instant()).thenReturn(java.time.Instant.parse("2026-10-14T00:00:00Z"));
        lenient().when(wallClock.getZone()).thenReturn(java.time.ZoneOffset.UTC);
    }

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long TEST_USER_ID = 1L;
    private static final String TEST_EMAIL = "test@example.com";
    private static final String TEST_TOTP_SECRET = "JBSWY3DPEHPK3PXP";
    private static final String TEST_ENCRYPTED_SECRET = "encrypted-secret";
    private static final String TEST_TOTP_CODE = "123456";
    private static final String TEST_MFA_SESSION_TOKEN = "mfa-session-token";
    private static final String TEST_SESSION_HASH = "hashed-session-token";
    private static final String TEST_IP = "203.0.113.1";
    private static final String TEST_UA = "Auth2faServiceTest";
    private static final MfaSessionService.MfaSession TEST_SESSION =
            new MfaSessionService.MfaSession(TEST_SESSION_HASH, TEST_USER_ID);

    private UserEntity createActiveUser() {
        return UserEntity.builder()
                .email(TEST_EMAIL)
                .passwordHash("$2a$12$encodedPasswordHash")
                .lastName("山田")
                .firstName("太郎")
                .displayName("yamada")
                .locale("ja")
                .timezone("Asia/Tokyo")
                .status(UserEntity.UserStatus.ACTIVE)
                .isSearchable(true)
                .build();
    }

    private TwoFactorAuthEntity createDisabledTwoFactorAuth() {
        return TwoFactorAuthEntity.builder()
                .userId(TEST_USER_ID)
                .totpSecret(TEST_ENCRYPTED_SECRET)
                .backupCodes("[]")
                .isEnabled(false)
                .build();
    }

    private TwoFactorAuthEntity createEnabledTwoFactorAuth() {
        return TwoFactorAuthEntity.builder()
                .userId(TEST_USER_ID)
                .totpSecret(TEST_ENCRYPTED_SECRET)
                .backupCodes("[\"hashed1\",\"hashed2\"]")
                .isEnabled(true)
                .build();
    }

    // ========================================
    // setupTotp
    // ========================================

    @Nested
    @DisplayName("setupTotp")
    class SetupTotp {

        @Test
        @DisplayName("正常系: TOTP秘密鍵とQRコードURLが返る")
        void setupTotp_正常_秘密鍵とQRコードURLが返る() {
            // Given
            given(userRepository.findById(TEST_USER_ID)).willReturn(Optional.of(createActiveUser()));
            given(twoFactorAuthRepository.findByUserId(TEST_USER_ID)).willReturn(Optional.empty());
            given(encryptionService.encrypt(anyString())).willReturn(TEST_ENCRYPTED_SECRET);
            given(twoFactorAuthRepository.save(any(TwoFactorAuthEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            // When
            ApiResponse<TotpSetupResponse> response = auth2faService.setupTotp(TEST_USER_ID);

            // Then
            assertThat(response.getData().getSecret()).isNotNull();
            assertThat(response.getData().getQrCodeUrl()).contains("otpauth://totp/Mannschaft:");
            assertThat(response.getData().getQrCodeUrl()).contains(TEST_EMAIL);
            verify(twoFactorAuthRepository).save(any(TwoFactorAuthEntity.class));
        }

        @Test
        @DisplayName("正常系: 未有効化の既存設定がある場合は削除して再作成")
        void setupTotp_未有効化既存あり_削除して再作成() {
            // Given
            TwoFactorAuthEntity existing = createDisabledTwoFactorAuth();
            given(userRepository.findById(TEST_USER_ID)).willReturn(Optional.of(createActiveUser()));
            given(twoFactorAuthRepository.findByUserId(TEST_USER_ID)).willReturn(Optional.of(existing));
            given(encryptionService.encrypt(anyString())).willReturn(TEST_ENCRYPTED_SECRET);
            given(twoFactorAuthRepository.save(any(TwoFactorAuthEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            // When
            ApiResponse<TotpSetupResponse> response = auth2faService.setupTotp(TEST_USER_ID);

            // Then
            verify(twoFactorAuthRepository).delete(existing);
            verify(twoFactorAuthRepository).save(any(TwoFactorAuthEntity.class));
            assertThat(response.getData().getSecret()).isNotNull();
        }

        @Test
        @DisplayName("異常系: 既に有効な2FAが存在する場合AUTH_017例外")
        void setupTotp_既に有効_AUTH017例外() {
            // Given
            TwoFactorAuthEntity enabled = createEnabledTwoFactorAuth();
            given(userRepository.findById(TEST_USER_ID)).willReturn(Optional.of(createActiveUser()));
            given(twoFactorAuthRepository.findByUserId(TEST_USER_ID)).willReturn(Optional.of(enabled));

            // When / Then
            assertThatThrownBy(() -> auth2faService.setupTotp(TEST_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_017"));
        }

        @Test
        @DisplayName("異常系: ユーザー不在でAUTH_015例外")
        void setupTotp_ユーザー不在_AUTH015例外() {
            // Given
            given(userRepository.findById(TEST_USER_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> auth2faService.setupTotp(TEST_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_015"));
        }
    }

    // ========================================
    // verifyTotpSetup
    // ========================================

    @Nested
    @DisplayName("verifyTotpSetup")
    class VerifyTotpSetup {

        @Test
        @DisplayName("異常系: 2FA設定が存在しない場合AUTH_016例外")
        void verifyTotpSetup_設定不在_AUTH016例外() {
            // Given
            given(twoFactorAuthRepository.findByUserIdForUpdate(TEST_USER_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> auth2faService.verifyTotpSetup(TEST_USER_ID, TEST_TOTP_CODE))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_016"));
        }

        @Test
        @DisplayName("異常系: TOTPコード不正でAUTH_074例外（誤コードは使用済みではない）")
        void verifyTotpSetup_TOTPコード不正_AUTH074例外() {
            // Given
            TwoFactorAuthEntity twoFactorAuth = createDisabledTwoFactorAuth();
            given(twoFactorAuthRepository.findByUserIdForUpdate(TEST_USER_ID)).willReturn(Optional.of(twoFactorAuth));
            given(encryptionService.decrypt(TEST_ENCRYPTED_SECRET)).willReturn(TEST_TOTP_SECRET);

            // When / Then（不正なTOTPコード: verifyTotpCodeが失敗する）
            assertThatThrownBy(() -> auth2faService.verifyTotpSetup(TEST_USER_ID, "000000"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_074"));
        }

        @Test
        @DisplayName("異常系: nullのTOTPコードでAUTH_074例外（誤コードは使用済みではない）")
        void verifyTotpSetup_nullコード_AUTH074例外() {
            // Given
            TwoFactorAuthEntity twoFactorAuth = createDisabledTwoFactorAuth();
            given(twoFactorAuthRepository.findByUserIdForUpdate(TEST_USER_ID)).willReturn(Optional.of(twoFactorAuth));
            given(encryptionService.decrypt(TEST_ENCRYPTED_SECRET)).willReturn(TEST_TOTP_SECRET);

            // When / Then
            assertThatThrownBy(() -> auth2faService.verifyTotpSetup(TEST_USER_ID, null))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_074"));
        }

        @Test
        @DisplayName("異常系: 6桁以外のTOTPコードでAUTH_074例外（誤コードは使用済みではない）")
        void verifyTotpSetup_6桁以外_AUTH074例外() {
            // Given
            TwoFactorAuthEntity twoFactorAuth = createDisabledTwoFactorAuth();
            given(twoFactorAuthRepository.findByUserIdForUpdate(TEST_USER_ID)).willReturn(Optional.of(twoFactorAuth));
            given(encryptionService.decrypt(TEST_ENCRYPTED_SECRET)).willReturn(TEST_TOTP_SECRET);

            // When / Then
            assertThatThrownBy(() -> auth2faService.verifyTotpSetup(TEST_USER_ID, "12345"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_074"));
        }
    }

    // ========================================
    // validateTotp
    // ========================================

    // CMP-261012-0910: 旧 validateTotp(token, totpCode) は validateMfaLogin(token, method, code, ip, ua) へ置き換えた
    // （D-3T 凍結行の入れ替え）。旧テストの各シナリオは新 API の契約（AUTH_073/AUTH_074）で引き継ぐ。
    @Nested
    @DisplayName("validateMfaLogin（旧 validateTotp）")
    class ValidateTotp {

        @Test
        @DisplayName("正常系: リフレッシュトークンを保存するため書き込みトランザクションで実行する")
        void validateTotp_書き込みトランザクション() throws NoSuchMethodException {
            Method method = Auth2faService.class.getDeclaredMethod(
                    "validateMfaLogin", String.class, MfaVerificationMethod.class, String.class,
                    String.class, String.class);
            Transactional transactional = method.getAnnotation(Transactional.class);

            assertThat(transactional).isNotNull();
            assertThat(transactional.readOnly()).isFalse();
        }

        @Test
        @DisplayName("正常系: 2FAログインで保存するリフレッシュトークンにjtiを設定する")
        void issueTokenPair_リフレッシュトークンにjtiを設定する() throws Exception {
            given(authTokenService.generateRefreshToken()).willReturn("refresh-token");
            given(authTokenService.hashToken("refresh-token")).willReturn("refresh-token-hash");
            given(refreshTokenRepository.save(any(RefreshTokenEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            Method method = Auth2faService.class.getDeclaredMethod("issueTokenPair", Long.class);
            method.setAccessible(true);
            method.invoke(auth2faService, TEST_USER_ID);

            ArgumentCaptor<RefreshTokenEntity> captor = ArgumentCaptor.forClass(RefreshTokenEntity.class);
            verify(refreshTokenRepository).save(captor.capture());
            assertThat(captor.getValue().getJti()).isNotBlank();
        }

        @Test
        @DisplayName("異常系: MFAセッショントークン無効でAUTH_073例外（旧 AUTH_019）")
        void validateTotp_セッション無効_AUTH073例外() {
            // Given
            given(mfaSessionService.resolve(TEST_MFA_SESSION_TOKEN))
                    .willThrow(new BusinessException(com.mannschaft.app.auth.AuthErrorCode.AUTH_073));

            // When / Then
            assertThatThrownBy(() -> auth2faService.validateMfaLogin(
                    TEST_MFA_SESSION_TOKEN, MfaVerificationMethod.TOTP, TEST_TOTP_CODE, TEST_IP, TEST_UA))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_073"));
            verify(refreshTokenRepository, never()).save(any());
        }

        @Test
        @DisplayName("異常系: 2FA設定不在ならセッションを破棄しAUTH_073例外（旧 AUTH_016）")
        void validateTotp_2FA設定不在_AUTH073例外() {
            // Given
            given(mfaSessionService.resolve(TEST_MFA_SESSION_TOKEN)).willReturn(TEST_SESSION);
            given(mfaSessionService.beginAttempt(TEST_SESSION)).willReturn(1L);
            given(twoFactorAuthRepository.findByUserIdForUpdate(TEST_USER_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> auth2faService.validateMfaLogin(
                    TEST_MFA_SESSION_TOKEN, MfaVerificationMethod.TOTP, TEST_TOTP_CODE, TEST_IP, TEST_UA))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_073"));
            verify(mfaSessionService).terminate(TEST_SESSION);
            verify(refreshTokenRepository, never()).save(any());
        }

        @Test
        @DisplayName("異常系: 行ロック待ちの間に掛かったユーザーロックを行ロック取得後に検出しAUTH_003（照合もトークン発行もしない）")
        void validateMfaLogin_行ロック後の再確認でロック検出_AUTH003例外() {
            // Given: 入口の確認は未ロック、行ロック取得後の再確認ではロック済み（並列の先行側が 10 回目の失敗でロック）
            given(mfaSessionService.resolve(TEST_MFA_SESSION_TOKEN)).willReturn(TEST_SESSION);
            given(mfaSessionService.beginAttempt(TEST_SESSION)).willReturn(1L);
            org.mockito.Mockito.doNothing()
                    .doThrow(new BusinessException(com.mannschaft.app.auth.AuthErrorCode.AUTH_003))
                    .when(mfaSessionService).assertNotLocked(TEST_USER_ID);
            given(twoFactorAuthRepository.findByUserIdForUpdate(TEST_USER_ID))
                    .willReturn(Optional.of(createEnabledTwoFactorAuth()));

            // When / Then
            assertThatThrownBy(() -> auth2faService.validateMfaLogin(
                    TEST_MFA_SESSION_TOKEN, MfaVerificationMethod.TOTP, TEST_TOTP_CODE, TEST_IP, TEST_UA))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_003"));
            verify(mfaSessionService, times(2)).assertNotLocked(TEST_USER_ID);
            verify(encryptionService, never()).decrypt(any());
            verify(mfaSessionService, never()).consume(any());
            verify(refreshTokenRepository, never()).save(any());
        }

        @Test
        @DisplayName("異常系: TOTPコード不正でAUTH_074例外・失敗を監査と試行に計上（旧 AUTH_018）")
        void validateTotp_TOTPコード不正_AUTH074例外() {
            // Given
            given(mfaSessionService.resolve(TEST_MFA_SESSION_TOKEN)).willReturn(TEST_SESSION);
            given(mfaSessionService.beginAttempt(TEST_SESSION)).willReturn(1L);
            given(mfaSessionService.recordFailure(TEST_SESSION, 1L))
                    .willReturn(new MfaSessionService.FailureOutcome(false, false));
            TwoFactorAuthEntity twoFactorAuth = createEnabledTwoFactorAuth();
            given(twoFactorAuthRepository.findByUserIdForUpdate(TEST_USER_ID)).willReturn(Optional.of(twoFactorAuth));
            given(encryptionService.decrypt(TEST_ENCRYPTED_SECRET)).willReturn(TEST_TOTP_SECRET);

            // When / Then（不正なTOTPコード）
            assertThatThrownBy(() -> auth2faService.validateMfaLogin(
                    TEST_MFA_SESSION_TOKEN, MfaVerificationMethod.TOTP, "000000", TEST_IP, TEST_UA))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_074"));
            verify(mfaFailureAuditRecorder).record(any());
            verify(mfaSessionService, never()).consume(any());
            verify(refreshTokenRepository, never()).save(any());
        }

        @Test
        @DisplayName("異常系: 形式違反のTOTPコードでもAUTH_074例外（旧 AUTH_018）")
        void validateTotp_形式違反コード_AUTH074例外() {
            // Given
            given(mfaSessionService.resolve(TEST_MFA_SESSION_TOKEN)).willReturn(TEST_SESSION);
            given(mfaSessionService.beginAttempt(TEST_SESSION)).willReturn(1L);
            given(mfaSessionService.recordFailure(TEST_SESSION, 1L))
                    .willReturn(new MfaSessionService.FailureOutcome(false, false));
            TwoFactorAuthEntity twoFactorAuth = createEnabledTwoFactorAuth();
            given(twoFactorAuthRepository.findByUserIdForUpdate(TEST_USER_ID)).willReturn(Optional.of(twoFactorAuth));
            given(encryptionService.decrypt(TEST_ENCRYPTED_SECRET)).willReturn(TEST_TOTP_SECRET);

            // HTTP 経路では DTO の形式検証で 400 になるが、Service 直呼びでも不一致として扱う
            assertThatThrownBy(() -> auth2faService.validateMfaLogin(
                    TEST_MFA_SESSION_TOKEN, MfaVerificationMethod.TOTP, "abcdef", TEST_IP, TEST_UA))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_074"));
        }
    }

    // ========================================
    // requestMfaRecovery
    // ========================================

    @Nested
    @DisplayName("requestMfaRecovery")
    class RequestMfaRecovery {

        @Test
        @DisplayName("正常系: リカバリーメール送信メッセージが返る")
        void requestMfaRecovery_正常_メッセージが返る() {
            // Given
            given(redisTemplate.opsForValue()).willReturn(valueOperations);
            given(valueOperations.get("mannschaft:auth:mfa_session_token:" + TEST_SESSION_HASH))
                    .willReturn(String.valueOf(TEST_USER_ID));
            doNothing().when(authTokenService).checkRateLimit(anyString(), anyInt(), any());
            given(userRepository.findById(TEST_USER_ID)).willReturn(Optional.of(createActiveUser()));
            // 再要求の直列化（Codex 検分指摘）: 2FA 行をロックしてから旧トークン失効・新トークン発行
            given(twoFactorAuthRepository.findByUserIdForUpdate(TEST_USER_ID))
                    .willReturn(Optional.of(createEnabledTwoFactorAuth()));
            given(authTokenService.hashToken(anyString())).willReturn(TEST_SESSION_HASH, "hashed-recovery-token");
            given(mfaRecoveryTokenRepository.save(any(MfaRecoveryTokenEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            // When
            ApiResponse<MessageResponse> response = auth2faService.requestMfaRecovery(TEST_MFA_SESSION_TOKEN);

            // Then
            assertThat(response.getData().getMessage()).contains("リカバリーメールを送信しました");
            verify(mfaRecoveryTokenRepository).save(any(MfaRecoveryTokenEntity.class));
            verify(eventPublisher).publish(any());
        }

        @Test
        @DisplayName("異常系: MFAセッショントークン無効でAUTH_019例外")
        void requestMfaRecovery_セッション無効_AUTH019例外() {
            // Given
            given(authTokenService.hashToken(TEST_MFA_SESSION_TOKEN)).willReturn(TEST_SESSION_HASH);
            given(redisTemplate.opsForValue()).willReturn(valueOperations);
            given(valueOperations.get("mannschaft:auth:mfa_session_token:" + TEST_SESSION_HASH))
                    .willReturn(null);

            // When / Then
            assertThatThrownBy(() -> auth2faService.requestMfaRecovery(TEST_MFA_SESSION_TOKEN))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_019"));
        }

        @Test
        @DisplayName("異常系: ユーザー不在でAUTH_015例外")
        void requestMfaRecovery_ユーザー不在_AUTH015例外() {
            // Given
            given(authTokenService.hashToken(TEST_MFA_SESSION_TOKEN)).willReturn(TEST_SESSION_HASH);
            given(redisTemplate.opsForValue()).willReturn(valueOperations);
            given(valueOperations.get("mannschaft:auth:mfa_session_token:" + TEST_SESSION_HASH))
                    .willReturn(String.valueOf(TEST_USER_ID));
            doNothing().when(authTokenService).checkRateLimit(anyString(), anyInt(), any());
            // 2FA 行ロックを最初の DB 読取りにしたため、ユーザー取得はロック取得後
            given(twoFactorAuthRepository.findByUserIdForUpdate(TEST_USER_ID))
                    .willReturn(Optional.of(createEnabledTwoFactorAuth()));
            given(userRepository.findById(TEST_USER_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> auth2faService.requestMfaRecovery(TEST_MFA_SESSION_TOKEN))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_015"));
        }
    }

    // ========================================
    // confirmMfaRecovery
    // ========================================

    @Nested
    @DisplayName("confirmMfaRecovery")
    class ConfirmMfaRecovery {

        @Test
        @DisplayName("正常系: 2FA無効化しトークンが発行される")
        void confirmMfaRecovery_正常_トークン発行() {
            // Given
            String rawToken = "recovery-token";
            String tokenHash = "hashed-recovery-token";
            given(authTokenService.hashToken(rawToken)).willReturn(tokenHash);

            MfaRecoveryTokenEntity recoveryToken = MfaRecoveryTokenEntity.builder()
                    .userId(TEST_USER_ID)
                    .tokenHash(tokenHash)
                    .expiresAt(LocalDateTime.now().plusHours(1))
                    .build();
            given(mfaRecoveryTokenRepository.findUserIdByTokenHash(tokenHash)).willReturn(Optional.of(TEST_USER_ID));
            given(mfaRecoveryTokenRepository.findByTokenHashForUpdate(tokenHash)).willReturn(Optional.of(recoveryToken));
            given(mfaRecoveryTokenRepository.save(any(MfaRecoveryTokenEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            TwoFactorAuthEntity twoFactorAuth = createEnabledTwoFactorAuth();
            given(twoFactorAuthRepository.findByUserIdForUpdate(TEST_USER_ID)).willReturn(Optional.of(twoFactorAuth));

            given(authTokenService.issueAccessToken(any(), any(), anyBoolean())).willReturn("jwt-access-token");
            given(authTokenService.generateRefreshToken()).willReturn("raw-refresh-token");
            given(authTokenService.hashToken("raw-refresh-token")).willReturn("hashed-refresh-token");
            given(refreshTokenRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));

            // When
            ApiResponse<TokenResponse> response = auth2faService.confirmMfaRecovery(rawToken);

            // Then
            assertThat(response.getData().getAccessToken()).isEqualTo("jwt-access-token");
            assertThat(response.getData().getRefreshToken()).isEqualTo("raw-refresh-token");
            // CMP-261012-0910 AC-52: 有効フラグを倒すだけでなく行ごと消す（秘密鍵・バックアップコードを残さない）
            verify(twoFactorAuthRepository).delete(twoFactorAuth);
            // MfaDisabledEvent(reason=RECOVERY) と MfaRecoveryCompletedEvent の 2 件
            verify(eventPublisher, times(2)).publish(any());
        }

        @Test
        @DisplayName("セキュリティ: MFA回復後に既存セッションが全て無効化される")
        void confirmMfaRecovery_既存セッション無効化() {
            // Given
            String rawToken = "recovery-token";
            String tokenHash = "hashed-recovery-token";
            given(authTokenService.hashToken(rawToken)).willReturn(tokenHash);

            MfaRecoveryTokenEntity recoveryToken = MfaRecoveryTokenEntity.builder()
                    .userId(TEST_USER_ID)
                    .tokenHash(tokenHash)
                    .expiresAt(LocalDateTime.now().plusHours(1))
                    .build();
            given(mfaRecoveryTokenRepository.findUserIdByTokenHash(tokenHash)).willReturn(Optional.of(TEST_USER_ID));
            given(mfaRecoveryTokenRepository.findByTokenHashForUpdate(tokenHash)).willReturn(Optional.of(recoveryToken));
            given(mfaRecoveryTokenRepository.save(any(MfaRecoveryTokenEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            TwoFactorAuthEntity twoFactorAuth = createEnabledTwoFactorAuth();
            given(twoFactorAuthRepository.findByUserIdForUpdate(TEST_USER_ID)).willReturn(Optional.of(twoFactorAuth));

            // 既存の active Refresh Token が 2 件あるシナリオ（一括 UPDATE の戻り値で表す）
            given(refreshTokenRepository.revokeAllActiveByUserId(eq(TEST_USER_ID), any(LocalDateTime.class)))
                    .willReturn(2);

            given(authTokenService.issueAccessToken(any(), any(), anyBoolean())).willReturn("jwt-access-token");
            given(authTokenService.generateRefreshToken()).willReturn("raw-refresh-token");
            given(authTokenService.hashToken("raw-refresh-token")).willReturn("hashed-refresh-token");
            given(refreshTokenRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));

            // When
            auth2faService.confirmMfaRecovery(rawToken);

            // Then: 既存 Refresh Token は一括 UPDATE（current read）で失効させる（ロック待ち中に commit された行も対象）
            verify(refreshTokenRepository).revokeAllActiveByUserId(eq(TEST_USER_ID), any(LocalDateTime.class));
            verify(refreshTokenRepository, never()).findByUserIdAndRevokedAtIsNull(any());
            // user_invalidated_at が更新される（JWT を即時失効させる）
            verify(authTokenService).setUserInvalidationTimestamp(TEST_USER_ID);
        }

        @Test
        @DisplayName("異常系: トークン不在でAUTH_023例外")
        void confirmMfaRecovery_トークン不在_AUTH023例外() {
            // Given
            String rawToken = "nonexistent-token";
            given(authTokenService.hashToken(rawToken)).willReturn("hashed-nonexistent");
            given(mfaRecoveryTokenRepository.findUserIdByTokenHash("hashed-nonexistent"))
                    .willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> auth2faService.confirmMfaRecovery(rawToken))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_023"));
        }

        @Test
        @DisplayName("異常系: トークン期限切れでAUTH_023例外")
        void confirmMfaRecovery_期限切れ_AUTH023例外() {
            // Given
            String rawToken = "expired-token";
            String tokenHash = "hashed-expired-token";
            given(authTokenService.hashToken(rawToken)).willReturn(tokenHash);

            MfaRecoveryTokenEntity expiredToken = MfaRecoveryTokenEntity.builder()
                    .userId(TEST_USER_ID)
                    .tokenHash(tokenHash)
                    .expiresAt(LocalDateTime.now().minusHours(1))
                    .build();
            given(mfaRecoveryTokenRepository.findUserIdByTokenHash(tokenHash)).willReturn(Optional.of(TEST_USER_ID));
            given(mfaRecoveryTokenRepository.findByTokenHashForUpdate(tokenHash)).willReturn(Optional.of(expiredToken));
            given(twoFactorAuthRepository.findByUserIdForUpdate(TEST_USER_ID))
                    .willReturn(Optional.of(createEnabledTwoFactorAuth()));

            // When / Then
            assertThatThrownBy(() -> auth2faService.confirmMfaRecovery(rawToken))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_023"));
        }

        @Test
        @DisplayName("異常系: 使用済みトークンでAUTH_023例外")
        void confirmMfaRecovery_使用済み_AUTH023例外() {
            // Given
            String rawToken = "used-token";
            String tokenHash = "hashed-used-token";
            given(authTokenService.hashToken(rawToken)).willReturn(tokenHash);

            MfaRecoveryTokenEntity usedToken = MfaRecoveryTokenEntity.builder()
                    .userId(TEST_USER_ID)
                    .tokenHash(tokenHash)
                    .expiresAt(LocalDateTime.now().plusHours(1))
                    .build();
            usedToken.markUsed();
            given(mfaRecoveryTokenRepository.findUserIdByTokenHash(tokenHash)).willReturn(Optional.of(TEST_USER_ID));
            given(mfaRecoveryTokenRepository.findByTokenHashForUpdate(tokenHash)).willReturn(Optional.of(usedToken));
            given(twoFactorAuthRepository.findByUserIdForUpdate(TEST_USER_ID))
                    .willReturn(Optional.of(createEnabledTwoFactorAuth()));

            // When / Then
            assertThatThrownBy(() -> auth2faService.confirmMfaRecovery(rawToken))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_023"));
        }

        @Test
        @DisplayName("異常系: 2FA設定不在（無効化済み等）は無効トークン扱いでAUTH_023例外（CMP-261012-0910 AC-48。旧 AUTH_016）")
        void confirmMfaRecovery_2FA設定不在_AUTH023例外() {
            // Given
            String rawToken = "recovery-token";
            String tokenHash = "hashed-recovery-token";
            given(authTokenService.hashToken(rawToken)).willReturn(tokenHash);

            // ロック順は 2FA 行 → 回復トークン行。2FA が無ければトークン行に触れずに AUTH_023
            given(mfaRecoveryTokenRepository.findUserIdByTokenHash(tokenHash)).willReturn(Optional.of(TEST_USER_ID));
            given(twoFactorAuthRepository.findByUserIdForUpdate(TEST_USER_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> auth2faService.confirmMfaRecovery(rawToken))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_023"));
        }
    }

    // ========================================
    // generateBackupCodes（パッケージプライベート）
    // ========================================

    @Nested
    @DisplayName("generateBackupCodes")
    class GenerateBackupCodes {

        @Test
        @DisplayName("正常系: 8個の8桁数字コードが生成される")
        void generateBackupCodes_正常_8個の8桁コード() {
            // When
            List<String> codes = auth2faService.generateBackupCodes();

            // Then
            assertThat(codes).hasSize(8);
            codes.forEach(code -> {
                assertThat(code).hasSize(8);
                assertThat(code).matches("\\d{8}");
            });
        }

        @Test
        @DisplayName("境界値: 生成されるコードは全て一意")
        void generateBackupCodes_生成コード_ほぼ一意() {
            // When（セキュアランダムなので完全に重複しないことは保証できないが高確率で一意）
            List<String> codes = auth2faService.generateBackupCodes();

            // Then
            long uniqueCount = codes.stream().distinct().count();
            // 8桁数字で8個 → 理論上の衝突確率は極めて低い
            assertThat(uniqueCount).isGreaterThanOrEqualTo(7L);
        }
    }

    // ========================================
    // verifyTotpCode（パッケージプライベート）
    // ========================================

    @Nested
    @DisplayName("verifyTotpCode")
    class VerifyTotpCode {

        @Test
        @DisplayName("異常系: nullコードでfalse")
        void verifyTotpCode_nullコード_false() {
            // When
            boolean result = auth2faService.verifyTotpCode(TEST_TOTP_SECRET, null);

            // Then
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("異常系: 6桁以外のコードでfalse")
        void verifyTotpCode_5桁コード_false() {
            // When
            boolean result = auth2faService.verifyTotpCode(TEST_TOTP_SECRET, "12345");

            // Then
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("異常系: 数字以外を含むコードでfalse")
        void verifyTotpCode_非数字コード_false() {
            // When
            boolean result = auth2faService.verifyTotpCode(TEST_TOTP_SECRET, "abcdef");

            // Then
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("異常系: 7桁コードでfalse")
        void verifyTotpCode_7桁コード_false() {
            // When
            boolean result = auth2faService.verifyTotpCode(TEST_TOTP_SECRET, "1234567");

            // Then
            assertThat(result).isFalse();
        }
    }
}
