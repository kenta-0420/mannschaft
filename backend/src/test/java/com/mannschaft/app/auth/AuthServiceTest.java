package com.mannschaft.app.auth;

import com.mannschaft.app.auth.entity.RefreshTokenEntity;
import com.mannschaft.app.auth.entity.TwoFactorAuthEntity;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.RefreshTokenRepository;
import com.mannschaft.app.auth.repository.TwoFactorAuthRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuthPasswordResetService;
import com.mannschaft.app.auth.service.AuthRegistrationService;
import com.mannschaft.app.auth.service.AuthService;
import com.mannschaft.app.auth.service.AuthSessionService;
import com.mannschaft.app.auth.service.AuthTokenRotationService;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.auth.service.NewDeviceDetectionService;
import com.mannschaft.app.auth.dto.LoginRequest;
import com.mannschaft.app.auth.dto.LoginResponse;
import com.mannschaft.app.auth.dto.MfaRequiredResponse;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.DomainEventPublisher;
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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * {@link AuthService} の単体テスト（ログイン本体のみ）。
 *
 * <p>セキュリティ重要案件: register / verifyEmail / resendVerificationEmail /
 * logout 系 / セッション系 / refreshAccessToken / パスワードリセット系のテストは
 * リファクタリング第8弾で各サブサービスへ移送した。それぞれのテストファイルを参照すること。</p>
 *
 * <ul>
 *   <li>{@link AuthRegistrationServiceTest} — register / verifyEmail / resendVerificationEmail</li>
 *   <li>{@link AuthSessionServiceTest} — logout / logoutAllDevices / logoutDevice / getSessions / getLoginHistory</li>
 *   <li>{@link AuthTokenRotationServiceTest} — refreshAccessToken</li>
 *   <li>{@link AuthPasswordResetServiceTest} — requestPasswordReset / confirmPasswordReset</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService 単体テスト（ログイン本体）")
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

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
    private NewDeviceDetectionService newDeviceDetectionService;

    // サブサービス（ファサードからの委譲先 — login 本体は委譲しないが、コンストラクタ注入に必要）
    @Mock
    private AuthRegistrationService authRegistrationService;

    @Mock
    private AuthSessionService authSessionService;

    @Mock
    private AuthTokenRotationService authTokenRotationService;

    @Mock
    private AuthPasswordResetService authPasswordResetService;

    // 認可基盤完全根治 Phase 1: ログイン成功時のトークン発行で roles を解決するヘルパ。
    @Mock
    private com.mannschaft.app.auth.service.RoleClaimResolver roleClaimResolver;

    @Mock
    private com.mannschaft.app.auth.service.StatusClaimResolver statusClaimResolver;

    @InjectMocks
    private AuthService authService;

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

    private LoginRequest createLoginRequest() {
        return new LoginRequest(TEST_EMAIL, TEST_PASSWORD, false, null);
    }

    // ========================================
    // login（ファサード本体のロジック）
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
        @DisplayName("異常系: パスワード不一致でAUTH_001例外")
        void login_パスワード不一致_AUTH001例外() {
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
                            .isEqualTo("AUTH_001"));
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
        @DisplayName("異常系: ユーザー不在でAUTH_001例外（タイミング攻撃対策あり）")
        void login_ユーザー不在_AUTH001例外() {
            // Given
            LoginRequest req = createLoginRequest();
            given(userRepository.findByEmail(TEST_EMAIL)).willReturn(Optional.empty());
            // タイミング攻撃対策でダミーbcrypt検証が呼ばれる
            given(passwordEncoder.matches(anyString(), anyString())).willReturn(false);

            // When / Then
            assertThatThrownBy(() -> authService.login(req, TEST_IP, TEST_USER_AGENT))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_001"));
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
                            .isEqualTo("AUTH_001"));

            // アカウントロックキーが設定されることを確認
            verify(valueOperations).set(contains("account_lock"), eq("1"), anyLong(), any());
        }

        @Test
        @DisplayName("正常系: 旧BCryptハッシュはログイン成功時にArgon2idへ透過的に再ハッシュされる")
        void login_旧BCryptハッシュ_Argon2idへ段階移行() {
            // Given: upgradeEncoding が true（旧アルゴリズム＝生BCrypt）を返す
            LoginRequest req = createLoginRequest();
            UserEntity user = createActiveUser();
            given(userRepository.findByEmail(TEST_EMAIL)).willReturn(Optional.of(user));
            given(passwordEncoder.matches(TEST_PASSWORD, ENCODED_PASSWORD)).willReturn(true);
            given(passwordEncoder.upgradeEncoding(ENCODED_PASSWORD)).willReturn(true);
            given(passwordEncoder.encode(TEST_PASSWORD)).willReturn("{argon2}$argon2id$v=19$rehashed");
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

            // Then: 再エンコードされた Argon2id ハッシュで保存され、ログインも成功する
            assertThat(response.getData()).isInstanceOf(LoginResponse.class);
            verify(passwordEncoder).encode(TEST_PASSWORD);
            verify(userRepository).save(user);
            assertThat(user.getPasswordHash()).isEqualTo("{argon2}$argon2id$v=19$rehashed");
        }

        @Test
        @DisplayName("正常系: 既にArgon2idのハッシュはログイン成功時に再ハッシュしない")
        void login_Argon2idハッシュ_再ハッシュしない() {
            // Given: upgradeEncoding が false（既に最新アルゴリズム）を返す
            LoginRequest req = createLoginRequest();
            UserEntity user = createActiveUser();
            given(userRepository.findByEmail(TEST_EMAIL)).willReturn(Optional.of(user));
            given(passwordEncoder.matches(TEST_PASSWORD, ENCODED_PASSWORD)).willReturn(true);
            given(passwordEncoder.upgradeEncoding(ENCODED_PASSWORD)).willReturn(false);
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

            // Then: encode（再ハッシュ）は呼ばれず、ユーザーの save も発生しない
            assertThat(response.getData()).isInstanceOf(LoginResponse.class);
            verify(passwordEncoder, never()).encode(anyString());
            verify(userRepository, never()).save(any());
            assertThat(user.getPasswordHash()).isEqualTo(ENCODED_PASSWORD);
        }
    }
}
