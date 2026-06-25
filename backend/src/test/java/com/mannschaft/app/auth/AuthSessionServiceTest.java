package com.mannschaft.app.auth;

import com.mannschaft.app.auth.AuditEventCategory;
import com.mannschaft.app.auth.dto.AuditLogResponse;
import com.mannschaft.app.auth.dto.LoginHistoryResponse;
import com.mannschaft.app.auth.dto.SessionResponse;
import com.mannschaft.app.auth.entity.RefreshTokenEntity;
import com.mannschaft.app.auth.repository.RefreshTokenRepository;
import com.mannschaft.app.auth.service.AuditLogQueryService;
import com.mannschaft.app.auth.service.AuthSessionService;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CursorPagedResponse;
import com.mannschaft.app.common.DomainEventPublisher;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link AuthSessionService} の単体テスト。
 * AuthService から分離されたログアウト / セッション管理ロジックを検証する。
 *
 * <p>セキュリティ重要案件として、AuthServiceTest から該当 nested クラス単位で
 * テスト挙動を完全保存したまま機械的に移送した。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthSessionService 単体テスト")
class AuthSessionServiceTest {

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private AuthTokenService authTokenService;

    @Mock
    private DomainEventPublisher eventPublisher;

    @Mock
    private AuditLogQueryService auditLogQueryService;

    @InjectMocks
    private AuthSessionService authSessionService;

    private static final String TEST_IP = "127.0.0.1";
    private static final String TEST_USER_AGENT = "Mozilla/5.0";

    // ========================================
    // logout
    // ========================================

    @Nested
    @DisplayName("logout")
    class Logout {

        @Test
        @DisplayName("正常系: RefreshToken失効とJTIブラックリスト追加")
        void logout_正常_トークン失効() {
            // Given
            String refreshTokenHash = "hashed-refresh-token";
            String jti = "jti-123";
            long expEpoch = LocalDateTime.now().plusHours(1).toEpochSecond(java.time.ZoneOffset.UTC);

            RefreshTokenEntity token = RefreshTokenEntity.builder()
                    .userId(1L)
                    .tokenHash(refreshTokenHash)
                    .rememberMe(false)
                    .expiresAt(LocalDateTime.now().plusDays(7))
                    .build();
            given(refreshTokenRepository.findByTokenHash(refreshTokenHash))
                    .willReturn(Optional.of(token));

            // When
            authSessionService.logout(refreshTokenHash, jti, expEpoch);

            // Then
            assertThat(token.getRevokedAt()).isNotNull();
            verify(authTokenService).addJtiToBlacklist(eq(jti), anyLong());
            verify(eventPublisher).publish(any());
        }

        @Test
        @DisplayName("正常系: 存在しないRefreshTokenの場合は何もしない")
        void logout_トークン不在_何もしない() {
            // Given
            given(refreshTokenRepository.findByTokenHash("nonexistent"))
                    .willReturn(Optional.empty());

            // When
            authSessionService.logout("nonexistent", "jti", 0L);

            // Then
            verify(authTokenService, never()).addJtiToBlacklist(any(), anyLong());
            verify(eventPublisher, never()).publish(any());
        }
    }

    // ========================================
    // logoutAllDevices
    // ========================================

    @Nested
    @DisplayName("logoutAllDevices")
    class LogoutAllDevices {

        @Test
        @DisplayName("正常系: 全RefreshToken失効とユーザー無効化タイムスタンプ設定")
        void logoutAllDevices_正常_全失効() {
            // Given
            Long userId = 1L;
            RefreshTokenEntity token1 = RefreshTokenEntity.builder()
                    .userId(userId).tokenHash("hash1").rememberMe(false)
                    .expiresAt(LocalDateTime.now().plusDays(7)).build();
            RefreshTokenEntity token2 = RefreshTokenEntity.builder()
                    .userId(userId).tokenHash("hash2").rememberMe(false)
                    .expiresAt(LocalDateTime.now().plusDays(7)).build();
            given(refreshTokenRepository.findByUserIdAndRevokedAtIsNull(userId))
                    .willReturn(List.of(token1, token2));

            // When
            authSessionService.logoutAllDevices(userId);

            // Then
            assertThat(token1.getRevokedAt()).isNotNull();
            assertThat(token2.getRevokedAt()).isNotNull();
            verify(authTokenService).setUserInvalidationTimestamp(userId);
            verify(eventPublisher).publish(any());
        }

        @Test
        @DisplayName("正常系: アクティブトークンなしでも正常終了")
        void logoutAllDevices_トークンなし_正常終了() {
            // Given
            Long userId = 1L;
            given(refreshTokenRepository.findByUserIdAndRevokedAtIsNull(userId))
                    .willReturn(List.of());

            // When
            authSessionService.logoutAllDevices(userId);

            // Then
            verify(authTokenService).setUserInvalidationTimestamp(userId);
            verify(eventPublisher).publish(any());
        }
    }

    // ========================================
    // logoutDevice
    // ========================================

    @Nested
    @DisplayName("logoutDevice")
    class LogoutDevice {

        @Test
        @DisplayName("正常系: 指定デバイスのRefreshTokenが失効される")
        void logoutDevice_正常_トークン失効() {
            // Given
            Long userId = 1L;
            Long tokenId = 100L;
            RefreshTokenEntity token = RefreshTokenEntity.builder()
                    .userId(userId).tokenHash("hash").rememberMe(false)
                    .expiresAt(LocalDateTime.now().plusDays(7)).build();
            given(refreshTokenRepository.findById(tokenId)).willReturn(Optional.of(token));

            // When
            authSessionService.logoutDevice(userId, tokenId, null, null);

            // Then
            assertThat(token.getRevokedAt()).isNotNull();
            verify(eventPublisher).publish(any());
        }

        @Test
        @DisplayName("異常系: 他ユーザーのトークンの場合はAUTH_033")
        void logoutDevice_他ユーザーのトークン_例外() {
            // Given
            Long userId = 1L;
            Long tokenId = 100L;
            RefreshTokenEntity token = RefreshTokenEntity.builder()
                    .userId(999L) // 別のユーザー
                    .tokenHash("hash").rememberMe(false)
                    .expiresAt(LocalDateTime.now().plusDays(7)).build();
            given(refreshTokenRepository.findById(tokenId)).willReturn(Optional.of(token));

            // When/Then
            assertThatThrownBy(() -> authSessionService.logoutDevice(userId, tokenId, null, null))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("異常系: 存在しないトークンIDの場合はAUTH_033")
        void logoutDevice_トークン不在_例外() {
            // Given
            given(refreshTokenRepository.findById(999L)).willReturn(Optional.empty());

            // When/Then
            assertThatThrownBy(() -> authSessionService.logoutDevice(1L, 999L, null, null))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ========================================
    // getSessions
    // ========================================

    @Nested
    @DisplayName("getSessions")
    class GetSessions {

        @Test
        @DisplayName("正常系: アクティブセッション一覧が返る")
        void getSessions_正常_セッション一覧() {
            // Given
            Long userId = 1L;
            RefreshTokenEntity activeToken = RefreshTokenEntity.builder()
                    .userId(userId).tokenHash("hash").rememberMe(false)
                    .ipAddress(TEST_IP).userAgent(TEST_USER_AGENT)
                    .expiresAt(LocalDateTime.now().plusDays(7)).build();
            given(refreshTokenRepository.findByUserIdAndRevokedAtIsNull(userId))
                    .willReturn(List.of(activeToken));

            // When
            ApiResponse<List<SessionResponse>> response = authSessionService.getSessions(userId, null, null);

            // Then
            assertThat(response.getData()).hasSize(1);
        }

        @Test
        @DisplayName("正常系: 期限切れトークンはフィルタされる")
        void getSessions_期限切れ_フィルタ() {
            // Given
            Long userId = 1L;
            RefreshTokenEntity expiredToken = RefreshTokenEntity.builder()
                    .userId(userId).tokenHash("hash").rememberMe(false)
                    .ipAddress(TEST_IP).userAgent(TEST_USER_AGENT)
                    .expiresAt(LocalDateTime.now().minusDays(1)).build(); // 期限切れ
            given(refreshTokenRepository.findByUserIdAndRevokedAtIsNull(userId))
                    .willReturn(List.of(expiredToken));

            // When
            ApiResponse<List<SessionResponse>> response = authSessionService.getSessions(userId, null, null);

            // Then
            assertThat(response.getData()).isEmpty();
        }
    }

    // ========================================
    // getLoginHistory
    // ========================================

    @Nested
    @DisplayName("getLoginHistory")
    class GetLoginHistory {

        @Test
        @DisplayName("正常系: AuditLogQueryServiceからAUTHカテゴリのログを取得する")
        void getLoginHistory_正常_空リスト() {
            // Given
            CursorPagedResponse<AuditLogResponse> emptyResponse = CursorPagedResponse.of(
                    List.of(),
                    new CursorPagedResponse.CursorMeta(null, false, 10));
            given(auditLogQueryService.getMyLogs(
                    eq(1L), isNull(), eq(List.of(AuditEventCategory.AUTH)),
                    isNull(), isNull(), isNull(), eq(10)))
                    .willReturn(emptyResponse);

            // When
            CursorPagedResponse<LoginHistoryResponse> response =
                    authSessionService.getLoginHistory(1L, null, 10, null, null);

            // Then
            assertThat(response.getData()).isEmpty();
            assertThat(response.getMeta().isHasNext()).isFalse();
        }
    }
}