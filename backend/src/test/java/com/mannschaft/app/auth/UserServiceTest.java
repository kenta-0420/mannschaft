package com.mannschaft.app.auth;

import com.mannschaft.app.auth.entity.EmailChangeTokenEntity;
import com.mannschaft.app.auth.entity.TwoFactorAuthEntity;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.event.WithdrawalCancelledEvent;
import com.mannschaft.app.auth.repository.EmailChangeTokenRepository;
import com.mannschaft.app.auth.repository.OAuthAccountRepository;
import com.mannschaft.app.auth.repository.RefreshTokenRepository;
import com.mannschaft.app.auth.repository.TwoFactorAuthRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.repository.WebAuthnCredentialRepository;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.auth.service.ParentalConsentService;
import com.mannschaft.app.auth.service.UserService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.postal.CountryResolver;
import com.mannschaft.app.postal.PostalCodePolicyRegistry;
import com.mannschaft.app.gdpr.GdprErrorCode;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.auth.dto.ChangePasswordRequest;
import com.mannschaft.app.auth.dto.MessageResponse;
import com.mannschaft.app.auth.dto.RequestEmailChangeRequest;
import com.mannschaft.app.auth.dto.RequestWithdrawalRequest;
import com.mannschaft.app.auth.dto.UpdateProfileRequest;
import com.mannschaft.app.auth.dto.UserProfileResponse;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.DomainEventPublisher;
import com.mannschaft.app.common.EncryptionService;
import com.mannschaft.app.common.storage.MediaUrlResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link UserService} の単体テスト。
 * プロフィール操作・パスワード変更・メール変更・退会処理を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserService 単体テスト")
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private EmailChangeTokenRepository emailChangeTokenRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private OAuthAccountRepository oauthAccountRepository;

    @Mock
    private TwoFactorAuthRepository twoFactorAuthRepository;

    @Mock
    private WebAuthnCredentialRepository webauthnCredentialRepository;

    @Mock
    private AuthTokenService authTokenService;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private DomainEventPublisher eventPublisher;

    @Mock
    private EncryptionService encryptionService;

    @Mock
    private UserRoleRepository userRoleRepository;

    @Mock
    private ParentalConsentService parentalConsentService;

    @Mock
    private AccessControlService accessControlService;

    @Mock
    private MediaUrlResolver mediaUrlResolver;

    // Issue #2487: プロフィール更新で timezone / locale が変わったときのキャッシュ即時無効化
    @Mock
    private com.mannschaft.app.common.timezone.UserTimezoneCache userTimezoneCache;

    @Mock
    private com.mannschaft.app.common.i18n.UserLocaleCache userLocaleCache;

    // F02.10 §391 郵便番号検証基盤: 実ロジック（JP 固定）を使う
    @Spy
    private CountryResolver countryResolver = new CountryResolver();

    @Spy
    private PostalCodePolicyRegistry postalCodePolicyRegistry = new PostalCodePolicyRegistry();

    @InjectMocks
    private UserService userService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long USER_ID = 1L;
    private static final String TEST_EMAIL = "test@example.com";
    private static final String ENCODED_PASSWORD = "$2a$12$encodedPasswordHash";
    private static final String TEST_IP = "127.0.0.1";

    private UserEntity createActiveUser() {
        return UserEntity.builder()
                .email(TEST_EMAIL)
                .passwordHash(ENCODED_PASSWORD)
                .lastName("山田")
                .firstName("太郎")
                .lastNameKana("ヤマダ")
                .firstNameKana("タロウ")
                .displayName("yamada")
                .isSearchable(true)
                .locale("ja")
                .timezone("Asia/Tokyo")
                .status(UserEntity.UserStatus.ACTIVE)
                .build();
    }

    private UserEntity createActiveUserWithDeletedAt() {
        UserEntity user = createActiveUser();
        user.requestDeletion(); // deletedAtを設定
        return user;
    }

    /**
     * 既存（永続化済み）ユーザーを再現するため、継承フィールド {@code id} をリフレクションで設定する。
     *
     * <p>{@code id} は {@code BaseEntity} のフィールドで {@code @Builder} の対象外（これが toBuilder バグの根）であり、
     * テストからは builder で設定できないため、永続化済みエンティティの状態を再現する目的でのみ直接代入する。
     */
    private static void setId(UserEntity user, Long id) {
        try {
            java.lang.reflect.Field field =
                    com.mannschaft.app.common.BaseEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(user, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("テスト用 id 設定に失敗", e);
        }
    }

    // ========================================
    // getUserProfile
    // ========================================

    @Nested
    @DisplayName("getUserProfile")
    class GetUserProfile {

        @Test
        @DisplayName("正常系: プロフィールが返却される")
        void getUserProfile_正常_プロフィール返却() {
            // Given
            UserEntity user = createActiveUser();
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
            given(twoFactorAuthRepository.findByUserId(USER_ID)).willReturn(Optional.empty());
            given(webauthnCredentialRepository.findByUserId(USER_ID)).willReturn(List.of());
            given(oauthAccountRepository.findByUserId(USER_ID)).willReturn(List.of());

            // When
            ApiResponse<UserProfileResponse> response = userService.getUserProfile(USER_ID);

            // Then
            UserProfileResponse profile = response.getData();
            assertThat(profile.getEmail()).isEqualTo(TEST_EMAIL);
            assertThat(profile.getNickname()).isEqualTo("yamada");
            assertThat(profile.getLastName()).isEqualTo("山田");
            assertThat(profile.getFirstName()).isEqualTo("太郎");
            assertThat(profile.isHasPassword()).isTrue();
            assertThat(profile.is2faEnabled()).isFalse();
            assertThat(profile.getWebauthnCount()).isZero();
            assertThat(profile.getOauthProviders()).isEmpty();
        }

        @Test
        @DisplayName("異常系: ユーザー不在でAUTH_015例外")
        void getUserProfile_ユーザー不在_AUTH015例外() {
            // Given
            given(userRepository.findById(USER_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> userService.getUserProfile(USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_015"));
        }
    }

    // ========================================
    // changePassword
    // ========================================

    @Nested
    @DisplayName("changePassword")
    class ChangePassword {

        @Test
        @DisplayName("正常系: パスワードが更新される")
        void changePassword_正常_パスワード更新() {
            // Given
            ChangePasswordRequest req = new ChangePasswordRequest("OldPassword1!", "NewPassword1!");
            UserEntity user = createActiveUser();
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
            given(passwordEncoder.matches("OldPassword1!", ENCODED_PASSWORD)).willReturn(true);
            given(passwordEncoder.matches("NewPassword1!", ENCODED_PASSWORD)).willReturn(false);
            given(passwordEncoder.encode("NewPassword1!")).willReturn("$2a$12$newHash");
            given(userRepository.save(any(UserEntity.class))).willAnswer(invocation -> invocation.getArgument(0));
            given(refreshTokenRepository.findByUserIdAndRevokedAtIsNull(USER_ID)).willReturn(List.of());

            // When
            userService.changePassword(USER_ID, req, TEST_IP);

            // Then
            verify(userRepository).save(any(UserEntity.class));
            verify(authTokenService).setUserInvalidationTimestamp(USER_ID);
            verify(eventPublisher).publish(any());
        }

        @Test
        @DisplayName("異常系: 現パスワード不一致でAUTH_010例外")
        void changePassword_現パス不一致_AUTH010例外() {
            // Given
            ChangePasswordRequest req = new ChangePasswordRequest("WrongPassword1!", "NewPassword1!");
            UserEntity user = createActiveUser();
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
            given(passwordEncoder.matches("WrongPassword1!", ENCODED_PASSWORD)).willReturn(false);

            // When / Then
            assertThatThrownBy(() -> userService.changePassword(USER_ID, req, TEST_IP))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_010"));
        }

        @Test
        @DisplayName("異常系: 同一パスワードでAUTH_009例外")
        void changePassword_同一パスワード_AUTH009例外() {
            // Given
            ChangePasswordRequest req = new ChangePasswordRequest("SamePassword1!", "SamePassword1!");
            UserEntity user = createActiveUser();
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
            given(passwordEncoder.matches("SamePassword1!", ENCODED_PASSWORD)).willReturn(true); // 現パスOK
            // 新パスも同じなのでマッチする
            // Note: passwordEncoder.matches は同じハッシュに対して2回呼ばれる
            // 1回目: currentPassword検証 → true
            // 2回目: newPassword同一チェック → true（同一パスワード）

            // When / Then
            assertThatThrownBy(() -> userService.changePassword(USER_ID, req, TEST_IP))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_009"));
        }

        @Test
        @DisplayName("正常系: 3種ちょうど（記号なし）のパスワードが変更時に受理される")
        void changePassword_3種ちょうど記号なし_受理() {
            // Given: "Passw0rd1" は 大文字+小文字+数字 = 3種（記号なし）。
            //   旧ポリシー（4種すべて必須）では弾かれたが、登録時と統一した新ポリシー（3種以上）では受理されること。
            String newPassword = "Passw0rd1";
            ChangePasswordRequest req = new ChangePasswordRequest("OldPassword1!", newPassword);
            UserEntity user = createActiveUser();
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
            given(passwordEncoder.matches("OldPassword1!", ENCODED_PASSWORD)).willReturn(true);
            given(passwordEncoder.matches(newPassword, ENCODED_PASSWORD)).willReturn(false);
            given(passwordEncoder.encode(newPassword)).willReturn("$2a$12$newHash");
            given(userRepository.save(any(UserEntity.class))).willAnswer(invocation -> invocation.getArgument(0));
            given(refreshTokenRepository.findByUserIdAndRevokedAtIsNull(USER_ID)).willReturn(List.of());

            // When
            userService.changePassword(USER_ID, req, TEST_IP);

            // Then: ポリシー違反でスローされず、更新が完了すること
            verify(userRepository).save(any(UserEntity.class));
            verify(authTokenService).setUserInvalidationTimestamp(USER_ID);
        }

        @Test
        @DisplayName("異常系: 1種のみ（小文字のみ）の弱いパスワードはAUTH_008で拒否される")
        void changePassword_1種のみ_AUTH008例外() {
            // Given: "password" は小文字のみ = 1種。新ポリシー（3種以上）でも当然拒否されること。
            ChangePasswordRequest req = new ChangePasswordRequest("OldPassword1!", "password");
            UserEntity user = createActiveUser();
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
            given(passwordEncoder.matches("OldPassword1!", ENCODED_PASSWORD)).willReturn(true);
            given(passwordEncoder.matches("password", ENCODED_PASSWORD)).willReturn(false);

            // When / Then
            assertThatThrownBy(() -> userService.changePassword(USER_ID, req, TEST_IP))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_008"));
        }
    }

    // ========================================
    // requestEmailChange
    // ========================================

    @Nested
    @DisplayName("requestEmailChange")
    class RequestEmailChange {

        @Test
        @DisplayName("正常系: トークンが生成され確認メールが送信される")
        void requestEmailChange_正常_トークン生成() {
            // Given
            RequestEmailChangeRequest req = new RequestEmailChangeRequest("new@example.com", "Password1!");
            given(userRepository.existsByEmail("new@example.com")).willReturn(false);

            UserEntity user = createActiveUser();
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
            given(passwordEncoder.matches("Password1!", ENCODED_PASSWORD)).willReturn(true);
            given(authTokenService.hashToken(anyString())).willReturn("hashed-change-token");
            given(emailChangeTokenRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));

            // When
            ApiResponse<MessageResponse> response = userService.requestEmailChange(USER_ID, req);

            // Then
            assertThat(response.getData().getMessage()).contains("確認メール");
            verify(emailChangeTokenRepository).save(any(EmailChangeTokenEntity.class));
            verify(eventPublisher).publish(any());
        }

        @Test
        @DisplayName("異常系: メール重複でAUTH_013例外")
        void requestEmailChange_メール重複_AUTH013例外() {
            // Given
            RequestEmailChangeRequest req = new RequestEmailChangeRequest("existing@example.com", "Password1!");
            given(userRepository.existsByEmail("existing@example.com")).willReturn(true);

            // When / Then
            assertThatThrownBy(() -> userService.requestEmailChange(USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_013"));
        }
    }

    // ========================================
    // requestWithdrawal
    // ========================================

    @Nested
    @DisplayName("requestWithdrawal")
    class RequestWithdrawal {

        @Test
        @DisplayName("正常系: deletedAtが設定される")
        void requestWithdrawal_正常_deletedAt設定() {
            // Given
            RequestWithdrawalRequest req = new RequestWithdrawalRequest("Password1!");
            UserEntity user = createActiveUser();
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
            given(passwordEncoder.matches("Password1!", ENCODED_PASSWORD)).willReturn(true);
            given(userRepository.save(any(UserEntity.class))).willAnswer(invocation -> invocation.getArgument(0));
            given(refreshTokenRepository.findByUserIdAndRevokedAtIsNull(USER_ID)).willReturn(List.of());

            // When
            userService.requestWithdrawal(USER_ID, req);

            // Then
            assertThat(user.getDeletedAt()).isNotNull();
            verify(userRepository).save(user);
            verify(authTokenService).setUserInvalidationTimestamp(USER_ID);
            verify(eventPublisher).publish(any());
        }
    }

    // ========================================
    // cancelWithdrawal
    // ========================================

    @Nested
    @DisplayName("cancelWithdrawal")
    class CancelWithdrawal {

        @Test
        @DisplayName("異常系: 未申請でAUTH_032例外")
        void cancelWithdrawal_未申請_AUTH032例外() {
            // Given
            UserEntity user = createActiveUser(); // deletedAt = null
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));

            // When / Then
            assertThatThrownBy(() -> userService.cancelWithdrawal(USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_032"));
        }

        @Test
        @DisplayName("正常系: 退会リクエストが取り消される")
        void cancelWithdrawal_正常_取り消し() {
            // Given
            UserEntity user = createActiveUserWithDeletedAt();
            assertThat(user.getDeletedAt()).isNotNull(); // 事前確認
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
            given(userRepository.save(any(UserEntity.class))).willAnswer(invocation -> invocation.getArgument(0));

            // When
            ApiResponse<MessageResponse> response = userService.cancelWithdrawal(USER_ID);

            // Then
            assertThat(response.getData().getMessage()).contains("取り消し");
            assertThat(user.getDeletedAt()).isNull();
            verify(userRepository).save(user);
        }

        @Test
        @DisplayName("正常系: WithdrawalCancelledEvent が発行される（監査ログ用）")
        void cancelWithdrawal_正常_イベント発行() {
            // Given
            UserEntity user = createActiveUserWithDeletedAt();
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
            given(userRepository.save(any(UserEntity.class))).willAnswer(invocation -> invocation.getArgument(0));

            // When
            userService.cancelWithdrawal(USER_ID);

            // Then
            ArgumentCaptor<WithdrawalCancelledEvent> captor = ArgumentCaptor.forClass(WithdrawalCancelledEvent.class);
            verify(eventPublisher).publish(captor.capture());
            assertThat(captor.getValue().getUserId()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("異常系: 未申請時はイベントが発行されない")
        void cancelWithdrawal_未申請時_イベント未発行() {
            // Given
            UserEntity user = createActiveUser(); // deletedAt = null
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));

            // When / Then
            assertThatThrownBy(() -> userService.cancelWithdrawal(USER_ID))
                    .isInstanceOf(BusinessException.class);
            verify(eventPublisher, never()).publish(any(WithdrawalCancelledEvent.class));
        }
    }

    // ========================================
    // setupPassword
    // ========================================

    @Nested
    @DisplayName("setupPassword")
    class SetupPassword {

        @Test
        @DisplayName("正常系: OAuthユーザーにパスワードが設定される")
        void setupPassword_正常_パスワード設定() {
            // Given
            UserEntity oauthUser = UserEntity.builder()
                    .email(TEST_EMAIL)
                    .passwordHash(null) // OAuthユーザーはNULL
                    .lastName("田中").firstName("花子")
                    .displayName("hanako").isSearchable(true)
                    .locale("ja").timezone("Asia/Tokyo")
                    .status(UserEntity.UserStatus.ACTIVE)
                    .build();
            String newPassword = "NewPassword1!";
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(oauthUser));
            given(passwordEncoder.encode(newPassword)).willReturn("$2a$12$newHash");
            given(userRepository.save(any(UserEntity.class))).willAnswer(invocation -> invocation.getArgument(0));

            // When
            ApiResponse<MessageResponse> response = userService.setupPassword(USER_ID, newPassword);

            // Then
            assertThat(response.getData().getMessage()).contains("パスワードを設定しました");
            verify(userRepository).save(any(UserEntity.class));
        }

        @Test
        @DisplayName("正常系: 3種ちょうど（記号なし）のパスワードが設定時に受理される")
        void setupPassword_3種ちょうど記号なし_受理() {
            // Given: setupPassword も changePassword と同じ統一ポリシー（3種以上）であることを確認する。
            UserEntity oauthUser = UserEntity.builder()
                    .email(TEST_EMAIL).passwordHash(null)
                    .lastName("田中").firstName("花子")
                    .displayName("hanako").isSearchable(true)
                    .locale("ja").timezone("Asia/Tokyo")
                    .status(UserEntity.UserStatus.ACTIVE)
                    .build();
            String newPassword = "Passw0rd1"; // 大文字+小文字+数字 = 3種（記号なし）
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(oauthUser));
            given(passwordEncoder.encode(newPassword)).willReturn("$2a$12$newHash");
            given(userRepository.save(any(UserEntity.class))).willAnswer(invocation -> invocation.getArgument(0));

            // When
            ApiResponse<MessageResponse> response = userService.setupPassword(USER_ID, newPassword);

            // Then
            assertThat(response.getData().getMessage()).contains("パスワードを設定しました");
            verify(userRepository).save(any(UserEntity.class));
        }

        @Test
        @DisplayName("異常系: パスワード既設定でAUTH_011例外")
        void setupPassword_既設定_AUTH011例外() {
            // Given
            UserEntity user = createActiveUser(); // passwordHash != null
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));

            // When / Then
            assertThatThrownBy(() -> userService.setupPassword(USER_ID, "NewPassword1!"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_011"));
        }

        @Test
        @DisplayName("異常系: ポリシー違反パスワードでAUTH_008例外")
        void setupPassword_ポリシー違反_AUTH008例外() {
            // Given
            UserEntity oauthUser = UserEntity.builder()
                    .email(TEST_EMAIL).passwordHash(null)
                    .lastName("田中").firstName("花子")
                    .displayName("hanako").isSearchable(true)
                    .locale("ja").timezone("Asia/Tokyo")
                    .status(UserEntity.UserStatus.ACTIVE)
                    .build();
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(oauthUser));

            // When / Then（ポリシー違反: 数字なし）
            assertThatThrownBy(() -> userService.setupPassword(USER_ID, "weakpassword"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_008"));
        }
    }

    // ========================================
    // confirmEmailChange
    // ========================================

    @Nested
    @DisplayName("confirmEmailChange")
    class ConfirmEmailChange {

        @Test
        @DisplayName("正常系: メールアドレスが変更される")
        void confirmEmailChange_正常_メール変更() {
            // Given
            String rawToken = "email-change-token";
            String tokenHash = "hashed-email-change-token";
            given(authTokenService.hashToken(rawToken)).willReturn(tokenHash);

            EmailChangeTokenEntity emailChangeToken = EmailChangeTokenEntity.builder()
                    .userId(USER_ID)
                    .newEmail("new@example.com")
                    .tokenHash(tokenHash)
                    .expiresAt(LocalDateTime.now().plusHours(24))
                    .build();
            given(emailChangeTokenRepository.findByTokenHash(tokenHash))
                    .willReturn(Optional.of(emailChangeToken));
            given(userRepository.existsByEmail("new@example.com")).willReturn(false);

            UserEntity user = createActiveUser();
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
            given(userRepository.save(any(UserEntity.class))).willAnswer(invocation -> invocation.getArgument(0));
            given(emailChangeTokenRepository.save(any(EmailChangeTokenEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));
            given(refreshTokenRepository.findByUserIdAndRevokedAtIsNull(any())).willReturn(List.of());

            // When
            ApiResponse<MessageResponse> response = userService.confirmEmailChange(rawToken);

            // Then
            assertThat(response.getData().getMessage()).contains("メールアドレスを変更しました");
            verify(userRepository).save(any(UserEntity.class));
            verify(authTokenService).setUserInvalidationTimestamp(any());
            verify(eventPublisher).publish(any());
        }

        @Test
        @DisplayName("異常系: トークン不在でAUTH_012例外")
        void confirmEmailChange_トークン不在_AUTH012例外() {
            // Given
            String rawToken = "nonexistent-token";
            given(authTokenService.hashToken(rawToken)).willReturn("hashed-nonexistent");
            given(emailChangeTokenRepository.findByTokenHash("hashed-nonexistent"))
                    .willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> userService.confirmEmailChange(rawToken))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_012"));
        }

        @Test
        @DisplayName("異常系: 期限切れトークンでAUTH_012例外")
        void confirmEmailChange_期限切れ_AUTH012例外() {
            // Given
            String rawToken = "expired-token";
            String tokenHash = "hashed-expired";
            given(authTokenService.hashToken(rawToken)).willReturn(tokenHash);

            EmailChangeTokenEntity expiredToken = EmailChangeTokenEntity.builder()
                    .userId(USER_ID).newEmail("new@example.com")
                    .tokenHash(tokenHash)
                    .expiresAt(LocalDateTime.now().minusHours(1)) // 期限切れ
                    .build();
            given(emailChangeTokenRepository.findByTokenHash(tokenHash))
                    .willReturn(Optional.of(expiredToken));

            // When / Then
            assertThatThrownBy(() -> userService.confirmEmailChange(rawToken))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_012"));
        }

        @Test
        @DisplayName("異常系: 使用済みトークンでAUTH_012例外")
        void confirmEmailChange_使用済み_AUTH012例外() {
            // Given
            String rawToken = "used-token";
            String tokenHash = "hashed-used";
            given(authTokenService.hashToken(rawToken)).willReturn(tokenHash);

            EmailChangeTokenEntity usedToken = EmailChangeTokenEntity.builder()
                    .userId(USER_ID).newEmail("new@example.com")
                    .tokenHash(tokenHash)
                    .expiresAt(LocalDateTime.now().plusHours(24))
                    .build();
            usedToken.markUsed();
            given(emailChangeTokenRepository.findByTokenHash(tokenHash))
                    .willReturn(Optional.of(usedToken));

            // When / Then
            assertThatThrownBy(() -> userService.confirmEmailChange(rawToken))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_012"));
        }

        @Test
        @DisplayName("異常系: 新メール重複でAUTH_013例外")
        void confirmEmailChange_新メール重複_AUTH013例外() {
            // Given
            String rawToken = "valid-token";
            String tokenHash = "hashed-valid";
            given(authTokenService.hashToken(rawToken)).willReturn(tokenHash);

            EmailChangeTokenEntity tokenEntity = EmailChangeTokenEntity.builder()
                    .userId(USER_ID).newEmail("already@example.com")
                    .tokenHash(tokenHash)
                    .expiresAt(LocalDateTime.now().plusHours(24))
                    .build();
            given(emailChangeTokenRepository.findByTokenHash(tokenHash))
                    .willReturn(Optional.of(tokenEntity));
            given(userRepository.existsByEmail("already@example.com")).willReturn(true);

            // When / Then
            assertThatThrownBy(() -> userService.confirmEmailChange(rawToken))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_013"));
        }
    }

    // ========================================
    // updateProfile
    // ========================================

    @Nested
    @DisplayName("updateProfile")
    class UpdateProfile {

        @Test
        @DisplayName("正常系: プロフィールが更新される")
        void updateProfile_正常_プロフィール更新() {
            // Given
            UpdateProfileRequest req = new UpdateProfileRequest(
                    "佐藤", "次郎", "サトウ", "ジロウ",
                    "sato-jiro", null, "ja", null, "Asia/Tokyo",
                    false, null, "090-1234-5678", null, null);
            UserEntity user = createActiveUser();
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
            given(encryptionService.hmac(anyString())).willReturn("hashed-value");
            given(userRepository.save(any(UserEntity.class))).willAnswer(invocation -> invocation.getArgument(0));
            // getUserProfile の呼び出しに必要なモック
            given(twoFactorAuthRepository.findByUserId(USER_ID)).willReturn(Optional.empty());
            given(webauthnCredentialRepository.findByUserId(USER_ID)).willReturn(List.of());
            given(oauthAccountRepository.findByUserId(USER_ID)).willReturn(List.of());

            // When
            ApiResponse<UserProfileResponse> response = userService.updateProfile(USER_ID, req);

            // Then
            assertThat(response.getData()).isNotNull();
            verify(userRepository).save(any(UserEntity.class));
        }

        // AC-1: JP・郵便番号フォーマット不正（"111"）→ AUTH_072
        @Test
        @DisplayName("AC-1 異常系: JP・郵便番号フォーマット不正でAUTH_072例外")
        void updateProfile_郵便番号フォーマット不正_AUTH072例外() {
            UpdateProfileRequest req = new UpdateProfileRequest(
                    null, null, null, null, null, null, null, null, null,
                    null, null, null, "111", null);
            UserEntity user = createActiveUser(); // locale=ja → JP（対応国）
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));

            assertThatThrownBy(() -> userService.updateProfile(USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_072"));
            verify(userRepository, never()).save(any());
        }

        // AC-2: JP・正値（ハイフンあり "123-4567"）→ 成功
        @Test
        @DisplayName("AC-2 正常系: JP・正値（123-4567）で更新成功")
        void updateProfile_正値ハイフンあり_成功() {
            UpdateProfileRequest req = new UpdateProfileRequest(
                    null, null, null, null, null, null, null, null, null,
                    null, null, null, "123-4567", null);
            UserEntity user = createActiveUser();
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
            given(encryptionService.hmac(anyString())).willReturn("hashed-value");
            given(userRepository.save(any(UserEntity.class))).willAnswer(inv -> inv.getArgument(0));
            given(twoFactorAuthRepository.findByUserId(USER_ID)).willReturn(Optional.empty());
            given(webauthnCredentialRepository.findByUserId(USER_ID)).willReturn(List.of());
            given(oauthAccountRepository.findByUserId(USER_ID)).willReturn(List.of());

            ApiResponse<UserProfileResponse> response = userService.updateProfile(USER_ID, req);

            assertThat(response.getData()).isNotNull();
            verify(userRepository).save(any(UserEntity.class));
        }

        // AC-3: JP・正値（ハイフンなし "1234567"）→ 成功
        @Test
        @DisplayName("AC-3 正常系: JP・正値（1234567）で更新成功")
        void updateProfile_正値ハイフンなし_成功() {
            UpdateProfileRequest req = new UpdateProfileRequest(
                    null, null, null, null, null, null, null, null, null,
                    null, null, null, "1234567", null);
            UserEntity user = createActiveUser();
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
            given(encryptionService.hmac(anyString())).willReturn("hashed-value");
            given(userRepository.save(any(UserEntity.class))).willAnswer(inv -> inv.getArgument(0));
            given(twoFactorAuthRepository.findByUserId(USER_ID)).willReturn(Optional.empty());
            given(webauthnCredentialRepository.findByUserId(USER_ID)).willReturn(List.of());
            given(oauthAccountRepository.findByUserId(USER_ID)).willReturn(List.of());

            ApiResponse<UserProfileResponse> response = userService.updateProfile(USER_ID, req);

            assertThat(response.getData()).isNotNull();
            verify(userRepository).save(any(UserEntity.class));
        }

        // AC-7: 明示的に空文字 "" でクリア → 対応国では空に戻せない → AUTH_071
        @Test
        @DisplayName("AC-7 異常系: 明示的に空文字でクリアするとAUTH_071例外（対応国では空不可）")
        void updateProfile_郵便番号空文字クリア_AUTH071例外() {
            UpdateProfileRequest req = new UpdateProfileRequest(
                    null, null, null, null, null, null, null, null, null,
                    null, null, null, "", null);
            UserEntity user = createActiveUser();
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));

            assertThatThrownBy(() -> userService.updateProfile(USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_071"));
            verify(userRepository, never()).save(any());
        }

        // AC-7: postalCode == null（欄据置・未変更）→ 検証スキップ・既存値維持で成功
        @Test
        @DisplayName("AC-7 正常系: postalCode=null（据置）は検証スキップで既存値維持")
        void updateProfile_郵便番号null据置_検証スキップ_成功() {
            UpdateProfileRequest req = new UpdateProfileRequest(
                    "佐藤", null, null, null, null, null, null, null, null,
                    null, null, null, null, null);
            UserEntity user = createActiveUser();
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
            given(encryptionService.hmac(anyString())).willReturn("hashed-value");
            given(userRepository.save(any(UserEntity.class))).willAnswer(inv -> inv.getArgument(0));
            given(twoFactorAuthRepository.findByUserId(USER_ID)).willReturn(Optional.empty());
            given(webauthnCredentialRepository.findByUserId(USER_ID)).willReturn(List.of());
            given(oauthAccountRepository.findByUserId(USER_ID)).willReturn(List.of());

            ApiResponse<UserProfileResponse> response = userService.updateProfile(USER_ID, req);

            assertThat(response.getData()).isNotNull();
            verify(userRepository).save(any(UserEntity.class));
        }
    }

    // ========================================
    // changePassword 追加パターン
    // ========================================

    @Nested
    @DisplayName("changePassword 追加パターン")
    class ChangePasswordAdditional {

        @Test
        @DisplayName("異常系: OAuthユーザー（パスワード未設定）でAUTH_011例外")
        void changePassword_パスワード未設定_AUTH011例外() {
            // Given
            ChangePasswordRequest req = new ChangePasswordRequest("any", "NewPassword1!");
            UserEntity oauthUser = UserEntity.builder()
                    .email(TEST_EMAIL).passwordHash(null)
                    .lastName("田中").firstName("花子")
                    .displayName("hanako").isSearchable(true)
                    .locale("ja").timezone("Asia/Tokyo")
                    .status(UserEntity.UserStatus.ACTIVE)
                    .build();
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(oauthUser));

            // When / Then
            assertThatThrownBy(() -> userService.changePassword(USER_ID, req, TEST_IP))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_011"));
        }

        @Test
        @DisplayName("異常系: パスワードポリシー違反でAUTH_008例外")
        void changePassword_ポリシー違反_AUTH008例外() {
            // Given
            ChangePasswordRequest req = new ChangePasswordRequest("OldPassword1!", "weakpassword");
            UserEntity user = createActiveUser();
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
            given(passwordEncoder.matches("OldPassword1!", ENCODED_PASSWORD)).willReturn(true);
            given(passwordEncoder.matches("weakpassword", ENCODED_PASSWORD)).willReturn(false);

            // When / Then
            assertThatThrownBy(() -> userService.changePassword(USER_ID, req, TEST_IP))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_008"));
        }
    }

    // ========================================
    // requestWithdrawal 追加パターン
    // ========================================

    @Nested
    @DisplayName("requestWithdrawal 追加パターン")
    class RequestWithdrawalAdditional {

        @Test
        @DisplayName("正常系: OAuthユーザー（パスワードなし）は検証なし退会")
        void requestWithdrawal_OAuthユーザー_検証なし退会() {
            // Given
            RequestWithdrawalRequest req = new RequestWithdrawalRequest(null);
            UserEntity oauthUser = UserEntity.builder()
                    .email(TEST_EMAIL).passwordHash(null)
                    .lastName("田中").firstName("花子")
                    .displayName("hanako").isSearchable(true)
                    .locale("ja").timezone("Asia/Tokyo")
                    .status(UserEntity.UserStatus.ACTIVE)
                    .build();
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(oauthUser));
            given(userRepository.save(any(UserEntity.class))).willAnswer(invocation -> invocation.getArgument(0));
            given(refreshTokenRepository.findByUserIdAndRevokedAtIsNull(USER_ID)).willReturn(List.of());

            // When
            userService.requestWithdrawal(USER_ID, req);

            // Then
            assertThat(oauthUser.getDeletedAt()).isNotNull();
            verify(eventPublisher).publish(any());
        }

        @Test
        @DisplayName("異常系: パスワード不一致でAUTH_010例外")
        void requestWithdrawal_パスワード不一致_AUTH010例外() {
            // Given
            RequestWithdrawalRequest req = new RequestWithdrawalRequest("WrongPassword!");
            UserEntity user = createActiveUser();
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
            given(passwordEncoder.matches("WrongPassword!", ENCODED_PASSWORD)).willReturn(false);

            // When / Then
            assertThatThrownBy(() -> userService.requestWithdrawal(USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_010"));
        }
    }

    // ========================================
    // requestEmailChange 追加パターン
    // ========================================

    @Nested
    @DisplayName("requestEmailChange 追加パターン")
    class RequestEmailChangeAdditional {

        @Test
        @DisplayName("異常系: OAuthユーザー（パスワード未設定）でAUTH_011例外")
        void requestEmailChange_パスワード未設定_AUTH011例外() {
            // Given
            RequestEmailChangeRequest req = new RequestEmailChangeRequest("new@example.com", null);
            given(userRepository.existsByEmail("new@example.com")).willReturn(false);

            UserEntity oauthUser = UserEntity.builder()
                    .email(TEST_EMAIL).passwordHash(null)
                    .lastName("田中").firstName("花子")
                    .displayName("hanako").isSearchable(true)
                    .locale("ja").timezone("Asia/Tokyo")
                    .status(UserEntity.UserStatus.ACTIVE)
                    .build();
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(oauthUser));

            // When / Then
            assertThatThrownBy(() -> userService.requestEmailChange(USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_011"));
        }

        @Test
        @DisplayName("異常系: パスワード不一致でAUTH_010例外")
        void requestEmailChange_パスワード不一致_AUTH010例外() {
            // Given
            RequestEmailChangeRequest req = new RequestEmailChangeRequest("new@example.com", "WrongPassword!");
            given(userRepository.existsByEmail("new@example.com")).willReturn(false);

            UserEntity user = createActiveUser();
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
            given(passwordEncoder.matches("WrongPassword!", ENCODED_PASSWORD)).willReturn(false);

            // When / Then
            assertThatThrownBy(() -> userService.requestEmailChange(USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_010"));
        }
    }

    // ========================================
    // getUserProfile 追加パターン
    // ========================================

    @Nested
    @DisplayName("getUserProfile 追加パターン")
    class GetUserProfileAdditional {

        @Test
        @DisplayName("正常系: 2FA有効・WebAuthn有り・OAuthプロバイダ有りのプロフィール")
        void getUserProfile_全認証手段あり_詳細プロフィール返却() {
            // Given
            UserEntity user = createActiveUser();
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));

            TwoFactorAuthEntity twoFa = TwoFactorAuthEntity.builder()
                    .userId(USER_ID).totpSecret("secret").backupCodes("[]").isEnabled(true).build();
            given(twoFactorAuthRepository.findByUserId(USER_ID)).willReturn(Optional.of(twoFa));
            given(webauthnCredentialRepository.findByUserId(USER_ID)).willReturn(List.of(
                    com.mannschaft.app.auth.entity.WebAuthnCredentialEntity.builder()
                            .id(1L).userId(USER_ID).credentialId("cred-id")
                            .publicKey("pk").signCount(0L).deviceName("MacBook").build()
            ));
            com.mannschaft.app.auth.entity.OAuthAccountEntity oauthAccount =
                    com.mannschaft.app.auth.entity.OAuthAccountEntity.builder()
                            .userId(USER_ID)
                            .provider(com.mannschaft.app.auth.entity.OAuthAccountEntity.OAuthProvider.GOOGLE)
                            .providerUserId("google-uid").providerEmail(TEST_EMAIL)
                            .build();
            given(oauthAccountRepository.findByUserId(USER_ID)).willReturn(List.of(oauthAccount));

            // When
            ApiResponse<UserProfileResponse> response = userService.getUserProfile(USER_ID);

            // Then
            UserProfileResponse profile = response.getData();
            assertThat(profile.is2faEnabled()).isTrue();
            assertThat(profile.getWebauthnCount()).isEqualTo(1);
            assertThat(profile.getOauthProviders()).containsExactly("GOOGLE");
        }
    }

    // ========================================
    // withdrawUser — Phase 0-α: 退会匿名化 + 論理削除
    // CLAUDE.md「DB設計の原則 §4」準拠
    // ========================================

    @Nested
    @DisplayName("withdrawUser")
    class WithdrawUser {

        @Test
        @DisplayName("正常系: PII消去 + deletedAt設定 + UserAnonymizedEvent発行")
        void withdrawUser_正常_PII消去とdeletedAt設定とイベント発行() {
            // Given
            UserEntity user = createActiveUser();
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
            given(userRepository.save(any(UserEntity.class))).willAnswer(inv -> inv.getArgument(0));

            // When
            userService.withdrawUser(USER_ID);

            // Then: PII が匿名化されている
            assertThat(user.getEmail()).startsWith("withdrawn-").endsWith("@deleted.mannschaft.internal");
            assertThat(user.getPasswordHash()).isNull();
            assertThat(user.getLastName()).isEqualTo("退会済み");
            assertThat(user.getFirstName()).isEqualTo("ユーザー");
            assertThat(user.getDisplayName()).isEqualTo("退会済みユーザー");
            assertThat(user.getAvatarUrl()).isNull();
            assertThat(user.getPhoneNumber()).isNull();
            assertThat(user.getIsSearchable()).isFalse();

            // Then: 論理削除されている
            assertThat(user.getDeletedAt()).isNotNull();

            // Then: トークン無効化が呼ばれた
            verify(refreshTokenRepository).findByUserIdAndRevokedAtIsNull(USER_ID);
            verify(authTokenService).setUserInvalidationTimestamp(USER_ID);

            // Then: UserAnonymizedEvent が発行されている
            verify(eventPublisher).publish(any(com.mannschaft.app.auth.event.UserAnonymizedEvent.class));
        }

        @Test
        @DisplayName("異常系: 唯一の SYSTEM_ADMIN 退会はブロックされる (GDPR_006)")
        void withdrawUser_唯一のSYSTEM_ADMIN_例外() {
            // Given
            doThrow(new BusinessException(GdprErrorCode.GDPR_006))
                    .when(accessControlService).checkNotLastSystemAdmin(USER_ID);

            // When / Then
            assertThatThrownBy(() -> userService.withdrawUser(USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("GDPR_006"));

            // PII消去は呼ばれない
            verify(userRepository, org.mockito.Mockito.never()).save(any());
            verify(eventPublisher, org.mockito.Mockito.never()).publish(any());
        }

        @Test
        @DisplayName("冪等性: softDelete() を再度呼んでも deletedAt は変わらない")
        void softDelete_冪等() {
            // Given
            UserEntity user = createActiveUser();
            user.softDelete();
            LocalDateTime first = user.getDeletedAt();

            // When: 再度呼ぶ
            user.softDelete();

            // Then: 同じインスタンスのまま
            assertThat(user.getDeletedAt()).isEqualTo(first);
        }

        @Test
        @DisplayName("責任分離: anonymize() 単独では deletedAt は変化しない")
        void anonymize_単独ではdeletedAt変化なし() {
            // Given
            UserEntity user = createActiveUser();
            assertThat(user.getDeletedAt()).isNull();

            // When
            user.anonymize();

            // Then: anonymize は PII 消去のみ。deletedAt は softDelete() の責務。
            assertThat(user.getDeletedAt()).isNull();
            assertThat(user.getDisplayName()).isEqualTo("退会済みユーザー");
        }
    }

    // ========================================
    // toBuilder 更新破壊（id 欠落 INSERT 化）回帰テスト — PR #1643 と同型
    //
    // 旧実装は user.toBuilder().build() で作り直して save していたため、@Builder の対象外である
    // 継承フィールド id が引き継がれず id=null の新インスタンスを save → UPDATE でなく INSERT が走り
    // email 一意制約違反で 500 になっていた。直接ミューテートに是正したことを以下で固定する:
    //   ① save に渡るのが findById で取得した「同一インスタンス」であること
    //   ② save に渡るエンティティの id が保持されていること（id 不変＝UPDATE 経路）
    // ========================================

    @Nested
    @DisplayName("toBuilder更新破壊回帰")
    class ToBuilderUpdateRegression {

        private static final Long EXISTING_ID = 42L;

        @Test
        @DisplayName("updateProfile: 取得した同一インスタンスを id 保持のまま UPDATE する（新インスタンス化しない）")
        void updateProfile_既存行をUPDATE_id保持() {
            // Given: 既存（永続化済み・id付き）ユーザー
            UpdateProfileRequest req = new UpdateProfileRequest(
                    "佐藤", "次郎", "サトウ", "ジロウ",
                    "sato-jiro", null, "ja", null, "Asia/Tokyo",
                    false, null, "090-1234-5678", null, null);
            UserEntity user = createActiveUser();
            setId(user, EXISTING_ID);
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
            given(encryptionService.hmac(anyString())).willReturn("hashed-value");
            given(twoFactorAuthRepository.findByUserId(USER_ID)).willReturn(Optional.empty());
            given(webauthnCredentialRepository.findByUserId(USER_ID)).willReturn(List.of());
            given(oauthAccountRepository.findByUserId(USER_ID)).willReturn(List.of());

            ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
            given(userRepository.save(captor.capture())).willAnswer(inv -> inv.getArgument(0));

            // When
            userService.updateProfile(USER_ID, req);

            // Then: save に渡るのは取得した同一インスタンスで、id が保持されている（=UPDATE 経路）
            UserEntity saved = captor.getValue();
            assertThat(saved).isSameAs(user);
            assertThat(saved.getId()).isEqualTo(EXISTING_ID);
            // 更新値が反映されている
            assertThat(saved.getLastName()).isEqualTo("佐藤");
            assertThat(saved.getDisplayName()).isEqualTo("sato-jiro");
        }

        @Test
        @DisplayName("changePassword: 取得した同一インスタンスを id 保持のまま UPDATE する")
        void changePassword_既存行をUPDATE_id保持() {
            // Given
            ChangePasswordRequest req = new ChangePasswordRequest("OldPassword1!", "NewPassword1!");
            UserEntity user = createActiveUser();
            setId(user, EXISTING_ID);
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
            given(passwordEncoder.matches("OldPassword1!", ENCODED_PASSWORD)).willReturn(true);
            given(passwordEncoder.matches("NewPassword1!", ENCODED_PASSWORD)).willReturn(false);
            given(passwordEncoder.encode("NewPassword1!")).willReturn("$2a$12$newHash");
            given(refreshTokenRepository.findByUserIdAndRevokedAtIsNull(USER_ID)).willReturn(List.of());

            ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
            given(userRepository.save(captor.capture())).willAnswer(inv -> inv.getArgument(0));

            // When
            userService.changePassword(USER_ID, req, TEST_IP);

            // Then: 同一インスタンス・id 保持・新ハッシュ反映
            UserEntity saved = captor.getValue();
            assertThat(saved).isSameAs(user);
            assertThat(saved.getId()).isEqualTo(EXISTING_ID);
            assertThat(saved.getPasswordHash()).isEqualTo("$2a$12$newHash");
        }

        @Test
        @DisplayName("setupPassword: 取得した同一インスタンスを id 保持のまま UPDATE する")
        void setupPassword_既存行をUPDATE_id保持() {
            // Given: OAuth ユーザー（passwordHash=null）
            UserEntity oauthUser = UserEntity.builder()
                    .email(TEST_EMAIL).passwordHash(null)
                    .lastName("田中").firstName("花子")
                    .displayName("hanako").isSearchable(true)
                    .locale("ja").timezone("Asia/Tokyo")
                    .status(UserEntity.UserStatus.ACTIVE)
                    .build();
            setId(oauthUser, EXISTING_ID);
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(oauthUser));
            given(passwordEncoder.encode("NewPassword1!")).willReturn("$2a$12$newHash");

            ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
            given(userRepository.save(captor.capture())).willAnswer(inv -> inv.getArgument(0));

            // When
            userService.setupPassword(USER_ID, "NewPassword1!");

            // Then
            UserEntity saved = captor.getValue();
            assertThat(saved).isSameAs(oauthUser);
            assertThat(saved.getId()).isEqualTo(EXISTING_ID);
            assertThat(saved.getPasswordHash()).isEqualTo("$2a$12$newHash");
        }

        @Test
        @DisplayName("confirmEmailChange: 取得した同一インスタンスを id 保持のまま email UPDATE する")
        void confirmEmailChange_既存行をUPDATE_id保持() {
            // Given
            String rawToken = "email-change-token";
            String tokenHash = "hashed-email-change-token";
            given(authTokenService.hashToken(rawToken)).willReturn(tokenHash);

            EmailChangeTokenEntity emailChangeToken = EmailChangeTokenEntity.builder()
                    .userId(USER_ID)
                    .newEmail("new@example.com")
                    .tokenHash(tokenHash)
                    .expiresAt(LocalDateTime.now().plusHours(24))
                    .build();
            given(emailChangeTokenRepository.findByTokenHash(tokenHash))
                    .willReturn(Optional.of(emailChangeToken));
            given(userRepository.existsByEmail("new@example.com")).willReturn(false);

            UserEntity user = createActiveUser();
            setId(user, EXISTING_ID);
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
            given(emailChangeTokenRepository.save(any(EmailChangeTokenEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(refreshTokenRepository.findByUserIdAndRevokedAtIsNull(any())).willReturn(List.of());

            ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
            given(userRepository.save(captor.capture())).willAnswer(inv -> inv.getArgument(0));

            // When
            userService.confirmEmailChange(rawToken);

            // Then: 同一インスタンス・id 保持・email 更新（旧実装は新インスタンス化で email 一意制約500）
            UserEntity saved = captor.getValue();
            assertThat(saved).isSameAs(user);
            assertThat(saved.getId()).isEqualTo(EXISTING_ID);
            assertThat(saved.getEmail()).isEqualTo("new@example.com");
        }
    }
}
