package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.entity.OAuthAccountEntity;
import com.mannschaft.app.auth.entity.OAuthLinkTokenEntity;
import com.mannschaft.app.auth.entity.RefreshTokenEntity;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.entity.WebAuthnCredentialEntity;
import com.mannschaft.app.auth.repository.OAuthAccountRepository;
import com.mannschaft.app.auth.repository.OAuthLinkTokenRepository;
import com.mannschaft.app.auth.repository.RefreshTokenRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.repository.WebAuthnCredentialRepository;
import com.mannschaft.app.auth.dto.OAuthProviderResponse;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * {@link AuthOAuthService} の単体テスト。
 * OAuthプロバイダ連携によるログイン・アカウント連携・連携解除のロジックを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthOAuthService 単体テスト")
class AuthOAuthServiceTest {

    @Mock
    private OAuthAccountRepository oauthAccountRepository;

    @Mock
    private OAuthLinkTokenRepository oauthLinkTokenRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private WebAuthnCredentialRepository webAuthnCredentialRepository;

    @Mock
    private AuthTokenService authTokenService;

    @Mock
    private DomainEventPublisher eventPublisher;

    @Mock
    private EncryptionService encryptionService;

    @InjectMocks
    private AuthOAuthService authOAuthService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long TEST_USER_ID = 1L;
    private static final String TEST_EMAIL = "test@example.com";
    private static final String TEST_IP = "127.0.0.1";
    private static final String TEST_USER_AGENT = "Mozilla/5.0";

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

    private UserEntity createOAuthOnlyUser() {
        return UserEntity.builder()
                .email(TEST_EMAIL)
                .passwordHash(null)
                .lastName("山田")
                .firstName("太郎")
                .displayName("yamada")
                .locale("ja")
                .timezone("Asia/Tokyo")
                .status(UserEntity.UserStatus.ACTIVE)
                .isSearchable(true)
                .build();
    }

    private OAuthAccountEntity createGoogleOAuthAccount() {
        return OAuthAccountEntity.builder()
                .userId(TEST_USER_ID)
                .provider(OAuthAccountEntity.OAuthProvider.GOOGLE)
                .providerUserId("google-user-123")
                .providerEmail(TEST_EMAIL)
                .build();
    }

    private OAuthAccountEntity createLineOAuthAccount() {
        return OAuthAccountEntity.builder()
                .userId(TEST_USER_ID)
                .provider(OAuthAccountEntity.OAuthProvider.LINE)
                .providerUserId("line-user-456")
                .providerEmail(TEST_EMAIL)
                .build();
    }

    // ========================================
    // loginWithOAuth - プロバイダ検証のみテスト可能
    // （fetchOAuthUserInfoはUnsupportedOperationExceptionをthrowする）
    // ========================================

    @Nested
    @DisplayName("loginWithOAuth")
    class LoginWithOAuth {

        @Test
        @DisplayName("異常系: 未サポートプロバイダでAUTH_028例外")
        void loginWithOAuth_未サポートプロバイダ_AUTH028例外() {
            // Given / When / Then
            assertThatThrownBy(() -> authOAuthService.loginWithOAuth(
                    "INVALID_PROVIDER", "auth-code", TEST_IP, TEST_USER_AGENT))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_028"));
        }

        @Test
        @DisplayName("異常系: 有効なプロバイダでもfetchOAuthUserInfoが未実装でUnsupportedOperationException")
        void loginWithOAuth_有効プロバイダ_未実装例外() {
            // Given / When / Then
            // fetchOAuthUserInfoが未実装のためUnsupportedOperationExceptionがスローされる
            assertThatThrownBy(() -> authOAuthService.loginWithOAuth(
                    "GOOGLE", "auth-code", TEST_IP, TEST_USER_AGENT))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    // ========================================
    // confirmOAuthLinkage
    // ========================================

    @Nested
    @DisplayName("confirmOAuthLinkage")
    class ConfirmOAuthLinkage {

        @Test
        @DisplayName("正常系: OAuth連携が完了しトークンが発行される")
        void confirmOAuthLinkage_正常_トークン発行() {
            // Given
            String rawToken = "link-token";
            String tokenHash = "hashed-link-token";
            given(authTokenService.hashToken(rawToken)).willReturn(tokenHash);

            OAuthLinkTokenEntity linkToken = OAuthLinkTokenEntity.builder()
                    .userId(TEST_USER_ID)
                    .provider(OAuthAccountEntity.OAuthProvider.GOOGLE)
                    .providerUserId("google-user-123")
                    .providerEmail(TEST_EMAIL)
                    .tokenHash(tokenHash)
                    .expiresAt(LocalDateTime.now().plusHours(1))
                    .build();
            given(oauthLinkTokenRepository.findByTokenHash(tokenHash)).willReturn(Optional.of(linkToken));
            given(oauthLinkTokenRepository.save(any(OAuthLinkTokenEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));
            given(oauthAccountRepository.save(any(OAuthAccountEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            given(authTokenService.issueAccessToken(any(), any())).willReturn("jwt-access-token");
            given(authTokenService.generateRefreshToken()).willReturn("raw-refresh-token");
            given(authTokenService.hashToken("raw-refresh-token")).willReturn("hashed-refresh-token");
            given(refreshTokenRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));

            // When
            ApiResponse<TokenResponse> response = authOAuthService.confirmOAuthLinkage(rawToken);

            // Then
            assertThat(response.getData().getAccessToken()).isEqualTo("jwt-access-token");
            assertThat(response.getData().getRefreshToken()).isEqualTo("raw-refresh-token");
            verify(oauthAccountRepository).save(any(OAuthAccountEntity.class));
            verify(eventPublisher).publish(any());
        }

        @Test
        @DisplayName("異常系: トークン不在でAUTH_031例外")
        void confirmOAuthLinkage_トークン不在_AUTH031例外() {
            // Given
            String rawToken = "nonexistent-token";
            given(authTokenService.hashToken(rawToken)).willReturn("hashed-nonexistent");
            given(oauthLinkTokenRepository.findByTokenHash("hashed-nonexistent"))
                    .willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> authOAuthService.confirmOAuthLinkage(rawToken))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_031"));
        }

        @Test
        @DisplayName("異常系: トークン期限切れでAUTH_031例外")
        void confirmOAuthLinkage_期限切れ_AUTH031例外() {
            // Given
            String rawToken = "expired-token";
            String tokenHash = "hashed-expired-token";
            given(authTokenService.hashToken(rawToken)).willReturn(tokenHash);

            OAuthLinkTokenEntity expiredToken = OAuthLinkTokenEntity.builder()
                    .userId(TEST_USER_ID)
                    .provider(OAuthAccountEntity.OAuthProvider.GOOGLE)
                    .providerUserId("google-user-123")
                    .providerEmail(TEST_EMAIL)
                    .tokenHash(tokenHash)
                    .expiresAt(LocalDateTime.now().minusHours(1))
                    .build();
            given(oauthLinkTokenRepository.findByTokenHash(tokenHash)).willReturn(Optional.of(expiredToken));

            // When / Then
            assertThatThrownBy(() -> authOAuthService.confirmOAuthLinkage(rawToken))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_031"));
        }

        @Test
        @DisplayName("異常系: 使用済みトークンでAUTH_031例外")
        void confirmOAuthLinkage_使用済み_AUTH031例外() {
            // Given
            String rawToken = "used-token";
            String tokenHash = "hashed-used-token";
            given(authTokenService.hashToken(rawToken)).willReturn(tokenHash);

            OAuthLinkTokenEntity usedToken = OAuthLinkTokenEntity.builder()
                    .userId(TEST_USER_ID)
                    .provider(OAuthAccountEntity.OAuthProvider.GOOGLE)
                    .providerUserId("google-user-123")
                    .providerEmail(TEST_EMAIL)
                    .tokenHash(tokenHash)
                    .expiresAt(LocalDateTime.now().plusHours(1))
                    .build();
            usedToken.markUsed();
            given(oauthLinkTokenRepository.findByTokenHash(tokenHash)).willReturn(Optional.of(usedToken));

            // When / Then
            assertThatThrownBy(() -> authOAuthService.confirmOAuthLinkage(rawToken))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_031"));
        }
    }

    // ========================================
    // getConnectedProviders
    // ========================================

    @Nested
    @DisplayName("getConnectedProviders")
    class GetConnectedProviders {

        @Test
        @DisplayName("正常系: 連携済みプロバイダ一覧が返る")
        void getConnectedProviders_正常_一覧が返る() {
            // Given
            OAuthAccountEntity googleAccount = createGoogleOAuthAccount();
            given(oauthAccountRepository.findByUserId(TEST_USER_ID)).willReturn(List.of(googleAccount));

            // When
            ApiResponse<List<OAuthProviderResponse>> response = authOAuthService.getConnectedProviders(TEST_USER_ID);

            // Then
            assertThat(response.getData()).hasSize(1);
            assertThat(response.getData().get(0).getProvider()).isEqualTo("GOOGLE");
            assertThat(response.getData().get(0).getProviderEmail()).isEqualTo(TEST_EMAIL);
        }

        @Test
        @DisplayName("正常系: 連携なしで空リストが返る")
        void getConnectedProviders_連携なし_空リスト() {
            // Given
            given(oauthAccountRepository.findByUserId(TEST_USER_ID)).willReturn(List.of());

            // When
            ApiResponse<List<OAuthProviderResponse>> response = authOAuthService.getConnectedProviders(TEST_USER_ID);

            // Then
            assertThat(response.getData()).isEmpty();
        }

        @Test
        @DisplayName("正常系: 複数プロバイダ連携時に全件返る")
        void getConnectedProviders_複数連携_全件返る() {
            // Given
            OAuthAccountEntity googleAccount = createGoogleOAuthAccount();
            OAuthAccountEntity lineAccount = createLineOAuthAccount();
            given(oauthAccountRepository.findByUserId(TEST_USER_ID))
                    .willReturn(List.of(googleAccount, lineAccount));

            // When
            ApiResponse<List<OAuthProviderResponse>> response = authOAuthService.getConnectedProviders(TEST_USER_ID);

            // Then
            assertThat(response.getData()).hasSize(2);
        }
    }

    // ========================================
    // disconnectProvider
    // ========================================

    @Nested
    @DisplayName("disconnectProvider")
    class DisconnectProvider {

        @Test
        @DisplayName("正常系: パスワードありユーザーがOAuth連携解除できる")
        void disconnectProvider_パスワードあり_連携解除成功() {
            // Given
            OAuthAccountEntity googleAccount = createGoogleOAuthAccount();
            given(oauthAccountRepository.findByUserId(TEST_USER_ID)).willReturn(List.of(googleAccount));
            given(userRepository.findById(TEST_USER_ID)).willReturn(Optional.of(createActiveUser()));

            // When
            authOAuthService.disconnectProvider(TEST_USER_ID, "GOOGLE");

            // Then
            verify(oauthAccountRepository).delete(googleAccount);
            verify(eventPublisher).publish(any());
        }

        @Test
        @DisplayName("正常系: 他プロバイダ連携ありでOAuth連携解除できる")
        void disconnectProvider_他プロバイダあり_連携解除成功() {
            // Given
            OAuthAccountEntity googleAccount = createGoogleOAuthAccount();
            OAuthAccountEntity lineAccount = createLineOAuthAccount();
            given(oauthAccountRepository.findByUserId(TEST_USER_ID))
                    .willReturn(List.of(googleAccount, lineAccount));
            given(userRepository.findById(TEST_USER_ID)).willReturn(Optional.of(createOAuthOnlyUser()));
            given(webAuthnCredentialRepository.findByUserId(TEST_USER_ID)).willReturn(List.of());

            // When
            authOAuthService.disconnectProvider(TEST_USER_ID, "GOOGLE");

            // Then
            verify(oauthAccountRepository).delete(googleAccount);
            verify(eventPublisher).publish(any());
        }

        @Test
        @DisplayName("正常系: WebAuthnありでOAuth連携解除できる")
        void disconnectProvider_WebAuthnあり_連携解除成功() {
            // Given
            OAuthAccountEntity googleAccount = createGoogleOAuthAccount();
            given(oauthAccountRepository.findByUserId(TEST_USER_ID)).willReturn(List.of(googleAccount));
            given(userRepository.findById(TEST_USER_ID)).willReturn(Optional.of(createOAuthOnlyUser()));

            WebAuthnCredentialEntity webauthnCred = WebAuthnCredentialEntity.builder()
                    .userId(TEST_USER_ID)
                    .credentialId("cred-id-1")
                    .publicKey("pk")
                    .signCount(0L)
                    .build();
            given(webAuthnCredentialRepository.findByUserId(TEST_USER_ID)).willReturn(List.of(webauthnCred));

            // When
            authOAuthService.disconnectProvider(TEST_USER_ID, "GOOGLE");

            // Then
            verify(oauthAccountRepository).delete(googleAccount);
        }

        @Test
        @DisplayName("異常系: 未サポートプロバイダでAUTH_028例外")
        void disconnectProvider_未サポートプロバイダ_AUTH028例外() {
            // Given / When / Then
            assertThatThrownBy(() -> authOAuthService.disconnectProvider(TEST_USER_ID, "INVALID"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_028"));
        }

        @Test
        @DisplayName("異常系: 連携なしプロバイダでAUTH_029例外")
        void disconnectProvider_連携なしプロバイダ_AUTH029例外() {
            // Given
            given(oauthAccountRepository.findByUserId(TEST_USER_ID)).willReturn(List.of());

            // When / Then
            assertThatThrownBy(() -> authOAuthService.disconnectProvider(TEST_USER_ID, "GOOGLE"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_029"));
        }

        @Test
        @DisplayName("異常系: 最後のログイン手段削除でAUTH_030例外")
        void disconnectProvider_最後のログイン手段_AUTH030例外() {
            // Given（パスワードなし、他プロバイダなし、WebAuthnなし）
            OAuthAccountEntity googleAccount = createGoogleOAuthAccount();
            given(oauthAccountRepository.findByUserId(TEST_USER_ID)).willReturn(List.of(googleAccount));
            given(userRepository.findById(TEST_USER_ID)).willReturn(Optional.of(createOAuthOnlyUser()));
            given(webAuthnCredentialRepository.findByUserId(TEST_USER_ID)).willReturn(List.of());

            // When / Then
            assertThatThrownBy(() -> authOAuthService.disconnectProvider(TEST_USER_ID, "GOOGLE"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_030"));
        }

        @Test
        @DisplayName("異常系: ユーザー不在でAUTH_015例外")
        void disconnectProvider_ユーザー不在_AUTH015例外() {
            // Given
            OAuthAccountEntity googleAccount = createGoogleOAuthAccount();
            given(oauthAccountRepository.findByUserId(TEST_USER_ID)).willReturn(List.of(googleAccount));
            given(userRepository.findById(TEST_USER_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> authOAuthService.disconnectProvider(TEST_USER_ID, "GOOGLE"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_015"));
        }
    }
}
