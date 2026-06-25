package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.entity.WebAuthnCredentialEntity;
import com.mannschaft.app.auth.repository.RefreshTokenRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.repository.WebAuthnCredentialRepository;
import com.mannschaft.app.auth.dto.UpdateWebAuthnCredentialRequest;
import com.mannschaft.app.auth.dto.WebAuthnCredentialResponse;
import com.mannschaft.app.auth.dto.WebAuthnLoginBeginResponse;
import com.mannschaft.app.auth.dto.WebAuthnLoginCompleteRequest;
import com.mannschaft.app.auth.dto.WebAuthnReauthenticateBeginResponse;
import com.mannschaft.app.auth.dto.WebAuthnReauthenticateCompleteRequest;
import com.mannschaft.app.auth.dto.WebAuthnReauthenticateCompleteResponse;
import com.mannschaft.app.auth.dto.WebAuthnRegisterBeginResponse;
import com.mannschaft.app.auth.dto.WebAuthnRegisterCompleteRequest;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.DomainEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * {@link AuthWebAuthnService} の単体テスト。
 * WebAuthn資格情報の登録・ログイン・管理のロジックを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthWebAuthnService 単体テスト")
class AuthWebAuthnServiceTest {

    @Mock
    private WebAuthnCredentialRepository webAuthnCredentialRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private AuthTokenService authTokenService;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private DomainEventPublisher eventPublisher;

    // 認可基盤完全根治 Phase 1: トークン発行時に roles を解決するヘルパ。
    @Mock
    private RoleClaimResolver roleClaimResolver;

    @InjectMocks
    private AuthWebAuthnService authWebAuthnService;

    @BeforeEach
    void setUp() {
        // @Value フィールドは純 Mockito テストでは処理されないため手動で注入する
        ReflectionTestUtils.setField(authWebAuthnService, "rpId", "mannschaft.app");
        ReflectionTestUtils.setField(authWebAuthnService, "rpOrigin", "https://mannschaft.app");
    }

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long TEST_USER_ID = 1L;
    private static final Long TEST_CREDENTIAL_ID = 10L;
    private static final String TEST_EMAIL = "test@example.com";
    private static final String TEST_IP = "127.0.0.1";
    private static final String TEST_USER_AGENT = "Mozilla/5.0";
    private static final String TEST_WEBAUTHN_CREDENTIAL_ID = "credential-id-base64";
    private static final String TEST_PUBLIC_KEY = "public-key-base64";
    private static final String TEST_DEVICE_NAME = "My MacBook";

    private UserEntity createActiveUser() {
        UserEntity user = UserEntity.builder()
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
        // BaseEntity.id は @Builder で渡せないためリフレクションでセットする
        ReflectionTestUtils.setField(user, "id", TEST_USER_ID);
        return user;
    }

    private WebAuthnCredentialEntity createWebAuthnCredential() {
        return WebAuthnCredentialEntity.builder()
                .id(TEST_CREDENTIAL_ID)
                .userId(TEST_USER_ID)
                .credentialId(TEST_WEBAUTHN_CREDENTIAL_ID)
                .publicKey(TEST_PUBLIC_KEY)
                .signCount(5L)
                .deviceName(TEST_DEVICE_NAME)
                .aaguid("aaguid-123")
                .build();
    }

    // ========================================
    // beginRegister
    // ========================================

    @Nested
    @DisplayName("beginRegister")
    class BeginRegister {

        @Test
        @DisplayName("正常系: チャレンジが生成されレスポンスが返る")
        void beginRegister_正常_チャレンジが返る() {
            // Given
            given(userRepository.findById(TEST_USER_ID)).willReturn(Optional.of(createActiveUser()));
            given(redisTemplate.opsForValue()).willReturn(valueOperations);

            // When
            ApiResponse<WebAuthnRegisterBeginResponse> response = authWebAuthnService.beginRegister(TEST_USER_ID);

            // Then
            assertThat(response.getData().getChallenge()).isNotNull();
            assertThat(response.getData().getRpId()).isEqualTo("mannschaft.app");
            assertThat(response.getData().getRpName()).isEqualTo("Mannschaft");
            assertThat(response.getData().getUserId()).isEqualTo(TEST_USER_ID);
            assertThat(response.getData().getUserDisplayName()).isEqualTo("山田 太郎");
            verify(valueOperations).set(anyString(), anyString(), anyLong(), any());
        }

        @Test
        @DisplayName("異常系: ユーザー不在でAUTH_015例外")
        void beginRegister_ユーザー不在_AUTH015例外() {
            // Given
            given(userRepository.findById(TEST_USER_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> authWebAuthnService.beginRegister(TEST_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_015"));
        }
    }

    // ========================================
    // completeRegister
    // ========================================

    @Nested
    @DisplayName("completeRegister")
    class CompleteRegister {

        @Test
        @DisplayName("異常系: チャレンジ不在でAUTH_027例外")
        void completeRegister_チャレンジ不在_AUTH027例外() {
            // Given
            WebAuthnRegisterCompleteRequest req = new WebAuthnRegisterCompleteRequest(
                    TEST_WEBAUTHN_CREDENTIAL_ID, "attestation-obj", "client-data-json",
                    TEST_PUBLIC_KEY, TEST_DEVICE_NAME, "aaguid-123");

            given(redisTemplate.opsForValue()).willReturn(valueOperations);
            given(valueOperations.get(anyString())).willReturn(null);

            // When / Then
            assertThatThrownBy(() -> authWebAuthnService.completeRegister(TEST_USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_027"));
        }
    }

    // ========================================
    // beginLogin
    // ========================================

    @Nested
    @DisplayName("beginLogin")
    class BeginLogin {

        @Test
        @DisplayName("正常系: チャレンジとcredentialリストが返る")
        void beginLogin_正常_チャレンジとCredentialリスト() {
            // Given
            UserEntity user = createActiveUser();
            given(userRepository.findByEmail(TEST_EMAIL)).willReturn(Optional.of(user));

            WebAuthnCredentialEntity credential = createWebAuthnCredential();
            given(webAuthnCredentialRepository.findByUserId(any())).willReturn(List.of(credential));
            given(redisTemplate.opsForValue()).willReturn(valueOperations);

            // When
            ApiResponse<WebAuthnLoginBeginResponse> response = authWebAuthnService.beginLogin(TEST_EMAIL);

            // Then
            assertThat(response.getData().getChallenge()).isNotNull();
            assertThat(response.getData().getRpId()).isEqualTo("mannschaft.app");
            assertThat(response.getData().getAllowCredentials()).hasSize(1);
            assertThat(response.getData().getAllowCredentials().get(0)).isEqualTo(TEST_WEBAUTHN_CREDENTIAL_ID);
            assertThat(response.getData().getTimeout()).isEqualTo(300000L);
            verify(valueOperations).set(anyString(), anyString(), anyLong(), any());
        }

        @Test
        @DisplayName("異常系: ユーザー不在でAUTH_015例外")
        void beginLogin_ユーザー不在_AUTH015例外() {
            // Given
            given(userRepository.findByEmail(TEST_EMAIL)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> authWebAuthnService.beginLogin(TEST_EMAIL))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_015"));
        }

        @Test
        @DisplayName("異常系: WebAuthn資格情報なしでAUTH_024例外")
        void beginLogin_資格情報なし_AUTH024例外() {
            // Given
            UserEntity user = createActiveUser();
            given(userRepository.findByEmail(TEST_EMAIL)).willReturn(Optional.of(user));
            given(webAuthnCredentialRepository.findByUserId(any())).willReturn(List.of());

            // When / Then
            assertThatThrownBy(() -> authWebAuthnService.beginLogin(TEST_EMAIL))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_024"));
        }

        @Test
        @DisplayName("正常系: 複数credential登録時に全件返る")
        void beginLogin_複数Credential_全件返る() {
            // Given
            UserEntity user = createActiveUser();
            given(userRepository.findByEmail(TEST_EMAIL)).willReturn(Optional.of(user));

            WebAuthnCredentialEntity cred1 = createWebAuthnCredential();
            WebAuthnCredentialEntity cred2 = WebAuthnCredentialEntity.builder()
                    .id(11L)
                    .userId(TEST_USER_ID)
                    .credentialId("credential-id-2")
                    .publicKey("pk-2")
                    .signCount(0L)
                    .deviceName("My iPhone")
                    .build();
            given(webAuthnCredentialRepository.findByUserId(any())).willReturn(List.of(cred1, cred2));
            given(redisTemplate.opsForValue()).willReturn(valueOperations);

            // When
            ApiResponse<WebAuthnLoginBeginResponse> response = authWebAuthnService.beginLogin(TEST_EMAIL);

            // Then
            assertThat(response.getData().getAllowCredentials()).hasSize(2);
        }
    }

    // ========================================
    // completeLogin
    // ========================================

    @Nested
    @DisplayName("completeLogin")
    class CompleteLogin {

        @Test
        @DisplayName("異常系: credential_id不在でAUTH_024例外")
        void completeLogin_credentialId不在_AUTH024例外() {
            // Given
            WebAuthnLoginCompleteRequest req = new WebAuthnLoginCompleteRequest(
                    "nonexistent-cred-id", "auth-data", "client-data", "signature", 6L);
            given(webAuthnCredentialRepository.findByCredentialId("nonexistent-cred-id"))
                    .willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> authWebAuthnService.completeLogin(req, TEST_IP, TEST_USER_AGENT))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_024"));
        }

        @Test
        @DisplayName("異常系: チャレンジ不在でAUTH_027例外")
        void completeLogin_チャレンジ不在_AUTH027例外() {
            // Given
            WebAuthnLoginCompleteRequest req = new WebAuthnLoginCompleteRequest(
                    TEST_WEBAUTHN_CREDENTIAL_ID, "auth-data", "client-data", "signature", 6L);
            WebAuthnCredentialEntity credential = createWebAuthnCredential();
            given(webAuthnCredentialRepository.findByCredentialId(TEST_WEBAUTHN_CREDENTIAL_ID))
                    .willReturn(Optional.of(credential));
            given(redisTemplate.opsForValue()).willReturn(valueOperations);
            given(valueOperations.get(anyString())).willReturn(null);

            // When / Then
            assertThatThrownBy(() -> authWebAuthnService.completeLogin(req, TEST_IP, TEST_USER_AGENT))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_027"));
        }
    }

    // ========================================
    // getCredentials
    // ========================================

    @Nested
    @DisplayName("getCredentials")
    class GetCredentials {

        @Test
        @DisplayName("正常系: 登録済み資格情報一覧が返る")
        void getCredentials_正常_一覧が返る() {
            // Given
            WebAuthnCredentialEntity credential = createWebAuthnCredential();
            given(webAuthnCredentialRepository.findByUserId(TEST_USER_ID)).willReturn(List.of(credential));

            // When
            ApiResponse<List<WebAuthnCredentialResponse>> response =
                    authWebAuthnService.getCredentials(TEST_USER_ID);

            // Then
            assertThat(response.getData()).hasSize(1);
            assertThat(response.getData().get(0).getId()).isEqualTo(TEST_CREDENTIAL_ID);
            assertThat(response.getData().get(0).getCredentialId()).isEqualTo(TEST_WEBAUTHN_CREDENTIAL_ID);
            assertThat(response.getData().get(0).getDeviceName()).isEqualTo(TEST_DEVICE_NAME);
            assertThat(response.getData().get(0).getAaguid()).isEqualTo("aaguid-123");
        }

        @Test
        @DisplayName("正常系: 資格情報なしで空リストが返る")
        void getCredentials_なし_空リスト() {
            // Given
            given(webAuthnCredentialRepository.findByUserId(TEST_USER_ID)).willReturn(List.of());

            // When
            ApiResponse<List<WebAuthnCredentialResponse>> response =
                    authWebAuthnService.getCredentials(TEST_USER_ID);

            // Then
            assertThat(response.getData()).isEmpty();
        }
    }

    // ========================================
    // updateCredentialName
    // ========================================

    @Nested
    @DisplayName("updateCredentialName")
    class UpdateCredentialName {

        @Test
        @DisplayName("正常系: デバイス名が更新される")
        void updateCredentialName_正常_デバイス名更新() {
            // Given
            WebAuthnCredentialEntity credential = createWebAuthnCredential();
            given(webAuthnCredentialRepository.findById(TEST_CREDENTIAL_ID))
                    .willReturn(Optional.of(credential));
            given(webAuthnCredentialRepository.save(any(WebAuthnCredentialEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            UpdateWebAuthnCredentialRequest req = new UpdateWebAuthnCredentialRequest("New Device Name");

            // When
            ApiResponse<WebAuthnCredentialResponse> response =
                    authWebAuthnService.updateCredentialName(TEST_USER_ID, TEST_CREDENTIAL_ID, req);

            // Then
            assertThat(response.getData().getDeviceName()).isEqualTo("New Device Name");
            verify(webAuthnCredentialRepository).save(any(WebAuthnCredentialEntity.class));
        }

        @Test
        @DisplayName("異常系: 資格情報不在でAUTH_024例外")
        void updateCredentialName_不在_AUTH024例外() {
            // Given
            given(webAuthnCredentialRepository.findById(TEST_CREDENTIAL_ID)).willReturn(Optional.empty());
            UpdateWebAuthnCredentialRequest req = new UpdateWebAuthnCredentialRequest("New Name");

            // When / Then
            assertThatThrownBy(() -> authWebAuthnService.updateCredentialName(
                    TEST_USER_ID, TEST_CREDENTIAL_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_024"));
        }

        @Test
        @DisplayName("異常系: 他ユーザーの資格情報でAUTH_024例外")
        void updateCredentialName_他ユーザー_AUTH024例外() {
            // Given
            WebAuthnCredentialEntity credential = createWebAuthnCredential();
            given(webAuthnCredentialRepository.findById(TEST_CREDENTIAL_ID))
                    .willReturn(Optional.of(credential));
            UpdateWebAuthnCredentialRequest req = new UpdateWebAuthnCredentialRequest("New Name");

            Long otherUserId = 999L;

            // When / Then
            assertThatThrownBy(() -> authWebAuthnService.updateCredentialName(
                    otherUserId, TEST_CREDENTIAL_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_024"));
        }
    }

    // ========================================
    // deleteCredential
    // ========================================

    @Nested
    @DisplayName("deleteCredential")
    class DeleteCredential {

        @Test
        @DisplayName("正常系: 資格情報が削除される")
        void deleteCredential_正常_削除成功() {
            // Given
            WebAuthnCredentialEntity credential = createWebAuthnCredential();
            given(webAuthnCredentialRepository.findById(TEST_CREDENTIAL_ID))
                    .willReturn(Optional.of(credential));

            // When
            authWebAuthnService.deleteCredential(TEST_USER_ID, TEST_CREDENTIAL_ID);

            // Then
            verify(webAuthnCredentialRepository).delete(credential);
        }

        @Test
        @DisplayName("異常系: 資格情報不在でAUTH_024例外")
        void deleteCredential_不在_AUTH024例外() {
            // Given
            given(webAuthnCredentialRepository.findById(TEST_CREDENTIAL_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> authWebAuthnService.deleteCredential(TEST_USER_ID, TEST_CREDENTIAL_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_024"));
        }

        @Test
        @DisplayName("異常系: 他ユーザーの資格情報でAUTH_024例外")
        void deleteCredential_他ユーザー_AUTH024例外() {
            // Given
            WebAuthnCredentialEntity credential = createWebAuthnCredential();
            given(webAuthnCredentialRepository.findById(TEST_CREDENTIAL_ID))
                    .willReturn(Optional.of(credential));

            Long otherUserId = 999L;

            // When / Then
            assertThatThrownBy(() -> authWebAuthnService.deleteCredential(otherUserId, TEST_CREDENTIAL_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_024"));
        }
    }

    // ========================================
    // F18 提示モード追加保護: WebAuthn 再認証（設計書 §9.6 / POINT_CARD_009）
    // ========================================

    @Nested
    @DisplayName("beginReauthenticate (F18 §9.6)")
    class BeginReauthenticate {

        @Test
        @DisplayName("正常系: 認証済みユーザーのチャレンジが reauth プレフィックスで保存される")
        void beginReauthenticate_正常_チャレンジ保存() {
            // Given
            given(userRepository.findById(TEST_USER_ID)).willReturn(Optional.of(createActiveUser()));
            WebAuthnCredentialEntity credential = createWebAuthnCredential();
            given(webAuthnCredentialRepository.findByUserId(TEST_USER_ID))
                    .willReturn(List.of(credential));
            given(redisTemplate.opsForValue()).willReturn(valueOperations);

            // When
            ApiResponse<WebAuthnReauthenticateBeginResponse> response =
                    authWebAuthnService.beginReauthenticate(TEST_USER_ID);

            // Then
            assertThat(response.getData().getChallenge()).isNotNull().isNotBlank();
            assertThat(response.getData().getRpId()).isEqualTo("mannschaft.app");
            assertThat(response.getData().getAllowCredentials()).containsExactly(TEST_WEBAUTHN_CREDENTIAL_ID);
            assertThat(response.getData().getUserId()).isEqualTo(TEST_USER_ID);
            assertThat(response.getData().getTimeout()).isEqualTo(60_000L);

            // チャレンジ保存先が reauth プレフィックスであること
            org.mockito.ArgumentCaptor<String> keyCaptor =
                    org.mockito.ArgumentCaptor.forClass(String.class);
            verify(valueOperations).set(keyCaptor.capture(), anyString(), anyLong(), any());
            assertThat(keyCaptor.getValue())
                    .startsWith("mannschaft:auth:webauthn_reauth_challenge:")
                    .contains(String.valueOf(TEST_USER_ID));
        }

        @Test
        @DisplayName("異常系: ユーザー不在で AUTH_015")
        void beginReauthenticate_ユーザー不在_AUTH015() {
            given(userRepository.findById(TEST_USER_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> authWebAuthnService.beginReauthenticate(TEST_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_015"));
        }

        @Test
        @DisplayName("異常系: credential 未登録で AUTH_024")
        void beginReauthenticate_credential未登録_AUTH024() {
            given(userRepository.findById(TEST_USER_ID)).willReturn(Optional.of(createActiveUser()));
            given(webAuthnCredentialRepository.findByUserId(TEST_USER_ID)).willReturn(List.of());

            assertThatThrownBy(() -> authWebAuthnService.beginReauthenticate(TEST_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_024"));
        }
    }

    @Nested
    @DisplayName("completeReauthenticate (F18 §9.6)")
    class CompleteReauthenticate {

        @Test
        @DisplayName("異常系: チャレンジ不在で AUTH_027")
        void completeReauthenticate_チャレンジ不在_AUTH027() {
            WebAuthnReauthenticateCompleteRequest req = new WebAuthnReauthenticateCompleteRequest(
                    TEST_WEBAUTHN_CREDENTIAL_ID, "auth-data", "client-data", "signature", 6L);
            given(redisTemplate.opsForValue()).willReturn(valueOperations);
            given(valueOperations.get(anyString())).willReturn(null);

            assertThatThrownBy(() -> authWebAuthnService.completeReauthenticate(TEST_USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_027"));
        }

        @Test
        @DisplayName("異常系: credential_id 未登録で AUTH_024")
        void completeReauthenticate_credential未登録_AUTH024() {
            WebAuthnReauthenticateCompleteRequest req = new WebAuthnReauthenticateCompleteRequest(
                    "nonexistent-cred-id", "auth-data", "client-data", "signature", 6L);
            given(redisTemplate.opsForValue()).willReturn(valueOperations);
            given(valueOperations.get(anyString())).willReturn("stored-challenge");
            given(webAuthnCredentialRepository.findByCredentialId("nonexistent-cred-id"))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> authWebAuthnService.completeReauthenticate(TEST_USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_024"));
        }

        @Test
        @DisplayName("異常系: 他人 credential 指定で AUTH_024 (IDOR 防止)")
        void completeReauthenticate_他人credential_AUTH024() {
            WebAuthnReauthenticateCompleteRequest req = new WebAuthnReauthenticateCompleteRequest(
                    TEST_WEBAUTHN_CREDENTIAL_ID, "auth-data", "client-data", "signature", 6L);
            WebAuthnCredentialEntity credential = createWebAuthnCredential(); // userId=TEST_USER_ID
            given(redisTemplate.opsForValue()).willReturn(valueOperations);
            given(valueOperations.get(anyString())).willReturn("stored-challenge");
            given(webAuthnCredentialRepository.findByCredentialId(TEST_WEBAUTHN_CREDENTIAL_ID))
                    .willReturn(Optional.of(credential));

            // 他人 (otherUserId) として呼ぶ
            Long otherUserId = 999L;
            assertThatThrownBy(() -> authWebAuthnService.completeReauthenticate(otherUserId, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_024"));
        }
    }

    @Nested
    @DisplayName("isReauthenticatedRecently / consumeReauthentication (F18 §9.6)")
    class ReauthFlag {

        @Test
        @DisplayName("正常系: フラグキー存在で true、削除後 false")
        void isReauthenticatedRecently_キー存在で真() {
            given(redisTemplate.hasKey(anyString())).willReturn(Boolean.TRUE);
            assertThat(authWebAuthnService.isReauthenticatedRecently(TEST_USER_ID)).isTrue();
        }

        @Test
        @DisplayName("正常系: キー不在で false")
        void isReauthenticatedRecently_キー不在で偽() {
            given(redisTemplate.hasKey(anyString())).willReturn(Boolean.FALSE);
            assertThat(authWebAuthnService.isReauthenticatedRecently(TEST_USER_ID)).isFalse();
        }

        @Test
        @DisplayName("正常系: null userId は false")
        void isReauthenticatedRecently_nullUserId_偽() {
            assertThat(authWebAuthnService.isReauthenticatedRecently(null)).isFalse();
        }

        @Test
        @DisplayName("正常系: consumeReauthentication で reauth_verified キーを削除")
        void consumeReauthentication_キー削除() {
            authWebAuthnService.consumeReauthentication(TEST_USER_ID);

            org.mockito.ArgumentCaptor<String> keyCaptor =
                    org.mockito.ArgumentCaptor.forClass(String.class);
            verify(redisTemplate).delete(keyCaptor.capture());
            assertThat(keyCaptor.getValue())
                    .startsWith("mannschaft:auth:webauthn_reauth_verified:")
                    .contains(String.valueOf(TEST_USER_ID));
        }

        @Test
        @DisplayName("正常系: null userId なら何もしない")
        void consumeReauthentication_nullUserId_noop() {
            authWebAuthnService.consumeReauthentication(null);
            verify(redisTemplate, never()).delete(anyString());
        }

        @Test
        @DisplayName("正常系: 一連の流れ — フラグ書き込み→isReauth=true→consume→false")
        void reauthFlow_書き込み消費の流れ() {
            // 1. consume 前の状態: キー存在
            given(redisTemplate.hasKey(anyString())).willReturn(Boolean.TRUE);
            assertThat(authWebAuthnService.isReauthenticatedRecently(TEST_USER_ID)).isTrue();

            // 2. consume
            authWebAuthnService.consumeReauthentication(TEST_USER_ID);
            verify(redisTemplate).delete(anyString());

            // 3. consume 後の状態: キー不在
            given(redisTemplate.hasKey(anyString())).willReturn(Boolean.FALSE);
            assertThat(authWebAuthnService.isReauthenticatedRecently(TEST_USER_ID)).isFalse();
        }

        // 補助: WebAuthnReauthenticateCompleteResponse の DTO がコンパイル時依存となっていること
        // を保証するためのスモークテスト。
        @Test
        @DisplayName("DTO スモーク: WebAuthnReauthenticateCompleteResponse の verifiedUntil 保持")
        void completeReauthResponse_DTO_スモーク() {
            java.time.OffsetDateTime now = java.time.OffsetDateTime.now();
            WebAuthnReauthenticateCompleteResponse dto = new WebAuthnReauthenticateCompleteResponse(now);
            assertThat(dto.getVerifiedUntil()).isEqualTo(now);
        }
    }
}
