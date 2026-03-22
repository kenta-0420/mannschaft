package com.mannschaft.app.auth;

import com.mannschaft.app.auth.entity.AuditLogEntity;
import com.mannschaft.app.auth.entity.EmailVerificationTokenEntity;
import com.mannschaft.app.auth.entity.PasswordResetTokenEntity;
import com.mannschaft.app.auth.entity.RefreshTokenEntity;
import com.mannschaft.app.auth.entity.TwoFactorAuthEntity;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.AuditLogRepository;
import com.mannschaft.app.auth.repository.EmailVerificationTokenRepository;
import com.mannschaft.app.auth.repository.PasswordResetTokenRepository;
import com.mannschaft.app.auth.repository.RefreshTokenRepository;
import com.mannschaft.app.auth.repository.TwoFactorAuthRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuthService;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.auth.dto.LoginRequest;
import com.mannschaft.app.auth.dto.LoginResponse;
import com.mannschaft.app.auth.dto.MessageResponse;
import com.mannschaft.app.auth.dto.MfaRequiredResponse;
import com.mannschaft.app.auth.dto.RegisterRequest;
import com.mannschaft.app.auth.dto.TokenResponse;
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
 * {@link AuthService} の単体テスト。
 * ユーザー登録・ログイン・ログアウト・Refresh Tokenローテーション等の認証ロジックを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService 単体テスト")
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private EmailVerificationTokenRepository emailVerificationTokenRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Mock
    private TwoFactorAuthRepository twoFactorAuthRepository;

    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private AuthTokenService authTokenService;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private DomainEventPublisher eventPublisher;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private EncryptionService encryptionService;

    @InjectMocks
    private AuthService authService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final String TEST_EMAIL = "test@example.com";
    private static final String TEST_PASSWORD = "Password1!";
    private static final String TEST_IP = "127.0.0.1";
    private static final String TEST_USER_AGENT = "Mozilla/5.0";
    private static final String ENCODED_PASSWORD = "$2a$12$encodedPasswordHash";

    private UserEntity createActiveUser() {
        return UserEntity.builder()
                .email(TEST_EMAIL)
                .passwordHash(ENCODED_PASSWORD)
                .lastName("山田")
                .firstName("太郎")
                .displayName("yamada")
                .locale("ja")
                .timezone("Asia/Tokyo")
                .status(UserEntity.UserStatus.ACTIVE)
                .isSearchable(true)
                .build();
    }

    private UserEntity createPendingUser() {
        return UserEntity.builder()
                .email(TEST_EMAIL)
                .passwordHash(ENCODED_PASSWORD)
                .lastName("山田")
                .firstName("太郎")
                .displayName("yamada")
                .locale("ja")
                .timezone("Asia/Tokyo")
                .status(UserEntity.UserStatus.PENDING_VERIFICATION)
                .isSearchable(true)
                .build();
    }

    private UserEntity createFrozenUser() {
        return UserEntity.builder()
                .email(TEST_EMAIL)
                .passwordHash(ENCODED_PASSWORD)
                .lastName("山田")
                .firstName("太郎")
                .displayName("yamada")
                .locale("ja")
                .timezone("Asia/Tokyo")
                .status(UserEntity.UserStatus.FROZEN)
                .isSearchable(true)
                .build();
    }

    private RegisterRequest createRegisterRequest() {
        return new RegisterRequest(
                TEST_EMAIL, TEST_PASSWORD, "山田", "太郎", "yamada", "ja", "Asia/Tokyo");
    }

    private LoginRequest createLoginRequest() {
        return new LoginRequest(TEST_EMAIL, TEST_PASSWORD, false, null);
    }

    // ========================================
    // register
    // ========================================

    @Nested
    @DisplayName("register")
    class Register {

        @Test
        @DisplayName("正常系: ユーザーが作成される")
        void register_正常_ユーザーが作成される() {
            // Given
            RegisterRequest req = createRegisterRequest();
            given(userRepository.existsByEmail(TEST_EMAIL)).willReturn(false);
            given(passwordEncoder.encode(TEST_PASSWORD)).willReturn(ENCODED_PASSWORD);
            given(userRepository.save(any(UserEntity.class))).willAnswer(invocation -> invocation.getArgument(0));
            given(authTokenService.hashToken(anyString())).willReturn("hashed-token");
            given(emailVerificationTokenRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));

            // When
            ApiResponse<MessageResponse> response = authService.register(req, TEST_IP);

            // Then
            assertThat(response.getData().getMessage()).contains("確認メール");
            verify(userRepository).save(any(UserEntity.class));
            verify(emailVerificationTokenRepository).save(any(EmailVerificationTokenEntity.class));
            verify(eventPublisher).publish(any());
        }

        @Test
        @DisplayName("異常系: メール重複でAUTH_004例外")
        void register_メール重複_AUTH004例外() {
            // Given
            RegisterRequest req = createRegisterRequest();
            given(userRepository.existsByEmail(TEST_EMAIL)).willReturn(true);

            // When / Then
            assertThatThrownBy(() -> authService.register(req, TEST_IP))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_004"));
        }

        @Test
        @DisplayName("異常系: パスワードポリシー違反でAUTH_008例外")
        void register_パスワードポリシー違反_AUTH008例外() {
            // Given: 短すぎるパスワード
            RegisterRequest req = new RegisterRequest(
                    TEST_EMAIL, "short", "山田", "太郎", "yamada", "ja", "Asia/Tokyo");
            given(userRepository.existsByEmail(TEST_EMAIL)).willReturn(false);

            // When / Then
            assertThatThrownBy(() -> authService.register(req, TEST_IP))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_008"));
        }
    }

    // ========================================
    // verifyEmail
    // ========================================

    @Nested
    @DisplayName("verifyEmail")
    class VerifyEmail {

        @Test
        @DisplayName("正常系: ユーザーがACTIVEになる")
        void verifyEmail_正常_ユーザーがACTIVE() {
            // Given
            String rawToken = "valid-token";
            String tokenHash = "hashed-valid-token";
            given(authTokenService.hashToken(rawToken)).willReturn(tokenHash);

            EmailVerificationTokenEntity verificationToken = EmailVerificationTokenEntity.builder()
                    .userId(1L)
                    .tokenHash(tokenHash)
                    .expiresAt(LocalDateTime.now().plusHours(1))
                    .build();
            given(emailVerificationTokenRepository.findByTokenHash(tokenHash))
                    .willReturn(Optional.of(verificationToken));

            UserEntity pendingUser = createPendingUser();
            given(userRepository.findById(1L)).willReturn(Optional.of(pendingUser));

            // When
            ApiResponse<MessageResponse> response = authService.verifyEmail(rawToken);

            // Then
            assertThat(response.getData().getMessage()).contains("メール認証が完了");
            assertThat(pendingUser.getStatus()).isEqualTo(UserEntity.UserStatus.ACTIVE);
            verify(eventPublisher).publish(any());
        }

        @Test
        @DisplayName("異常系: トークン期限切れでAUTH_005例外")
        void verifyEmail_トークン期限切れ_AUTH005例外() {
            // Given
            String rawToken = "expired-token";
            String tokenHash = "hashed-expired-token";
            given(authTokenService.hashToken(rawToken)).willReturn(tokenHash);

            EmailVerificationTokenEntity expiredToken = EmailVerificationTokenEntity.builder()
                    .userId(1L)
                    .tokenHash(tokenHash)
                    .expiresAt(LocalDateTime.now().minusHours(1)) // 期限切れ
                    .build();
            given(emailVerificationTokenRepository.findByTokenHash(tokenHash))
                    .willReturn(Optional.of(expiredToken));

            // When / Then
            assertThatThrownBy(() -> authService.verifyEmail(rawToken))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_005"));
        }
    }

    // ========================================
    // login
    // ========================================

    @Nested
    @DisplayName("login")
    class Login {

        @Test
        @DisplayName("正常系: トークンが返る")
        void login_正常_トークンが返る() {
            // Given
            LoginRequest req = createLoginRequest();
            UserEntity user = createActiveUser();
            given(userRepository.findByEmail(TEST_EMAIL)).willReturn(Optional.of(user));
            given(passwordEncoder.matches(TEST_PASSWORD, ENCODED_PASSWORD)).willReturn(true);
            given(twoFactorAuthRepository.findByUserId(any())).willReturn(Optional.empty());
            given(authTokenService.issueAccessToken(any(), any())).willReturn("jwt-access-token");
            given(authTokenService.generateRefreshToken()).willReturn("raw-refresh-token");
            given(authTokenService.hashToken("raw-refresh-token")).willReturn("hashed-refresh-token");
            given(authTokenService.getRefreshTokenExpirationSeconds()).willReturn(604800L);
            given(authTokenService.getAccessTokenExpirationSeconds()).willReturn(900L);
            given(refreshTokenRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));
            given(redisTemplate.hasKey(anyString())).willReturn(false);

            // When
            ApiResponse<?> response = authService.login(req, TEST_IP, TEST_USER_AGENT);

            // Then
            assertThat(response.getData()).isInstanceOf(LoginResponse.class);
            LoginResponse loginResponse = (LoginResponse) response.getData();
            assertThat(loginResponse.getAccessToken()).isEqualTo("jwt-access-token");
            assertThat(loginResponse.getRefreshToken()).isEqualTo("raw-refresh-token");
            verify(eventPublisher).publish(any());
        }

        @Test
        @DisplayName("異常系: パスワード不一致でAUTH_009例外")
        void login_パスワード不一致_AUTH009例外() {
            // Given
            LoginRequest req = createLoginRequest();
            UserEntity user = createActiveUser();
            given(userRepository.findByEmail(TEST_EMAIL)).willReturn(Optional.of(user));
            given(passwordEncoder.matches(TEST_PASSWORD, ENCODED_PASSWORD)).willReturn(false);
            given(redisTemplate.hasKey(anyString())).willReturn(false);
            given(redisTemplate.opsForValue()).willReturn(valueOperations);
            given(valueOperations.increment(anyString())).willReturn(1L);

            // When / Then
            assertThatThrownBy(() -> authService.login(req, TEST_IP, TEST_USER_AGENT))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_009"));
        }

        @Test
        @DisplayName("異常系: PENDING_VERIFICATION状態でAUTH_002例外")
        void login_PENDING状態_AUTH002例外() {
            // Given
            LoginRequest req = createLoginRequest();
            UserEntity pendingUser = createPendingUser();
            given(userRepository.findByEmail(TEST_EMAIL)).willReturn(Optional.of(pendingUser));

            // When / Then
            assertThatThrownBy(() -> authService.login(req, TEST_IP, TEST_USER_AGENT))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_002"));
        }

        @Test
        @DisplayName("異常系: FROZEN状態でAUTH_003例外")
        void login_FROZEN状態_AUTH003例外() {
            // Given
            LoginRequest req = createLoginRequest();
            UserEntity frozenUser = createFrozenUser();
            given(userRepository.findByEmail(TEST_EMAIL)).willReturn(Optional.of(frozenUser));

            // When / Then
            assertThatThrownBy(() -> authService.login(req, TEST_IP, TEST_USER_AGENT))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_003"));
        }

        @Test
        @DisplayName("正常系: 2FA有効でMfaRequiredResponseが返る")
        void login_2FA有効_MfaRequiredResponse() {
            // Given
            LoginRequest req = createLoginRequest();
            UserEntity user = createActiveUser();
            given(userRepository.findByEmail(TEST_EMAIL)).willReturn(Optional.of(user));
            given(passwordEncoder.matches(TEST_PASSWORD, ENCODED_PASSWORD)).willReturn(true);
            given(redisTemplate.hasKey(anyString())).willReturn(false);
            given(redisTemplate.opsForValue()).willReturn(valueOperations);

            TwoFactorAuthEntity mfa = TwoFactorAuthEntity.builder()
                    .userId(1L)
                    .totpSecret("secret")
                    .backupCodes("[]")
                    .isEnabled(true)
                    .build();
            given(twoFactorAuthRepository.findByUserId(any())).willReturn(Optional.of(mfa));

            // When
            ApiResponse<?> response = authService.login(req, TEST_IP, TEST_USER_AGENT);

            // Then
            assertThat(response.getData()).isInstanceOf(MfaRequiredResponse.class);
            MfaRequiredResponse mfaResponse = (MfaRequiredResponse) response.getData();
            assertThat(mfaResponse.isMfaRequired()).isTrue();
            assertThat(mfaResponse.getMfaSessionToken()).isNotNull();
        }

        @Test
        @DisplayName("異常系: ユーザー不在でAUTH_009例外（タイミング攻撃対策あり）")
        void login_ユーザー不在_AUTH009例外() {
            // Given
            LoginRequest req = createLoginRequest();
            given(userRepository.findByEmail(TEST_EMAIL)).willReturn(Optional.empty());
            // タイミング攻撃対策でダミーbcrypt検証が呼ばれる
            given(passwordEncoder.matches(anyString(), anyString())).willReturn(false);

            // When / Then
            assertThatThrownBy(() -> authService.login(req, TEST_IP, TEST_USER_AGENT))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_009"));
            verify(eventPublisher).publish(any());
        }
    }

    // ========================================
    // refreshAccessToken
    // ========================================

    @Nested
    @DisplayName("refreshAccessToken")
    class RefreshAccessToken {

        @Test
        @DisplayName("正常系: 新トークンペアが発行される")
        void refreshAccessToken_正常_新トークン発行() {
            // Given
            String rawRefreshToken = "raw-refresh-token";
            String tokenHash = "hashed-refresh-token";
            given(authTokenService.hashToken(rawRefreshToken)).willReturn(tokenHash);

            RefreshTokenEntity existingToken = RefreshTokenEntity.builder()
                    .userId(1L)
                    .tokenHash(tokenHash)
                    .rememberMe(false)
                    .ipAddress(TEST_IP)
                    .userAgent(TEST_USER_AGENT)
                    .expiresAt(LocalDateTime.now().plusDays(7))
                    .build();
            given(refreshTokenRepository.findByTokenHash(tokenHash)).willReturn(Optional.of(existingToken));

            UserEntity user = createActiveUser();
            given(userRepository.findById(1L)).willReturn(Optional.of(user));

            given(authTokenService.issueAccessToken(any(), any())).willReturn("new-access-token");
            given(authTokenService.generateRefreshToken()).willReturn("new-raw-refresh-token");
            given(authTokenService.hashToken("new-raw-refresh-token")).willReturn("new-hashed-refresh-token");
            given(authTokenService.getRefreshTokenExpirationSeconds()).willReturn(604800L);
            given(authTokenService.getAccessTokenExpirationSeconds()).willReturn(900L);
            given(refreshTokenRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));

            // When
            ApiResponse<TokenResponse> response = authService.refreshAccessToken(rawRefreshToken, null);

            // Then
            assertThat(response.getData().getAccessToken()).isEqualTo("new-access-token");
            assertThat(response.getData().getRefreshToken()).isEqualTo("new-raw-refresh-token");
            verify(refreshTokenRepository).save(any(RefreshTokenEntity.class));
        }

        @Test
        @DisplayName("異常系: リボーク済みトークンで全トークン失効（リプレイ攻撃検出）")
        void refreshAccessToken_リボーク済み_全トークン失効() {
            // Given
            String rawRefreshToken = "revoked-refresh-token";
            String tokenHash = "hashed-revoked-token";
            given(authTokenService.hashToken(rawRefreshToken)).willReturn(tokenHash);

            RefreshTokenEntity revokedToken = RefreshTokenEntity.builder()
                    .userId(1L)
                    .tokenHash(tokenHash)
                    .rememberMe(false)
                    .expiresAt(LocalDateTime.now().plusDays(7))
                    .build();
            // revoke() を呼んで revokedAt を設定
            revokedToken.revoke();

            given(refreshTokenRepository.findByTokenHash(tokenHash)).willReturn(Optional.of(revokedToken));
            given(refreshTokenRepository.findByUserIdAndRevokedAtIsNull(1L)).willReturn(List.of());

            // When / Then
            assertThatThrownBy(() -> authService.refreshAccessToken(rawRefreshToken, null))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_029"));

            // 全デバイスログアウトが発動されることを確認
            verify(authTokenService).setUserInvalidationTimestamp(1L);
        }

        @Test
        @DisplayName("異常系: 存在しないRefresh TokenでAUTH_007例外")
        void refreshAccessToken_存在しない_AUTH007例外() {
            // Given
            String rawRefreshToken = "nonexistent-token";
            given(authTokenService.hashToken(rawRefreshToken)).willReturn("hashed-nonexistent");
            given(refreshTokenRepository.findByTokenHash("hashed-nonexistent")).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> authService.refreshAccessToken(rawRefreshToken, null))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_007"));
        }
    }
}
