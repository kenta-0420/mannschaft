package com.mannschaft.app.auth;

import com.mannschaft.app.auth.entity.EmailVerificationTokenEntity;
import com.mannschaft.app.auth.entity.PasswordResetTokenEntity;
import com.mannschaft.app.auth.entity.RefreshTokenEntity;
import com.mannschaft.app.auth.entity.TwoFactorAuthEntity;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.EmailVerificationTokenRepository;
import com.mannschaft.app.auth.repository.PasswordResetTokenRepository;
import com.mannschaft.app.auth.repository.RefreshTokenRepository;
import com.mannschaft.app.auth.repository.TwoFactorAuthRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuthService;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.auth.dto.ConfirmPasswordResetRequest;
import com.mannschaft.app.auth.dto.LoginRequest;
import com.mannschaft.app.auth.dto.LoginResponse;
import com.mannschaft.app.auth.dto.LoginHistoryResponse;
import com.mannschaft.app.auth.dto.MessageResponse;
import com.mannschaft.app.auth.dto.MfaRequiredResponse;
import com.mannschaft.app.auth.dto.RegisterRequest;
import com.mannschaft.app.auth.dto.SessionResponse;
import com.mannschaft.app.auth.dto.TokenResponse;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CursorPagedResponse;
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

    private UserEntity createArchivedUser() {
        return UserEntity.builder()
                .email(TEST_EMAIL)
                .passwordHash(ENCODED_PASSWORD)
                .lastName("山田")
                .firstName("太郎")
                .displayName("yamada")
                .locale("ja")
                .timezone("Asia/Tokyo")
                .status(UserEntity.UserStatus.ARCHIVED)
                .isSearchable(true)
                .build();
    }

    private RegisterRequest createRegisterRequest() {
        return new RegisterRequest(
                TEST_EMAIL, TEST_PASSWORD, "山田", "太郎", "yamada", null, "ja", "Asia/Tokyo");
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
                    TEST_EMAIL, "short", "山田", "太郎", "yamada", null, "ja", "Asia/Tokyo");
            given(userRepository.existsByEmail(TEST_EMAIL)).willReturn(false);

            // When / Then
            assertThatThrownBy(() -> authService.register(req, TEST_IP))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_008"));
        }

        @Test
        @DisplayName("異常系: 文字種不足のパスワードでAUTH_008例外")
        void register_文字種不足_AUTH008例外() {
            // Given: 8文字以上だが小文字と数字の2種のみ
            RegisterRequest req = new RegisterRequest(
                    TEST_EMAIL, "password123", "山田", "太郎", "yamada", null, "ja", "Asia/Tokyo");
            given(userRepository.existsByEmail(TEST_EMAIL)).willReturn(false);

            // When / Then
            assertThatThrownBy(() -> authService.register(req, TEST_IP))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_008"));
        }

        @Test
        @DisplayName("正常系: locale/timezone省略時にデフォルト値が使用される")
        void register_locale省略_デフォルト値() {
            // Given
            RegisterRequest req = new RegisterRequest(
                    TEST_EMAIL, TEST_PASSWORD, "山田", "太郎", "yamada", null, null, null);
            given(userRepository.existsByEmail(TEST_EMAIL)).willReturn(false);
            given(passwordEncoder.encode(TEST_PASSWORD)).willReturn(ENCODED_PASSWORD);
            given(userRepository.save(any(UserEntity.class))).willAnswer(invocation -> {
                UserEntity saved = invocation.getArgument(0);
                assertThat(saved.getLocale()).isEqualTo("ja");
                assertThat(saved.getTimezone()).isEqualTo("Asia/Tokyo");
                return saved;
            });
            given(authTokenService.hashToken(anyString())).willReturn("hashed-token");
            given(emailVerificationTokenRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));

            // When
            ApiResponse<MessageResponse> response = authService.register(req, TEST_IP);

            // Then
            assertThat(response.getData().getMessage()).contains("確認メール");
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

        @Test
        @DisplayName("異常系: トークン不在でAUTH_005例外")
        void verifyEmail_トークン不在_AUTH005例外() {
            // Given
            String rawToken = "nonexistent-token";
            given(authTokenService.hashToken(rawToken)).willReturn("hashed-nonexistent");
            given(emailVerificationTokenRepository.findByTokenHash("hashed-nonexistent"))
                    .willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> authService.verifyEmail(rawToken))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_005"));
        }

        @Test
        @DisplayName("異常系: 使用済みトークンでAUTH_005例外")
        void verifyEmail_使用済みトークン_AUTH005例外() {
            // Given
            String rawToken = "used-token";
            String tokenHash = "hashed-used-token";
            given(authTokenService.hashToken(rawToken)).willReturn(tokenHash);

            EmailVerificationTokenEntity usedToken = EmailVerificationTokenEntity.builder()
                    .userId(1L)
                    .tokenHash(tokenHash)
                    .expiresAt(LocalDateTime.now().plusHours(1))
                    .build();
            usedToken.markUsed(); // 使用済みにする
            given(emailVerificationTokenRepository.findByTokenHash(tokenHash))
                    .willReturn(Optional.of(usedToken));

            // When / Then
            assertThatThrownBy(() -> authService.verifyEmail(rawToken))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_005"));
        }

        @Test
        @DisplayName("異常系: ユーザー不在でAUTH_005例外")
        void verifyEmail_ユーザー不在_AUTH005例外() {
            // Given
            String rawToken = "valid-token";
            String tokenHash = "hashed-valid-token";
            given(authTokenService.hashToken(rawToken)).willReturn(tokenHash);

            EmailVerificationTokenEntity verificationToken = EmailVerificationTokenEntity.builder()
                    .userId(999L)
                    .tokenHash(tokenHash)
                    .expiresAt(LocalDateTime.now().plusHours(1))
                    .build();
            given(emailVerificationTokenRepository.findByTokenHash(tokenHash))
                    .willReturn(Optional.of(verificationToken));
            given(userRepository.findById(999L)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> authService.verifyEmail(rawToken))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_005"));
        }
    }

    // ========================================
    // resendVerificationEmail
    // ========================================

    @Nested
    @DisplayName("resendVerificationEmail")
    class ResendVerificationEmail {

        @Test
        @DisplayName("正常系: PENDING_VERIFICATIONユーザーに再送される")
        void resendVerificationEmail_正常_再送() {
            // Given
            given(redisTemplate.hasKey(anyString())).willReturn(false);
            given(redisTemplate.opsForValue()).willReturn(valueOperations);

            UserEntity pendingUser = createPendingUser();
            given(userRepository.findByEmail(TEST_EMAIL)).willReturn(Optional.of(pendingUser));
            given(authTokenService.hashToken(anyString())).willReturn("hashed-token");
            given(emailVerificationTokenRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            // When
            ApiResponse<MessageResponse> response = authService.resendVerificationEmail(TEST_EMAIL);

            // Then
            assertThat(response.getData().getMessage()).contains("確認メール");
            verify(emailVerificationTokenRepository).save(any(EmailVerificationTokenEntity.class));
            verify(eventPublisher).publish(any());
        }

        @Test
        @DisplayName("正常系: ユーザー不在でも同一レスポンス（情報漏洩防止）")
        void resendVerificationEmail_ユーザー不在_同一レスポンス() {
            // Given
            given(redisTemplate.hasKey(anyString())).willReturn(false);
            given(redisTemplate.opsForValue()).willReturn(valueOperations);
            given(userRepository.findByEmail(TEST_EMAIL)).willReturn(Optional.empty());

            // When
            ApiResponse<MessageResponse> response = authService.resendVerificationEmail(TEST_EMAIL);

            // Then
            assertThat(response.getData().getMessage()).contains("確認メール");
            verify(emailVerificationTokenRepository, never()).save(any());
        }

        @Test
        @DisplayName("正常系: ACTIVE状態のユーザーには再送しない（情報漏洩防止）")
        void resendVerificationEmail_ACTIVEユーザー_再送しない() {
            // Given
            given(redisTemplate.hasKey(anyString())).willReturn(false);
            given(redisTemplate.opsForValue()).willReturn(valueOperations);
            given(userRepository.findByEmail(TEST_EMAIL)).willReturn(Optional.of(createActiveUser()));

            // When
            ApiResponse<MessageResponse> response = authService.resendVerificationEmail(TEST_EMAIL);

            // Then
            assertThat(response.getData().getMessage()).contains("確認メール");
            verify(emailVerificationTokenRepository, never()).save(any());
        }

        @Test
        @DisplayName("異常系: クールダウン期間中にAUTH_006例外")
        void resendVerificationEmail_クールダウン中_AUTH006例外() {
            // Given
            given(redisTemplate.hasKey(anyString())).willReturn(true);

            // When / Then
            assertThatThrownBy(() -> authService.resendVerificationEmail(TEST_EMAIL))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_006"));
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

        @Test
        @DisplayName("異常系: アカウントロック中でAUTH_003例外")
        void login_アカウントロック中_AUTH003例外() {
            // Given
            LoginRequest req = createLoginRequest();
            UserEntity user = createActiveUser();
            given(userRepository.findByEmail(TEST_EMAIL)).willReturn(Optional.of(user));
            // アカウントロックキーが存在する
            given(redisTemplate.hasKey(anyString())).willReturn(true);

            // When / Then
            assertThatThrownBy(() -> authService.login(req, TEST_IP, TEST_USER_AGENT))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_003"));
        }

        @Test
        @DisplayName("正常系: ARCHIVED状態のユーザーがログインすると自動復帰する")
        void login_ARCHIVEDユーザー_自動復帰() {
            // Given
            LoginRequest req = createLoginRequest();
            UserEntity archivedUser = createArchivedUser();
            given(userRepository.findByEmail(TEST_EMAIL)).willReturn(Optional.of(archivedUser));
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
            assertThat(archivedUser.getStatus()).isEqualTo(UserEntity.UserStatus.ACTIVE);
        }

        @Test
        @DisplayName("異常系: パスワード失敗5回でアカウントロック発動")
        void login_パスワード失敗5回_アカウントロック() {
            // Given
            LoginRequest req = createLoginRequest();
            UserEntity user = createActiveUser();
            given(userRepository.findByEmail(TEST_EMAIL)).willReturn(Optional.of(user));
            given(passwordEncoder.matches(TEST_PASSWORD, ENCODED_PASSWORD)).willReturn(false);
            given(redisTemplate.hasKey(anyString())).willReturn(false);
            given(redisTemplate.opsForValue()).willReturn(valueOperations);
            // 5回目の失敗（閾値到達）
            given(valueOperations.increment(anyString())).willReturn(5L);

            // When / Then
            assertThatThrownBy(() -> authService.login(req, TEST_IP, TEST_USER_AGENT))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_009"));

            // アカウントロックキーが設定されることを確認
            verify(valueOperations).set(contains("account_lock"), eq("1"), anyLong(), any());
        }
    }

    // ========================================
    // logout
    // ========================================

    @Nested
    @DisplayName("logout")
    class Logout {

        @Test
        @DisplayName("正常系: RefreshToken失効とJTIブラックリスト追加")
        void logout_正常_トークン失効() {
            // Given
            String refreshTokenHash = "hashed-refresh-token";
            String jti = "jti-123";
            long expEpoch = LocalDateTime.now().plusHours(1).toEpochSecond(java.time.ZoneOffset.UTC);

            RefreshTokenEntity token = RefreshTokenEntity.builder()
                    .userId(1L)
                    .tokenHash(refreshTokenHash)
                    .rememberMe(false)
                    .expiresAt(LocalDateTime.now().plusDays(7))
                    .build();
            given(refreshTokenRepository.findByTokenHash(refreshTokenHash))
                    .willReturn(Optional.of(token));

            // When
            authService.logout(refreshTokenHash, jti, expEpoch);

            // Then
            assertThat(token.getRevokedAt()).isNotNull();
            verify(authTokenService).addJtiToBlacklist(eq(jti), anyLong());
            verify(eventPublisher).publish(any());
        }

        @Test
        @DisplayName("正常系: 存在しないRefreshTokenの場合は何もしない")
        void logout_トークン不在_何もしない() {
            // Given
            given(refreshTokenRepository.findByTokenHash("nonexistent"))
                    .willReturn(Optional.empty());

            // When
            authService.logout("nonexistent", "jti", 0L);

            // Then
            verify(authTokenService, never()).addJtiToBlacklist(any(), anyLong());
            verify(eventPublisher, never()).publish(any());
        }
    }

    // ========================================
    // logoutAllDevices
    // ========================================

    @Nested
    @DisplayName("logoutAllDevices")
    class LogoutAllDevices {

        @Test
        @DisplayName("正常系: 全RefreshToken失効とユーザー無効化タイムスタンプ設定")
        void logoutAllDevices_正常_全失効() {
            // Given
            Long userId = 1L;
            RefreshTokenEntity token1 = RefreshTokenEntity.builder()
                    .userId(userId).tokenHash("hash1").rememberMe(false)
                    .expiresAt(LocalDateTime.now().plusDays(7)).build();
            RefreshTokenEntity token2 = RefreshTokenEntity.builder()
                    .userId(userId).tokenHash("hash2").rememberMe(false)
                    .expiresAt(LocalDateTime.now().plusDays(7)).build();
            given(refreshTokenRepository.findByUserIdAndRevokedAtIsNull(userId))
                    .willReturn(List.of(token1, token2));

            // When
            authService.logoutAllDevices(userId);

            // Then
            assertThat(token1.getRevokedAt()).isNotNull();
            assertThat(token2.getRevokedAt()).isNotNull();
            verify(authTokenService).setUserInvalidationTimestamp(userId);
            verify(eventPublisher).publish(any());
        }

        @Test
        @DisplayName("正常系: アクティブトークンなしでも正常終了")
        void logoutAllDevices_トークンなし_正常終了() {
            // Given
            Long userId = 1L;
            given(refreshTokenRepository.findByUserIdAndRevokedAtIsNull(userId))
                    .willReturn(List.of());

            // When
            authService.logoutAllDevices(userId);

            // Then
            verify(authTokenService).setUserInvalidationTimestamp(userId);
            verify(eventPublisher).publish(any());
        }
    }

    // ========================================
    // logoutDevice
    // ========================================

    @Nested
    @DisplayName("logoutDevice")
    class LogoutDevice {

        @Test
        @DisplayName("正常系: 指定デバイスのRefreshTokenが失効される")
        void logoutDevice_正常_トークン失効() {
            // Given
            Long userId = 1L;
            Long tokenId = 100L;
            RefreshTokenEntity token = RefreshTokenEntity.builder()
                    .userId(userId).tokenHash("hash").rememberMe(false)
                    .expiresAt(LocalDateTime.now().plusDays(7)).build();
            given(refreshTokenRepository.findById(tokenId)).willReturn(Optional.of(token));

            // When
            authService.logoutDevice(userId, tokenId, null, null);

            // Then
            assertThat(token.getRevokedAt()).isNotNull();
            verify(eventPublisher).publish(any());
        }

        @Test
        @DisplayName("異常系: 他ユーザーのトークンの場合はAUTH_033")
        void logoutDevice_他ユーザーのトークン_例外() {
            // Given
            Long userId = 1L;
            Long tokenId = 100L;
            RefreshTokenEntity token = RefreshTokenEntity.builder()
                    .userId(999L) // 別のユーザー
                    .tokenHash("hash").rememberMe(false)
                    .expiresAt(LocalDateTime.now().plusDays(7)).build();
            given(refreshTokenRepository.findById(tokenId)).willReturn(Optional.of(token));

            // When/Then
            assertThatThrownBy(() -> authService.logoutDevice(userId, tokenId, null, null))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("異常系: 存在しないトークンIDの場合はAUTH_033")
        void logoutDevice_トークン不在_例外() {
            // Given
            given(refreshTokenRepository.findById(999L)).willReturn(Optional.empty());

            // When/Then
            assertThatThrownBy(() -> authService.logoutDevice(1L, 999L, null, null))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ========================================
    // getSessions
    // ========================================

    @Nested
    @DisplayName("getSessions")
    class GetSessions {

        @Test
        @DisplayName("正常系: アクティブセッション一覧が返る")
        void getSessions_正常_セッション一覧() {
            // Given
            Long userId = 1L;
            RefreshTokenEntity activeToken = RefreshTokenEntity.builder()
                    .userId(userId).tokenHash("hash").rememberMe(false)
                    .ipAddress(TEST_IP).userAgent(TEST_USER_AGENT)
                    .expiresAt(LocalDateTime.now().plusDays(7)).build();
            given(refreshTokenRepository.findByUserIdAndRevokedAtIsNull(userId))
                    .willReturn(List.of(activeToken));

            // When
            ApiResponse<List<SessionResponse>> response = authService.getSessions(userId, null, null);

            // Then
            assertThat(response.getData()).hasSize(1);
        }

        @Test
        @DisplayName("正常系: 期限切れトークンはフィルタされる")
        void getSessions_期限切れ_フィルタ() {
            // Given
            Long userId = 1L;
            RefreshTokenEntity expiredToken = RefreshTokenEntity.builder()
                    .userId(userId).tokenHash("hash").rememberMe(false)
                    .ipAddress(TEST_IP).userAgent(TEST_USER_AGENT)
                    .expiresAt(LocalDateTime.now().minusDays(1)).build(); // 期限切れ
            given(refreshTokenRepository.findByUserIdAndRevokedAtIsNull(userId))
                    .willReturn(List.of(expiredToken));

            // When
            ApiResponse<List<SessionResponse>> response = authService.getSessions(userId, null, null);

            // Then
            assertThat(response.getData()).isEmpty();
        }
    }

    // ========================================
    // getLoginHistory
    // ========================================

    @Nested
    @DisplayName("getLoginHistory")
    class GetLoginHistory {

        @Test
        @DisplayName("正常系: 空のログイン履歴が返る（未実装）")
        void getLoginHistory_正常_空リスト() {
            // When
            CursorPagedResponse<LoginHistoryResponse> response =
                    authService.getLoginHistory(1L, null, 10);

            // Then
            assertThat(response.getData()).isEmpty();
            assertThat(response.getMeta().isHasNext()).isFalse();
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

            given(userRepository.existsById(1L)).willReturn(true);
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

        @Test
        @DisplayName("異常系: 期限切れRefresh TokenでAUTH_032例外")
        void refreshAccessToken_期限切れ_AUTH032例外() {
            // Given
            String rawRefreshToken = "expired-refresh-token";
            String tokenHash = "hashed-expired";
            given(authTokenService.hashToken(rawRefreshToken)).willReturn(tokenHash);

            RefreshTokenEntity expiredToken = RefreshTokenEntity.builder()
                    .userId(1L)
                    .tokenHash(tokenHash)
                    .rememberMe(false)
                    .expiresAt(LocalDateTime.now().minusDays(1)) // 期限切れ
                    .build();
            given(refreshTokenRepository.findByTokenHash(tokenHash)).willReturn(Optional.of(expiredToken));

            // When / Then
            assertThatThrownBy(() -> authService.refreshAccessToken(rawRefreshToken, null))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_032"));
        }

        @Test
        @DisplayName("異常系: ユーザーが存在しない場合AUTH_007例外")
        void refreshAccessToken_ユーザー不在_AUTH007例外() {
            // Given
            String rawRefreshToken = "valid-refresh-token";
            String tokenHash = "hashed-valid";
            given(authTokenService.hashToken(rawRefreshToken)).willReturn(tokenHash);

            RefreshTokenEntity existingToken = RefreshTokenEntity.builder()
                    .userId(999L)
                    .tokenHash(tokenHash)
                    .rememberMe(false)
                    .expiresAt(LocalDateTime.now().plusDays(7))
                    .build();
            given(refreshTokenRepository.findByTokenHash(tokenHash)).willReturn(Optional.of(existingToken));
            given(userRepository.existsById(999L)).willReturn(false);

            // When / Then
            assertThatThrownBy(() -> authService.refreshAccessToken(rawRefreshToken, null))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_007"));
        }
    }

    // ========================================
    // requestPasswordReset
    // ========================================

    @Nested
    @DisplayName("requestPasswordReset")
    class RequestPasswordReset {

        @Test
        @DisplayName("正常系: ユーザーが存在する場合リセットトークンが生成される")
        void requestPasswordReset_正常_トークン生成() {
            // Given
            UserEntity user = createActiveUser();
            given(userRepository.findByEmail(TEST_EMAIL)).willReturn(Optional.of(user));
            given(authTokenService.hashToken(anyString())).willReturn("hashed-token");
            given(passwordResetTokenRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            // When
            ApiResponse<MessageResponse> response = authService.requestPasswordReset(TEST_EMAIL, TEST_IP);

            // Then
            assertThat(response.getData().getMessage()).contains("パスワードリセットメール");
            verify(passwordResetTokenRepository).save(any(PasswordResetTokenEntity.class));
            verify(eventPublisher).publish(any());
        }

        @Test
        @DisplayName("正常系: ユーザー不在でも同一レスポンス（情報漏洩防止）")
        void requestPasswordReset_ユーザー不在_同一レスポンス() {
            // Given
            given(userRepository.findByEmail(TEST_EMAIL)).willReturn(Optional.empty());

            // When
            ApiResponse<MessageResponse> response = authService.requestPasswordReset(TEST_EMAIL, TEST_IP);

            // Then
            assertThat(response.getData().getMessage()).contains("パスワードリセットメール");
            verify(passwordResetTokenRepository, never()).save(any());
            verify(eventPublisher, never()).publish(any());
        }
    }

    // ========================================
    // confirmPasswordReset
    // ========================================

    @Nested
    @DisplayName("confirmPasswordReset")
    class ConfirmPasswordReset {

        @Test
        @DisplayName("正常系: パスワードが正常に変更される")
        void confirmPasswordReset_正常_パスワード変更() {
            // Given
            ConfirmPasswordResetRequest req = new ConfirmPasswordResetRequest("raw-token", "NewPassword1!");
            given(authTokenService.hashToken("raw-token")).willReturn("hashed-token");

            PasswordResetTokenEntity resetToken = PasswordResetTokenEntity.builder()
                    .userId(1L)
                    .tokenHash("hashed-token")
                    .expiresAt(LocalDateTime.now().plusMinutes(15))
                    .build();
            given(passwordResetTokenRepository.findByTokenHash("hashed-token"))
                    .willReturn(Optional.of(resetToken));

            UserEntity user = createActiveUser();
            given(userRepository.findById(1L)).willReturn(Optional.of(user));
            given(passwordEncoder.encode("NewPassword1!")).willReturn("new-encoded-hash");
            given(userRepository.save(any(UserEntity.class))).willAnswer(inv -> inv.getArgument(0));
            given(refreshTokenRepository.findByUserIdAndRevokedAtIsNull(any())).willReturn(List.of());

            // When
            ApiResponse<MessageResponse> response = authService.confirmPasswordReset(req);

            // Then
            assertThat(response.getData().getMessage()).contains("パスワードが正常に変更されました");
            verify(authTokenService).setUserInvalidationTimestamp(any());
            verify(eventPublisher, atLeast(1)).publish(any());
        }

        @Test
        @DisplayName("異常系: トークン不在でAUTH_015例外")
        void confirmPasswordReset_トークン不在_AUTH015例外() {
            // Given
            ConfirmPasswordResetRequest req = new ConfirmPasswordResetRequest("invalid-token", "NewPassword1!");
            given(authTokenService.hashToken("invalid-token")).willReturn("hashed-invalid");
            given(passwordResetTokenRepository.findByTokenHash("hashed-invalid"))
                    .willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> authService.confirmPasswordReset(req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_015"));
        }

        @Test
        @DisplayName("異常系: 使用済みトークンでAUTH_015例外")
        void confirmPasswordReset_使用済みトークン_AUTH015例外() {
            // Given
            ConfirmPasswordResetRequest req = new ConfirmPasswordResetRequest("used-token", "NewPassword1!");
            given(authTokenService.hashToken("used-token")).willReturn("hashed-used");

            PasswordResetTokenEntity usedToken = PasswordResetTokenEntity.builder()
                    .userId(1L)
                    .tokenHash("hashed-used")
                    .expiresAt(LocalDateTime.now().plusMinutes(15))
                    .build();
            usedToken.markUsed();
            given(passwordResetTokenRepository.findByTokenHash("hashed-used"))
                    .willReturn(Optional.of(usedToken));

            // When / Then
            assertThatThrownBy(() -> authService.confirmPasswordReset(req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_015"));
        }

        @Test
        @DisplayName("異常系: 期限切れトークンでAUTH_015例外")
        void confirmPasswordReset_期限切れ_AUTH015例外() {
            // Given
            ConfirmPasswordResetRequest req = new ConfirmPasswordResetRequest("expired-token", "NewPassword1!");
            given(authTokenService.hashToken("expired-token")).willReturn("hashed-expired");

            PasswordResetTokenEntity expiredToken = PasswordResetTokenEntity.builder()
                    .userId(1L)
                    .tokenHash("hashed-expired")
                    .expiresAt(LocalDateTime.now().minusHours(1)) // 期限切れ
                    .build();
            given(passwordResetTokenRepository.findByTokenHash("hashed-expired"))
                    .willReturn(Optional.of(expiredToken));

            // When / Then
            assertThatThrownBy(() -> authService.confirmPasswordReset(req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_015"));
        }
    }
}
