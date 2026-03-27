package com.mannschaft.app.auth.controller;

import com.mannschaft.app.auth.dto.BackupCodesResponse;
import com.mannschaft.app.auth.dto.MessageResponse;
import com.mannschaft.app.auth.dto.TokenResponse;
import com.mannschaft.app.auth.dto.TotpSetupResponse;
import com.mannschaft.app.auth.dto.UpdateWebAuthnCredentialRequest;
import com.mannschaft.app.auth.dto.ValidateTotpLoginRequest;
import com.mannschaft.app.auth.dto.VerifyTotpRequest;
import com.mannschaft.app.auth.dto.WebAuthnCredentialResponse;
import com.mannschaft.app.auth.dto.WebAuthnLoginBeginResponse;
import com.mannschaft.app.auth.dto.WebAuthnLoginCompleteRequest;
import com.mannschaft.app.auth.dto.WebAuthnRegisterBeginResponse;
import com.mannschaft.app.auth.dto.WebAuthnRegisterCompleteRequest;
import com.mannschaft.app.auth.service.Auth2faService;
import com.mannschaft.app.auth.service.AuthOAuthService;
import com.mannschaft.app.auth.service.AuthWebAuthnService;
import com.mannschaft.app.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.withSettings;

/**
 * 認証コントローラー群の単体テスト。
 * SecurityContextHolder を設定してコントローラーを直接呼び出す。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("認証コントローラー 単体テスト")
class AuthControllerTest {

    private static final Long USER_ID = 1L;
    private static final Long CREDENTIAL_ID = 10L;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of())
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ========================================
    // Auth2faController
    // ========================================

    @Nested
    @DisplayName("Auth2faController")
    class TwoFaControllerTests {

        @Mock
        private Auth2faService auth2faService;

        @InjectMocks
        private Auth2faController auth2faController;

        @Test
        @DisplayName("正常系: TOTP設定開始が201で返る")
        void setupTotp_正常_201() {
            // Given
            TotpSetupResponse setup = new TotpSetupResponse("SECRET123", "otpauth://totp/...");
            given(auth2faService.setupTotp(USER_ID)).willReturn(ApiResponse.of(setup));

            // When
            ResponseEntity<ApiResponse<TotpSetupResponse>> response = auth2faController.setupTotp();

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(response.getBody().getData().getSecret()).isEqualTo("SECRET123");
            verify(auth2faService).setupTotp(USER_ID);
        }

        @Test
        @DisplayName("正常系: TOTP検証・有効化が200で返る")
        void verifyTotpSetup_正常_200() {
            // Given
            BackupCodesResponse backupCodes = new BackupCodesResponse(
                    List.of("12345678", "23456789", "34567890", "45678901",
                            "56789012", "67890123", "78901234", "89012345"));
            given(auth2faService.verifyTotpSetup(eq(USER_ID), anyString()))
                    .willReturn(ApiResponse.of(backupCodes));

            VerifyTotpRequest req = new VerifyTotpRequest("123456");

            // When
            ResponseEntity<ApiResponse<BackupCodesResponse>> response = auth2faController.verifyTotpSetup(req);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData().getBackupCodes()).hasSize(8);
        }

        @Test
        @DisplayName("正常系: TOTPログイン検証が200で返る")
        void validateTotp_正常_200() {
            // Given
            TokenResponse token = new TokenResponse("access-token", "refresh-token", 3600);
            given(auth2faService.validateTotp(anyString(), anyString()))
                    .willReturn(ApiResponse.of(token));

            ValidateTotpLoginRequest req = new ValidateTotpLoginRequest("mfa-session-token", "123456");

            // When
            ResponseEntity<ApiResponse<TokenResponse>> response = auth2faController.validateTotp(req);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData().getAccessToken()).isEqualTo("access-token");
        }

        @Test
        @DisplayName("正常系: バックアップコード再生成が200で返る")
        void regenerateBackupCodes_正常_200() {
            // Given
            BackupCodesResponse backupCodes = new BackupCodesResponse(
                    List.of("11111111", "22222222", "33333333", "44444444",
                            "55555555", "66666666", "77777777", "88888888"));
            given(auth2faService.regenerateBackupCodes(USER_ID)).willReturn(ApiResponse.of(backupCodes));

            // When
            ResponseEntity<ApiResponse<BackupCodesResponse>> response = auth2faController.regenerateBackupCodes();

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData().getBackupCodes()).hasSize(8);
        }

        @Test
        @DisplayName("正常系: MFAリカバリー要求が200で返る")
        void requestMfaRecovery_正常_200() {
            // Given
            MessageResponse message = MessageResponse.of("リカバリーメールを送信しました");
            given(auth2faService.requestMfaRecovery("mfa-session-token"))
                    .willReturn(ApiResponse.of(message));

            // When
            ResponseEntity<ApiResponse<MessageResponse>> response =
                    auth2faController.requestMfaRecovery("mfa-session-token");

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData().getMessage()).contains("リカバリーメール");
        }

        @Test
        @DisplayName("正常系: MFAリカバリー確認が200で返る")
        void confirmMfaRecovery_正常_200() {
            // Given
            TokenResponse token = new TokenResponse("recovery-access-token", "refresh-token", 3600);
            given(auth2faService.confirmMfaRecovery("recovery-token"))
                    .willReturn(ApiResponse.of(token));

            // When
            ResponseEntity<ApiResponse<TokenResponse>> response =
                    auth2faController.confirmMfaRecovery("recovery-token");

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData().getAccessToken()).isEqualTo("recovery-access-token");
        }
    }

    // ========================================
    // AuthWebAuthnController
    // ========================================

    @Nested
    @DisplayName("AuthWebAuthnController")
    class WebAuthnControllerTests {

        @Mock
        private AuthWebAuthnService authWebAuthnService;

        @InjectMocks
        private AuthWebAuthnController authWebAuthnController;

        @Test
        @DisplayName("正常系: WebAuthn登録開始が200で返る")
        void beginRegister_正常_200() {
            // Given
            WebAuthnRegisterBeginResponse beginResponse = new WebAuthnRegisterBeginResponse(
                    "challenge-abc", "mannschaft.app", "Mannschaft", USER_ID, "yamada");
            given(authWebAuthnService.beginRegister(USER_ID)).willReturn(ApiResponse.of(beginResponse));

            // When
            ResponseEntity<ApiResponse<WebAuthnRegisterBeginResponse>> response =
                    authWebAuthnController.beginRegister();

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData().getChallenge()).isEqualTo("challenge-abc");
            assertThat(response.getBody().getData().getRpId()).isEqualTo("mannschaft.app");
        }

        @Test
        @DisplayName("正常系: WebAuthn登録完了が201で返る")
        void completeRegister_正常_201() {
            // Given
            MessageResponse message = MessageResponse.of("WebAuthn資格情報を登録しました");
            WebAuthnRegisterCompleteRequest req = new WebAuthnRegisterCompleteRequest(
                    "cred-id", "attestation-obj", "client-data-json",
                    "public-key", "My MacBook", "aaguid-123");
            given(authWebAuthnService.completeRegister(USER_ID, req)).willReturn(ApiResponse.of(message));

            // When
            ResponseEntity<ApiResponse<MessageResponse>> response =
                    authWebAuthnController.completeRegister(req);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(response.getBody().getData().getMessage()).contains("登録しました");
        }

        @Test
        @DisplayName("正常系: WebAuthnログイン開始が200で返る")
        void beginLogin_正常_200() {
            // Given
            WebAuthnLoginBeginResponse beginResponse = new WebAuthnLoginBeginResponse(
                    "login-challenge", "mannschaft.app", List.of("cred-id-1"), 300000L);
            given(authWebAuthnService.beginLogin("test@example.com"))
                    .willReturn(ApiResponse.of(beginResponse));

            // When
            ResponseEntity<ApiResponse<WebAuthnLoginBeginResponse>> response =
                    authWebAuthnController.beginLogin("test@example.com");

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData().getChallenge()).isEqualTo("login-challenge");
            assertThat(response.getBody().getData().getAllowCredentials()).hasSize(1);
        }

        @Test
        @DisplayName("正常系: WebAuthnログイン完了が200で返る")
        void completeLogin_正常_200() {
            // Given
            TokenResponse token = new TokenResponse("webauthn-access", "refresh-token", 3600);
            WebAuthnLoginCompleteRequest req = new WebAuthnLoginCompleteRequest(
                    "cred-id", "auth-data", "client-data", "signature", 6L);
            HttpServletRequest httpRequest = mock(HttpServletRequest.class, withSettings().lenient());
            given(httpRequest.getHeader("X-Forwarded-For")).willReturn(null);
            given(httpRequest.getHeader("X-Real-IP")).willReturn(null);
            given(httpRequest.getRemoteAddr()).willReturn("127.0.0.1");
            given(httpRequest.getHeader("User-Agent")).willReturn("Mozilla/5.0");
            given(authWebAuthnService.completeLogin(eq(req), anyString(), anyString()))
                    .willReturn(ApiResponse.of(token));

            // When
            ResponseEntity<ApiResponse<TokenResponse>> response =
                    authWebAuthnController.completeLogin(req, httpRequest);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData().getAccessToken()).isEqualTo("webauthn-access");
        }

        @Test
        @DisplayName("正常系: WebAuthn資格情報一覧が200で返る")
        void getCredentials_正常_200() {
            // Given
            WebAuthnCredentialResponse cred = new WebAuthnCredentialResponse(
                    CREDENTIAL_ID, "cred-id", "My MacBook", "aaguid-123", null, null);
            given(authWebAuthnService.getCredentials(USER_ID))
                    .willReturn(ApiResponse.of(List.of(cred)));

            // When
            ResponseEntity<ApiResponse<List<WebAuthnCredentialResponse>>> response =
                    authWebAuthnController.getCredentials();

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData()).hasSize(1);
            assertThat(response.getBody().getData().get(0).getDeviceName()).isEqualTo("My MacBook");
        }

        @Test
        @DisplayName("正常系: WebAuthn資格情報デバイス名更新が200で返る")
        void updateCredentialName_正常_200() {
            // Given
            UpdateWebAuthnCredentialRequest req = new UpdateWebAuthnCredentialRequest("New Device");
            WebAuthnCredentialResponse cred = new WebAuthnCredentialResponse(
                    CREDENTIAL_ID, "cred-id", "New Device", "aaguid-123", null, null);
            given(authWebAuthnService.updateCredentialName(USER_ID, CREDENTIAL_ID, req))
                    .willReturn(ApiResponse.of(cred));

            // When
            ResponseEntity<ApiResponse<WebAuthnCredentialResponse>> response =
                    authWebAuthnController.updateCredentialName(CREDENTIAL_ID, req);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData().getDeviceName()).isEqualTo("New Device");
        }

        @Test
        @DisplayName("正常系: WebAuthn資格情報削除が204で返る")
        void deleteCredential_正常_204() {
            // When
            ResponseEntity<Void> response = authWebAuthnController.deleteCredential(CREDENTIAL_ID);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            verify(authWebAuthnService).deleteCredential(USER_ID, CREDENTIAL_ID);
        }
    }

    // ========================================
    // AuthOAuthController
    // ========================================

    @Nested
    @DisplayName("AuthOAuthController")
    class OAuthControllerTests {

        @Mock
        private AuthOAuthService authOAuthService;

        @InjectMocks
        private AuthOAuthController authOAuthController;

        @Test
        @DisplayName("正常系: OAuthログインが200で返る")
        void loginWithOAuth_正常_200() {
            // Given
            TokenResponse token = new TokenResponse("oauth-access", "refresh-token", 3600);
            HttpServletRequest httpRequest = mock(HttpServletRequest.class, withSettings().lenient());
            given(httpRequest.getHeader("X-Forwarded-For")).willReturn(null);
            given(httpRequest.getHeader("X-Real-IP")).willReturn(null);
            given(httpRequest.getRemoteAddr()).willReturn("127.0.0.1");
            given(httpRequest.getHeader("User-Agent")).willReturn("Mozilla/5.0");
            org.mockito.Mockito.doReturn(ApiResponse.of(token))
                    .when(authOAuthService).loginWithOAuth(anyString(), anyString(), anyString(), anyString());

            // When
            ResponseEntity<ApiResponse<?>> response =
                    authOAuthController.loginWithOAuth("GOOGLE", "auth-code", httpRequest);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(authOAuthService).loginWithOAuth(eq("GOOGLE"), eq("auth-code"), anyString(), anyString());
        }

        @Test
        @DisplayName("正常系: OAuth連携確認が200で返る")
        void confirmOAuthLinkage_正常_200() {
            // Given
            TokenResponse token = new TokenResponse("link-access", "refresh-token", 3600);
            org.mockito.Mockito.doReturn(ApiResponse.of(token))
                    .when(authOAuthService).confirmOAuthLinkage("link-token");

            // When
            ResponseEntity<ApiResponse<?>> response =
                    authOAuthController.confirmOAuthLinkage("link-token");

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(authOAuthService).confirmOAuthLinkage("link-token");
        }
    }
}
