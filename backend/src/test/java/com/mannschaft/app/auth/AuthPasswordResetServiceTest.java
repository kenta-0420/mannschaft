package com.mannschaft.app.auth;

import com.mannschaft.app.auth.dto.ConfirmPasswordResetRequest;
import com.mannschaft.app.auth.dto.MessageResponse;
import com.mannschaft.app.auth.entity.PasswordResetTokenEntity;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.PasswordResetTokenRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuthPasswordResetService;
import com.mannschaft.app.auth.service.AuthSessionService;
import com.mannschaft.app.auth.service.AuthTokenService;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link AuthPasswordResetService} の単体テスト。
 * AuthService から分離されたパスワードリセットロジックを検証する。
 *
 * <p>セキュリティ重要案件として、AuthServiceTest から該当 nested クラス単位で
 * テスト挙動を完全保存したまま機械的に移送した。logoutAllDevices 呼び出し検証は
 * {@link AuthSessionService} のモック呼び出しを観測する形に置き換えている。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthPasswordResetService 単体テスト")
class AuthPasswordResetServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Mock
    private AuthTokenService authTokenService;

    @Mock
    private AuthSessionService authSessionService;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private DomainEventPublisher eventPublisher;

    @InjectMocks
    private AuthPasswordResetService authPasswordResetService;

    private static final String TEST_EMAIL = "test@example.com";
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
            ApiResponse<MessageResponse> response = authPasswordResetService.requestPasswordReset(TEST_EMAIL, TEST_IP);

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
            ApiResponse<MessageResponse> response = authPasswordResetService.requestPasswordReset(TEST_EMAIL, TEST_IP);

            // Then
            assertThat(response.getData().getMessage()).contains("パスワードリセットメール");
            verify(passwordResetTokenRepository, never()).save(any());
            verify(eventPublisher, never()).publish(any());
        }
    }

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

            // When
            ApiResponse<MessageResponse> response = authPasswordResetService.confirmPasswordReset(req);

            // Then
            assertThat(response.getData().getMessage()).contains("パスワードが正常に変更されました");
            // 全デバイスログアウトは AuthSessionService 経由
            verify(authSessionService).logoutAllDevices(any());
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
            assertThatThrownBy(() -> authPasswordResetService.confirmPasswordReset(req))
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
            assertThatThrownBy(() -> authPasswordResetService.confirmPasswordReset(req))
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
            assertThatThrownBy(() -> authPasswordResetService.confirmPasswordReset(req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_015"));
        }
    }
}
