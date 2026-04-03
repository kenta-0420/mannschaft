package com.mannschaft.app.auth;

import com.mannschaft.app.auth.entity.EmailChangeTokenEntity;
import com.mannschaft.app.auth.entity.TwoFactorAuthEntity;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.EmailChangeTokenRepository;
import com.mannschaft.app.auth.repository.OAuthAccountRepository;
import com.mannschaft.app.auth.repository.RefreshTokenRepository;
import com.mannschaft.app.auth.repository.TwoFactorAuthRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.repository.WebAuthnCredentialRepository;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.auth.service.UserService;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
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
            assertThat(profile.getDisplayName()).isEqualTo("yamada");
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
            // SYSTEM_ADMINチェック: 唯一の管理者ではない
            given(userRoleRepository.countSystemAdmins()).willReturn(2L);

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
                    "sato-jiro", null, "ja", "Asia/Tokyo",
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
            given(userRoleRepository.countSystemAdmins()).willReturn(2L);

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
            given(userRoleRepository.countSystemAdmins()).willReturn(2L);
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
}
