package com.mannschaft.app.auth;

import com.mannschaft.app.auth.entity.EmailChangeTokenEntity;
import com.mannschaft.app.auth.entity.OAuthAccountEntity;
import com.mannschaft.app.auth.entity.RefreshTokenEntity;
import com.mannschaft.app.auth.entity.TwoFactorAuthEntity;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.entity.WebAuthnCredentialEntity;
import com.mannschaft.app.auth.repository.EmailChangeTokenRepository;
import com.mannschaft.app.auth.repository.OAuthAccountRepository;
import com.mannschaft.app.auth.repository.RefreshTokenRepository;
import com.mannschaft.app.auth.repository.TwoFactorAuthRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.repository.WebAuthnCredentialRepository;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.auth.service.UserService;
import com.mannschaft.app.auth.dto.ChangePasswordRequest;
import com.mannschaft.app.auth.dto.MessageResponse;
import com.mannschaft.app.auth.dto.RequestEmailChangeRequest;
import com.mannschaft.app.auth.dto.RequestWithdrawalRequest;
import com.mannschaft.app.auth.dto.UserProfileResponse;
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
}
