package com.mannschaft.app.auth;

import com.mannschaft.app.auth.dto.LoginHistoryResponse;
import com.mannschaft.app.auth.dto.OAuthProviderResponse;
import com.mannschaft.app.auth.dto.SessionResponse;
import com.mannschaft.app.auth.dto.UserProfileResponse;
import com.mannschaft.app.auth.dto.WebAuthnCredentialResponse;
import com.mannschaft.app.auth.entity.AuditLogEntity;
import com.mannschaft.app.auth.entity.OAuthAccountEntity;
import com.mannschaft.app.auth.entity.RefreshTokenEntity;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.entity.WebAuthnCredentialEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.lang.reflect.Field;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AuthMapper} の単体テスト。
 * MapStruct生成実装によるEntity→DTO変換を検証する。
 */
@DisplayName("AuthMapper 単体テスト")
class AuthMapperTest {

    private final AuthMapper mapper = Mappers.getMapper(AuthMapper.class);

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 3, 27, 10, 0);

    // ========================================
    // UserEntity → UserProfileResponse
    // ========================================

    @Nested
    @DisplayName("toUserProfileResponse")
    class ToUserProfileResponse {

        @Test
        @DisplayName("正常系: ACTIVEユーザーがUserProfileResponseに変換される")
        void 変換_ACTIVEユーザー_statusがString変換() {
            // Given
            UserEntity user = UserEntity.builder()
                    .email("user@example.com")
                    .passwordHash("$2a$12$hash")
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

            // When
            UserProfileResponse response = mapper.toUserProfileResponse(user);

            // Then
            assertThat(response.getEmail()).isEqualTo("user@example.com");
            assertThat(response.getLastName()).isEqualTo("山田");
            assertThat(response.getFirstName()).isEqualTo("太郎");
            assertThat(response.getLastNameKana()).isEqualTo("ヤマダ");
            assertThat(response.getFirstNameKana()).isEqualTo("タロウ");
            assertThat(response.getDisplayName()).isEqualTo("yamada");
            assertThat(response.getIsSearchable()).isTrue();
            assertThat(response.getLocale()).isEqualTo("ja");
            assertThat(response.getTimezone()).isEqualTo("Asia/Tokyo");
            assertThat(response.getStatus()).isEqualTo("ACTIVE");
            // ignore フィールドはデフォルト値
            assertThat(response.isHasPassword()).isFalse();
            assertThat(response.is2faEnabled()).isFalse();
            assertThat(response.getWebauthnCount()).isZero();
            assertThat(response.getOauthProviders()).isNull();
        }

        @Test
        @DisplayName("正常系: PENDING_VERIFICATIONステータスが変換される")
        void 変換_PENDING_VERIFICATIONユーザー_statusが変換() {
            // Given
            UserEntity user = UserEntity.builder()
                    .email("pending@example.com")
                    .passwordHash("$2a$12$hash")
                    .lastName("鈴木")
                    .firstName("花子")
                    .displayName("suzuki")
                    .isSearchable(false)
                    .locale("en")
                    .timezone("UTC")
                    .status(UserEntity.UserStatus.PENDING_VERIFICATION)
                    .build();

            // When
            UserProfileResponse response = mapper.toUserProfileResponse(user);

            // Then
            assertThat(response.getStatus()).isEqualTo("PENDING_VERIFICATION");
            assertThat(response.getEmail()).isEqualTo("pending@example.com");
            assertThat(response.getIsSearchable()).isFalse();
        }

        @Test
        @DisplayName("正常系: statusがnullの場合nullが返る")
        void 変換_statusNull_nullが返る() {
            // Given
            UserEntity user = UserEntity.builder()
                    .email("test@example.com")
                    .passwordHash("hash")
                    .lastName("田中")
                    .firstName("次郎")
                    .displayName("tanaka")
                    .isSearchable(true)
                    .locale("ja")
                    .timezone("Asia/Tokyo")
                    .status(null)
                    .build();

            // When
            UserProfileResponse response = mapper.toUserProfileResponse(user);

            // Then
            assertThat(response.getStatus()).isNull();
        }

        @Test
        @DisplayName("正常系: FROZENステータスが変換される")
        void 変換_FROZENユーザー_statusが変換() {
            // Given
            UserEntity user = UserEntity.builder()
                    .email("frozen@example.com")
                    .passwordHash(null)
                    .lastName("佐藤")
                    .firstName("三郎")
                    .displayName("sato")
                    .isSearchable(true)
                    .locale("ja")
                    .timezone("Asia/Tokyo")
                    .status(UserEntity.UserStatus.FROZEN)
                    .build();

            // When
            UserProfileResponse response = mapper.toUserProfileResponse(user);

            // Then
            assertThat(response.getStatus()).isEqualTo("FROZEN");
            assertThat(response.getDisplayName()).isEqualTo("sato");
        }
    }

    // ========================================
    // RefreshTokenEntity → SessionResponse
    // ========================================

    @Nested
    @DisplayName("toSessionResponse")
    class ToSessionResponse {

        @Test
        @DisplayName("正常系: エンティティがSessionResponseに変換される")
        void 変換_正常_フィールドが正しくマップされる() {
            // Given
            RefreshTokenEntity entity = RefreshTokenEntity.builder()
                    .userId(1L)
                    .tokenHash("hashedToken123")
                    .rememberMe(true)
                    .ipAddress("192.168.1.1")
                    .userAgent("Mozilla/5.0")
                    .expiresAt(NOW.plusDays(7))
                    .build();

            // When
            SessionResponse response = mapper.toSessionResponse(entity);

            // Then
            assertThat(response.getIpAddress()).isEqualTo("192.168.1.1");
            assertThat(response.getUserAgent()).isEqualTo("Mozilla/5.0");
            assertThat(response.isRememberMe()).isTrue();
            // isCurrent は ignore なのでデフォルト値(false)
            assertThat(response.isCurrent()).isFalse();
        }

        @Test
        @DisplayName("正常系: rememberMe=falseの場合も変換される")
        void 変換_rememberMeFalse_falseが返る() {
            // Given
            RefreshTokenEntity entity = RefreshTokenEntity.builder()
                    .userId(2L)
                    .tokenHash("anotherHash")
                    .rememberMe(false)
                    .ipAddress("10.0.0.1")
                    .userAgent("Chrome/100")
                    .expiresAt(NOW.plusDays(1))
                    .build();

            // When
            SessionResponse response = mapper.toSessionResponse(entity);

            // Then
            assertThat(response.isRememberMe()).isFalse();
            assertThat(response.getIpAddress()).isEqualTo("10.0.0.1");
        }

        @Test
        @DisplayName("正常系: IPアドレスなしの場合nullが返る")
        void 変換_IPなし_nullが返る() {
            // Given
            RefreshTokenEntity entity = RefreshTokenEntity.builder()
                    .userId(1L)
                    .tokenHash("hash")
                    .rememberMe(false)
                    .expiresAt(NOW.plusDays(1))
                    .build();

            // When
            SessionResponse response = mapper.toSessionResponse(entity);

            // Then
            assertThat(response.getIpAddress()).isNull();
        }
    }

    // ========================================
    // WebAuthnCredentialEntity → WebAuthnCredentialResponse
    // ========================================

    @Nested
    @DisplayName("toWebAuthnCredentialResponse")
    class ToWebAuthnCredentialResponse {

        @Test
        @DisplayName("正常系: エンティティがWebAuthnCredentialResponseに変換される")
        void 変換_正常_フィールドが正しくマップされる() throws Exception {
            // Given
            WebAuthnCredentialEntity entity = WebAuthnCredentialEntity.builder()
                    .userId(1L)
                    .credentialId("cred-id-base64")
                    .publicKey("public-key-data")
                    .signCount(10L)
                    .deviceName("My MacBook")
                    .aaguid("aaguid-123")
                    .build();

            Field idField = entity.getClass().getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(entity, 50L);

            // When
            WebAuthnCredentialResponse response = mapper.toWebAuthnCredentialResponse(entity);

            // Then
            assertThat(response.getId()).isEqualTo(50L);
            assertThat(response.getCredentialId()).isEqualTo("cred-id-base64");
            assertThat(response.getDeviceName()).isEqualTo("My MacBook");
            assertThat(response.getAaguid()).isEqualTo("aaguid-123");
        }

        @Test
        @DisplayName("正常系: デバイス名なしの場合nullが返る")
        void 変換_デバイス名なし_nullが返る() {
            // Given
            WebAuthnCredentialEntity entity = WebAuthnCredentialEntity.builder()
                    .userId(1L)
                    .credentialId("cred-id-base64")
                    .publicKey("public-key-data")
                    .signCount(0L)
                    .build();

            // When
            WebAuthnCredentialResponse response = mapper.toWebAuthnCredentialResponse(entity);

            // Then
            assertThat(response.getDeviceName()).isNull();
            assertThat(response.getAaguid()).isNull();
        }
    }

    // ========================================
    // OAuthAccountEntity → OAuthProviderResponse
    // ========================================

    @Nested
    @DisplayName("toOAuthProviderResponse")
    class ToOAuthProviderResponse {

        @Test
        @DisplayName("正常系: GoogleプロバイダーエンティティがOAuthProviderResponseに変換される")
        void 変換_Google_providerがString変換() throws Exception {
            // Given
            OAuthAccountEntity entity = OAuthAccountEntity.builder()
                    .userId(1L)
                    .provider(OAuthAccountEntity.OAuthProvider.GOOGLE)
                    .providerUserId("google-user-id-123")
                    .providerEmail("user@gmail.com")
                    .build();

            // When
            OAuthProviderResponse response = mapper.toOAuthProviderResponse(entity);

            // Then
            assertThat(response.getProvider()).isEqualTo("GOOGLE");
            assertThat(response.getProviderEmail()).isEqualTo("user@gmail.com");
        }

        @Test
        @DisplayName("正常系: LINEプロバイダーが正しく変換される")
        void 変換_LINE_providerがString変換() {
            // Given
            OAuthAccountEntity entity = OAuthAccountEntity.builder()
                    .userId(2L)
                    .provider(OAuthAccountEntity.OAuthProvider.LINE)
                    .providerUserId("line-user-id-456")
                    .providerEmail(null)
                    .build();

            // When
            OAuthProviderResponse response = mapper.toOAuthProviderResponse(entity);

            // Then
            assertThat(response.getProvider()).isEqualTo("LINE");
            assertThat(response.getProviderEmail()).isNull();
        }

        @Test
        @DisplayName("正常系: APPLEプロバイダーが正しく変換される")
        void 変換_APPLE_providerがString変換() {
            // Given
            OAuthAccountEntity entity = OAuthAccountEntity.builder()
                    .userId(3L)
                    .provider(OAuthAccountEntity.OAuthProvider.APPLE)
                    .providerUserId("apple-user-id-789")
                    .providerEmail("user@privaterelay.appleid.com")
                    .build();

            // When
            OAuthProviderResponse response = mapper.toOAuthProviderResponse(entity);

            // Then
            assertThat(response.getProvider()).isEqualTo("APPLE");
            assertThat(response.getProviderEmail()).isEqualTo("user@privaterelay.appleid.com");
        }
    }

    // ========================================
    // AuditLogEntity → LoginHistoryResponse
    // ========================================

    @Nested
    @DisplayName("toLoginHistoryResponse")
    class ToLoginHistoryResponse {

        @Test
        @DisplayName("正常系: LOGIN_SUCCESSイベントが変換される")
        void 変換_LOGIN_SUCCESS_eventTypeがString変換() {
            // Given
            AuditLogEntity entity = AuditLogEntity.builder()
                    .userId(1L)
                    .eventType(AuditLogEntity.AuditEventType.LOGIN_SUCCESS)
                    .ipAddress("127.0.0.1")
                    .userAgent("Firefox/100")
                    .build();

            // When
            LoginHistoryResponse response = mapper.toLoginHistoryResponse(entity);

            // Then
            assertThat(response.getEventType()).isEqualTo("LOGIN_SUCCESS");
            assertThat(response.getIpAddress()).isEqualTo("127.0.0.1");
            assertThat(response.getUserAgent()).isEqualTo("Firefox/100");
            // method は ignore なので null
            assertThat(response.getMethod()).isNull();
        }

        @Test
        @DisplayName("正常系: PASSWORD_CHANGEDイベントが変換される")
        void 変換_PASSWORD_CHANGED_eventTypeがString変換() {
            // Given
            AuditLogEntity entity = AuditLogEntity.builder()
                    .userId(2L)
                    .eventType(AuditLogEntity.AuditEventType.PASSWORD_CHANGED)
                    .ipAddress("10.0.0.1")
                    .userAgent(null)
                    .build();

            // When
            LoginHistoryResponse response = mapper.toLoginHistoryResponse(entity);

            // Then
            assertThat(response.getEventType()).isEqualTo("PASSWORD_CHANGED");
            assertThat(response.getUserAgent()).isNull();
        }

        @Test
        @DisplayName("正常系: MFA_ENABLEDイベントが変換される")
        void 変換_MFA_ENABLED_eventTypeがString変換() {
            // Given
            AuditLogEntity entity = AuditLogEntity.builder()
                    .userId(3L)
                    .eventType(AuditLogEntity.AuditEventType.MFA_ENABLED)
                    .ipAddress("192.168.0.1")
                    .userAgent("Safari/15")
                    .build();

            // When
            LoginHistoryResponse response = mapper.toLoginHistoryResponse(entity);

            // Then
            assertThat(response.getEventType()).isEqualTo("MFA_ENABLED");
        }

        @Test
        @DisplayName("正常系: eventTypeがnullの場合nullが返る")
        void 変換_eventTypeNull_nullが返る() {
            // Given
            AuditLogEntity entity = AuditLogEntity.builder()
                    .userId(1L)
                    .eventType(null)
                    .ipAddress("127.0.0.1")
                    .build();

            // When
            LoginHistoryResponse response = mapper.toLoginHistoryResponse(entity);

            // Then
            assertThat(response.getEventType()).isNull();
        }

        @Test
        @DisplayName("正常系: WITHDRAWAL_REQUESTEDイベントが変換される")
        void 変換_WITHDRAWAL_REQUESTED_eventTypeが変換() {
            // Given
            AuditLogEntity entity = AuditLogEntity.builder()
                    .userId(4L)
                    .eventType(AuditLogEntity.AuditEventType.WITHDRAWAL_REQUESTED)
                    .ipAddress("1.2.3.4")
                    .build();

            // When
            LoginHistoryResponse response = mapper.toLoginHistoryResponse(entity);

            // Then
            assertThat(response.getEventType()).isEqualTo("WITHDRAWAL_REQUESTED");
        }
    }
}
