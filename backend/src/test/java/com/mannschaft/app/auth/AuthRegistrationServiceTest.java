package com.mannschaft.app.auth;

import com.mannschaft.app.admin.service.BetaRestrictionService;
import com.mannschaft.app.auth.dto.MessageResponse;
import com.mannschaft.app.auth.dto.RegisterRequest;
import com.mannschaft.app.auth.entity.EmailVerificationTokenEntity;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.EmailVerificationTokenRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuthRegistrationService;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.DomainEventPublisher;
import com.mannschaft.app.common.EncryptionService;
import com.mannschaft.app.role.service.InviteService;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link AuthRegistrationService} の単体テスト。
 * AuthService から分離されたユーザー登録 / メール認証ロジックを検証する。
 *
 * <p>セキュリティ重要案件として、AuthServiceTest から該当 nested クラス単位で
 * テスト挙動を完全保存したまま機械的に移送した。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthRegistrationService 単体テスト")
class AuthRegistrationServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private EmailVerificationTokenRepository emailVerificationTokenRepository;

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

    @Mock
    private BetaRestrictionService betaRestrictionService;

    @Mock
    private InviteService inviteService;

    @InjectMocks
    private AuthRegistrationService authRegistrationService;

    private static final String TEST_EMAIL = "test@example.com";
    private static final String TEST_PASSWORD = "Password1!";
    private static final String TEST_IP = "127.0.0.1";
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

    private RegisterRequest createRegisterRequest() {
        return new RegisterRequest(
                TEST_EMAIL, TEST_PASSWORD, "山田", "太郎", "yamada", null, "ja", "Asia/Tokyo", null, "2000-01-01");
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
            ApiResponse<MessageResponse> response = authRegistrationService.register(req, TEST_IP);

            // Then
            assertThat(response.getData().getMessage()).contains("確認メール");
            verify(userRepository).save(any(UserEntity.class));
            verify(emailVerificationTokenRepository).save(any(EmailVerificationTokenEntity.class));
            // F02.10: UserRegisteredEvent + UserPostalCodeUpdatedEvent の 2 イベントが発行される
            verify(eventPublisher, times(2)).publish(any());
        }

        @Test
        @DisplayName("異常系: メール重複でAUTH_004例外")
        void register_メール重複_AUTH004例外() {
            // Given
            RegisterRequest req = createRegisterRequest();
            given(userRepository.existsByEmail(TEST_EMAIL)).willReturn(true);

            // When / Then
            assertThatThrownBy(() -> authRegistrationService.register(req, TEST_IP))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_004"));
        }

        @Test
        @DisplayName("異常系: パスワードポリシー違反でAUTH_008例外")
        void register_パスワードポリシー違反_AUTH008例外() {
            // Given: 短すぎるパスワード
            RegisterRequest req = new RegisterRequest(
                    TEST_EMAIL, "short", "山田", "太郎", "yamada", null, "ja", "Asia/Tokyo", null, "2000-01-01");
            given(userRepository.existsByEmail(TEST_EMAIL)).willReturn(false);

            // When / Then
            assertThatThrownBy(() -> authRegistrationService.register(req, TEST_IP))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_008"));
        }

        @Test
        @DisplayName("異常系: 文字種不足のパスワードでAUTH_008例外")
        void register_文字種不足_AUTH008例外() {
            // Given: 8文字以上だが小文字と数字の2種のみ
            RegisterRequest req = new RegisterRequest(
                    TEST_EMAIL, "password123", "山田", "太郎", "yamada", null, "ja", "Asia/Tokyo", null, "2000-01-01");
            given(userRepository.existsByEmail(TEST_EMAIL)).willReturn(false);

            // When / Then
            assertThatThrownBy(() -> authRegistrationService.register(req, TEST_IP))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_008"));
        }

        @Test
        @DisplayName("正常系: locale/timezone省略時にデフォルト値が使用される")
        void register_locale省略_デフォルト値() {
            // Given
            RegisterRequest req = new RegisterRequest(
                    TEST_EMAIL, TEST_PASSWORD, "山田", "太郎", "yamada", null, null, null, null, "2000-01-01");
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
            ApiResponse<MessageResponse> response = authRegistrationService.register(req, TEST_IP);

            // Then
            assertThat(response.getData().getMessage()).contains("確認メール");
        }

        @Test
        @DisplayName("正常系: nickname省略時はdisplayNameを氏名から補完する（display_name NOT NULL 制約違反=500を防止）")
        void register_nickname省略_displayNameを氏名から補完() {
            // Given: nickname を null にする（任意項目）
            RegisterRequest req = new RegisterRequest(
                    TEST_EMAIL, TEST_PASSWORD, "山田", "太郎", null, null, "ja", "Asia/Tokyo", null, "2000-01-01");
            given(userRepository.existsByEmail(TEST_EMAIL)).willReturn(false);
            given(passwordEncoder.encode(TEST_PASSWORD)).willReturn(ENCODED_PASSWORD);
            given(userRepository.save(any(UserEntity.class))).willAnswer(invocation -> {
                UserEntity saved = invocation.getArgument(0);
                // display_name は NOT NULL。nickname 未指定でも氏名から補完されていること
                assertThat(saved.getDisplayName()).isEqualTo("山田 太郎");
                return saved;
            });
            given(authTokenService.hashToken(anyString())).willReturn("hashed-token");
            given(emailVerificationTokenRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));

            // When
            ApiResponse<MessageResponse> response = authRegistrationService.register(req, TEST_IP);

            // Then
            assertThat(response.getData().getMessage()).contains("確認メール");
            verify(userRepository).save(any(UserEntity.class));
        }

        @Test
        @DisplayName("正常系: nickname空白時もdisplayNameを氏名から補完する")
        void register_nickname空白_displayNameを氏名から補完() {
            // Given: nickname を空白文字列にする
            RegisterRequest req = new RegisterRequest(
                    TEST_EMAIL, TEST_PASSWORD, "山田", "太郎", "   ", null, "ja", "Asia/Tokyo", null, "2000-01-01");
            given(userRepository.existsByEmail(TEST_EMAIL)).willReturn(false);
            given(passwordEncoder.encode(TEST_PASSWORD)).willReturn(ENCODED_PASSWORD);
            given(userRepository.save(any(UserEntity.class))).willAnswer(invocation -> {
                UserEntity saved = invocation.getArgument(0);
                assertThat(saved.getDisplayName()).isEqualTo("山田 太郎");
                return saved;
            });
            given(authTokenService.hashToken(anyString())).willReturn("hashed-token");
            given(emailVerificationTokenRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));

            // When
            authRegistrationService.register(req, TEST_IP);

            // Then
            verify(userRepository).save(any(UserEntity.class));
        }

        @Test
        @DisplayName("正常系: nickname指定時はnicknameがdisplayNameになる")
        void register_nickname指定_nicknameがdisplayName() {
            // Given
            RegisterRequest req = new RegisterRequest(
                    TEST_EMAIL, TEST_PASSWORD, "山田", "太郎", "yamada", null, "ja", "Asia/Tokyo", null, "2000-01-01");
            given(userRepository.existsByEmail(TEST_EMAIL)).willReturn(false);
            given(passwordEncoder.encode(TEST_PASSWORD)).willReturn(ENCODED_PASSWORD);
            given(userRepository.save(any(UserEntity.class))).willAnswer(invocation -> {
                UserEntity saved = invocation.getArgument(0);
                assertThat(saved.getDisplayName()).isEqualTo("yamada");
                return saved;
            });
            given(authTokenService.hashToken(anyString())).willReturn("hashed-token");
            given(emailVerificationTokenRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));

            // When
            authRegistrationService.register(req, TEST_IP);

            // Then
            verify(userRepository).save(any(UserEntity.class));
        }

        @Test
        @DisplayName("異常系: ベータ制限ON・トークンなし・AUTH_042例外")
        void register_ベータ制限ON_トークンなし_AUTH042() {
            // Given
            given(betaRestrictionService.isEnabled()).willReturn(true);
            RegisterRequest req = new RegisterRequest(
                    "new@example.com", "Password1!", "山田", "太郎", "yamada",
                    "123-4567", "ja", "Asia/Tokyo", null, "2000-01-01");

            // When / Then
            assertThatThrownBy(() -> authRegistrationService.register(req, TEST_IP))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_042"));
        }

        @Test
        @DisplayName("異常系: ベータ制限ON・トークン無効・AUTH_043例外")
        void register_ベータ制限ON_トークン無効_AUTH043() {
            // Given
            given(betaRestrictionService.isEnabled()).willReturn(true);
            given(betaRestrictionService.isBetaTokenValid("bad-token")).willReturn(false);
            RegisterRequest req = new RegisterRequest(
                    "new@example.com", "Password1!", "山田", "太郎", "yamada",
                    "123-4567", "ja", "Asia/Tokyo", "bad-token", "2000-01-01");

            // When / Then
            assertThatThrownBy(() -> authRegistrationService.register(req, TEST_IP))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_043"));
        }

        @Test
        @DisplayName("正常系: ベータ制限ON・トークン有効・登録成功")
        void register_ベータ制限ON_トークン有効_登録成功() {
            // Given
            given(betaRestrictionService.isEnabled()).willReturn(true);
            given(betaRestrictionService.isBetaTokenValid("valid-token")).willReturn(true);
            given(userRepository.existsByEmail(any())).willReturn(false);
            given(passwordEncoder.encode(any())).willReturn(ENCODED_PASSWORD);
            given(userRepository.save(any(UserEntity.class))).willAnswer(inv -> inv.getArgument(0));
            given(authTokenService.hashToken(anyString())).willReturn("hashed-token");
            given(emailVerificationTokenRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            RegisterRequest req = new RegisterRequest(
                    "new@example.com", "Password1!", "山田", "太郎", "yamada",
                    "123-4567", "ja", "Asia/Tokyo", "valid-token", "2000-01-01");

            // When
            authRegistrationService.register(req, TEST_IP);

            // Then
            verify(inviteService).joinByInvite(eq("valid-token"), any());
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
            ApiResponse<MessageResponse> response = authRegistrationService.verifyEmail(rawToken);

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
            assertThatThrownBy(() -> authRegistrationService.verifyEmail(rawToken))
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
            assertThatThrownBy(() -> authRegistrationService.verifyEmail(rawToken))
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
            assertThatThrownBy(() -> authRegistrationService.verifyEmail(rawToken))
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
            assertThatThrownBy(() -> authRegistrationService.verifyEmail(rawToken))
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
            ApiResponse<MessageResponse> response = authRegistrationService.resendVerificationEmail(TEST_EMAIL);

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
            ApiResponse<MessageResponse> response = authRegistrationService.resendVerificationEmail(TEST_EMAIL);

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
            ApiResponse<MessageResponse> response = authRegistrationService.resendVerificationEmail(TEST_EMAIL);

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
            assertThatThrownBy(() -> authRegistrationService.resendVerificationEmail(TEST_EMAIL))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_006"));
        }
    }
}
