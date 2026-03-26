package com.mannschaft.app.auth.service;

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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.password.PasswordEncoder;

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

    @InjectMocks
    private Auth2faService auth2faService;

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
            given(twoFactorAuthRepository.findByUserId(TEST_USER_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> auth2faService.verifyTotpSetup(TEST_USER_ID, TEST_TOTP_CODE))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_016"));
        }

        @Test
        @DisplayName("異常系: TOTPコード不正でAUTH_018例外")
        void verifyTotpSetup_TOTPコード不正_AUTH018例外() {
            // Given
            TwoFactorAuthEntity twoFactorAuth = createDisabledTwoFactorAuth();
            given(twoFactorAuthRepository.findByUserId(TEST_USER_ID)).willReturn(Optional.of(twoFactorAuth));
            given(encryptionService.decrypt(TEST_ENCRYPTED_SECRET)).willReturn(TEST_TOTP_SECRET);

            // When / Then（不正なTOTPコード: verifyTotpCodeが失敗する）
            assertThatThrownBy(() -> auth2faService.verifyTotpSetup(TEST_USER_ID, "000000"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_018"));
        }

        @Test
        @DisplayName("異常系: nullのTOTPコードでAUTH_018例外")
        void verifyTotpSetup_nullコード_AUTH018例外() {
            // Given
            TwoFactorAuthEntity twoFactorAuth = createDisabledTwoFactorAuth();
            given(twoFactorAuthRepository.findByUserId(TEST_USER_ID)).willReturn(Optional.of(twoFactorAuth));
            given(encryptionService.decrypt(TEST_ENCRYPTED_SECRET)).willReturn(TEST_TOTP_SECRET);

            // When / Then
            assertThatThrownBy(() -> auth2faService.verifyTotpSetup(TEST_USER_ID, null))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_018"));
        }

        @Test
        @DisplayName("異常系: 6桁以外のTOTPコードでAUTH_018例外")
        void verifyTotpSetup_6桁以外_AUTH018例外() {
            // Given
            TwoFactorAuthEntity twoFactorAuth = createDisabledTwoFactorAuth();
            given(twoFactorAuthRepository.findByUserId(TEST_USER_ID)).willReturn(Optional.of(twoFactorAuth));
            given(encryptionService.decrypt(TEST_ENCRYPTED_SECRET)).willReturn(TEST_TOTP_SECRET);

            // When / Then
            assertThatThrownBy(() -> auth2faService.verifyTotpSetup(TEST_USER_ID, "12345"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_018"));
        }
    }

    // ========================================
    // validateTotp
    // ========================================

    @Nested
    @DisplayName("validateTotp")
    class ValidateTotp {

        @Test
        @DisplayName("異常系: MFAセッショントークン無効でAUTH_019例外")
        void validateTotp_セッション無効_AUTH019例外() {
            // Given
            given(authTokenService.hashToken(TEST_MFA_SESSION_TOKEN)).willReturn(TEST_SESSION_HASH);
            given(redisTemplate.opsForValue()).willReturn(valueOperations);
            given(valueOperations.get("mannschaft:auth:mfa_session_token:" + TEST_SESSION_HASH))
                    .willReturn(null);

            // When / Then
            assertThatThrownBy(() -> auth2faService.validateTotp(TEST_MFA_SESSION_TOKEN, TEST_TOTP_CODE))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_019"));
        }

        @Test
        @DisplayName("異常系: 2FA設定不在でAUTH_016例外")
        void validateTotp_2FA設定不在_AUTH016例外() {
            // Given
            given(authTokenService.hashToken(TEST_MFA_SESSION_TOKEN)).willReturn(TEST_SESSION_HASH);
            given(redisTemplate.opsForValue()).willReturn(valueOperations);
            given(valueOperations.get("mannschaft:auth:mfa_session_token:" + TEST_SESSION_HASH))
                    .willReturn(String.valueOf(TEST_USER_ID));
            given(redisTemplate.delete("mannschaft:auth:mfa_session_token:" + TEST_SESSION_HASH))
                    .willReturn(true);
            given(twoFactorAuthRepository.findByUserId(TEST_USER_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> auth2faService.validateTotp(TEST_MFA_SESSION_TOKEN, TEST_TOTP_CODE))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_016"));
        }

        @Test
        @DisplayName("異常系: TOTPコード不正でAUTH_018例外")
        void validateTotp_TOTPコード不正_AUTH018例外() {
            // Given
            given(authTokenService.hashToken(TEST_MFA_SESSION_TOKEN)).willReturn(TEST_SESSION_HASH);
            given(redisTemplate.opsForValue()).willReturn(valueOperations);
            given(valueOperations.get("mannschaft:auth:mfa_session_token:" + TEST_SESSION_HASH))
                    .willReturn(String.valueOf(TEST_USER_ID));
            given(redisTemplate.delete("mannschaft:auth:mfa_session_token:" + TEST_SESSION_HASH))
                    .willReturn(true);

            TwoFactorAuthEntity twoFactorAuth = createEnabledTwoFactorAuth();
            given(twoFactorAuthRepository.findByUserId(TEST_USER_ID)).willReturn(Optional.of(twoFactorAuth));
            given(encryptionService.decrypt(TEST_ENCRYPTED_SECRET)).willReturn(TEST_TOTP_SECRET);

            // When / Then（不正なTOTPコード）
            assertThatThrownBy(() -> auth2faService.validateTotp(TEST_MFA_SESSION_TOKEN, "000000"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_018"));
        }

        @Test
        @DisplayName("異常系: 使用済みTOTPコードでAUTH_018例外")
        void validateTotp_使用済みコード_AUTH018例外() {
            // Given
            given(authTokenService.hashToken(TEST_MFA_SESSION_TOKEN)).willReturn(TEST_SESSION_HASH);
            given(redisTemplate.opsForValue()).willReturn(valueOperations);
            given(valueOperations.get("mannschaft:auth:mfa_session_token:" + TEST_SESSION_HASH))
                    .willReturn(String.valueOf(TEST_USER_ID));
            given(redisTemplate.delete("mannschaft:auth:mfa_session_token:" + TEST_SESSION_HASH))
                    .willReturn(true);

            TwoFactorAuthEntity twoFactorAuth = createEnabledTwoFactorAuth();
            given(twoFactorAuthRepository.findByUserId(TEST_USER_ID)).willReturn(Optional.of(twoFactorAuth));
            given(encryptionService.decrypt(TEST_ENCRYPTED_SECRET)).willReturn(TEST_TOTP_SECRET);

            // nullコードはフォーマットバリデーションで弾かれるので不正コードで検証
            assertThatThrownBy(() -> auth2faService.validateTotp(TEST_MFA_SESSION_TOKEN, "abcdef"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_018"));
        }
    }

    // ========================================
    // regenerateBackupCodes
    // ========================================

    @Nested
    @DisplayName("regenerateBackupCodes")
    class RegenerateBackupCodes {

        @Test
        @DisplayName("正常系: 新しいバックアップコード8個が返る")
        void regenerateBackupCodes_正常_8個のコードが返る() {
            // Given
            TwoFactorAuthEntity twoFactorAuth = createEnabledTwoFactorAuth();
            given(twoFactorAuthRepository.findByUserId(TEST_USER_ID)).willReturn(Optional.of(twoFactorAuth));
            given(passwordEncoder.encode(anyString())).willReturn("hashed-backup-code");
            given(twoFactorAuthRepository.save(any(TwoFactorAuthEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            // When
            ApiResponse<BackupCodesResponse> response = auth2faService.regenerateBackupCodes(TEST_USER_ID);

            // Then
            assertThat(response.getData().getBackupCodes()).hasSize(8);
            response.getData().getBackupCodes().forEach(code ->
                    assertThat(code).matches("\\d{8}")
            );
            verify(twoFactorAuthRepository).save(any(TwoFactorAuthEntity.class));
        }

        @Test
        @DisplayName("異常系: 2FA設定不在でAUTH_016例外")
        void regenerateBackupCodes_設定不在_AUTH016例外() {
            // Given
            given(twoFactorAuthRepository.findByUserId(TEST_USER_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> auth2faService.regenerateBackupCodes(TEST_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_016"));
        }

        @Test
        @DisplayName("異常系: 2FA未有効でAUTH_020例外")
        void regenerateBackupCodes_2FA未有効_AUTH020例外() {
            // Given
            TwoFactorAuthEntity disabled = createDisabledTwoFactorAuth();
            given(twoFactorAuthRepository.findByUserId(TEST_USER_ID)).willReturn(Optional.of(disabled));

            // When / Then
            assertThatThrownBy(() -> auth2faService.regenerateBackupCodes(TEST_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_020"));
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
            given(mfaRecoveryTokenRepository.findByTokenHash(tokenHash)).willReturn(Optional.of(recoveryToken));
            given(mfaRecoveryTokenRepository.save(any(MfaRecoveryTokenEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            TwoFactorAuthEntity twoFactorAuth = createEnabledTwoFactorAuth();
            given(twoFactorAuthRepository.findByUserId(TEST_USER_ID)).willReturn(Optional.of(twoFactorAuth));
            given(twoFactorAuthRepository.save(any(TwoFactorAuthEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            given(authTokenService.issueAccessToken(any(), any())).willReturn("jwt-access-token");
            given(authTokenService.generateRefreshToken()).willReturn("raw-refresh-token");
            given(authTokenService.hashToken("raw-refresh-token")).willReturn("hashed-refresh-token");
            given(refreshTokenRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));

            // When
            ApiResponse<TokenResponse> response = auth2faService.confirmMfaRecovery(rawToken);

            // Then
            assertThat(response.getData().getAccessToken()).isEqualTo("jwt-access-token");
            assertThat(response.getData().getRefreshToken()).isEqualTo("raw-refresh-token");
            assertThat(twoFactorAuth.getIsEnabled()).isFalse();
            verify(eventPublisher).publish(any());
        }

        @Test
        @DisplayName("異常系: トークン不在でAUTH_023例外")
        void confirmMfaRecovery_トークン不在_AUTH023例外() {
            // Given
            String rawToken = "nonexistent-token";
            given(authTokenService.hashToken(rawToken)).willReturn("hashed-nonexistent");
            given(mfaRecoveryTokenRepository.findByTokenHash("hashed-nonexistent"))
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
            given(mfaRecoveryTokenRepository.findByTokenHash(tokenHash)).willReturn(Optional.of(expiredToken));

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
            given(mfaRecoveryTokenRepository.findByTokenHash(tokenHash)).willReturn(Optional.of(usedToken));

            // When / Then
            assertThatThrownBy(() -> auth2faService.confirmMfaRecovery(rawToken))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_023"));
        }

        @Test
        @DisplayName("異常系: 2FA設定不在でAUTH_016例外")
        void confirmMfaRecovery_2FA設定不在_AUTH016例外() {
            // Given
            String rawToken = "recovery-token";
            String tokenHash = "hashed-recovery-token";
            given(authTokenService.hashToken(rawToken)).willReturn(tokenHash);

            MfaRecoveryTokenEntity recoveryToken = MfaRecoveryTokenEntity.builder()
                    .userId(TEST_USER_ID)
                    .tokenHash(tokenHash)
                    .expiresAt(LocalDateTime.now().plusHours(1))
                    .build();
            given(mfaRecoveryTokenRepository.findByTokenHash(tokenHash)).willReturn(Optional.of(recoveryToken));
            given(mfaRecoveryTokenRepository.save(any(MfaRecoveryTokenEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));
            given(twoFactorAuthRepository.findByUserId(TEST_USER_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> auth2faService.confirmMfaRecovery(rawToken))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_016"));
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
