package com.mannschaft.app.auth;

import com.mannschaft.app.auth.dto.TokenResponse;
import com.mannschaft.app.auth.entity.RefreshTokenEntity;
import com.mannschaft.app.auth.repository.RefreshTokenRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuthSessionService;
import com.mannschaft.app.auth.service.AuthTokenRotationService;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.auth.service.RoleClaimResolver;
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

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link AuthTokenRotationService} の単体テスト。
 * AuthService から分離された Refresh Token ローテーション / リプレイ検出ロジックを検証する。
 *
 * <p>セキュリティ重要案件として、AuthServiceTest から該当 nested クラス単位で
 * テスト挙動を完全保存したまま機械的に移送した。logoutAllDevices 呼び出し検証は
 * {@link AuthSessionService} のモック呼び出しを観測する形に置き換えている。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthTokenRotationService 単体テスト")
class AuthTokenRotationServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private AuthTokenService authTokenService;

    @Mock
    private AuthSessionService authSessionService;

    @Mock
    private DomainEventPublisher eventPublisher;

    @Mock
    private RoleClaimResolver roleClaimResolver;

    @InjectMocks
    private AuthTokenRotationService authTokenRotationService;

    private static final String TEST_IP = "127.0.0.1";
    private static final String TEST_USER_AGENT = "Mozilla/5.0";

    @Nested
    @DisplayName("refreshAccessToken")
    class RefreshAccessToken {

        @Test
        @DisplayName("正常系: 新トークンペアが発行される")
        void refreshAccessToken_正常_新トークン発行() {
            // Given
            String rawRefreshToken = "raw-refresh-token";
            String tokenHash = "hashed-refresh-token";
            given(authTokenService.hashToken(rawRefreshToken)).willReturn(tokenHash);

            RefreshTokenEntity existingToken = RefreshTokenEntity.builder()
                    .userId(1L)
                    .tokenHash(tokenHash)
                    .rememberMe(false)
                    .ipAddress(TEST_IP)
                    .userAgent(TEST_USER_AGENT)
                    .expiresAt(LocalDateTime.now().plusDays(7))
                    .build();
            given(refreshTokenRepository.findByTokenHash(tokenHash)).willReturn(Optional.of(existingToken));

            given(userRepository.existsById(1L)).willReturn(true);
            given(authTokenService.issueAccessToken(any(), any())).willReturn("new-access-token");
            given(authTokenService.generateRefreshToken()).willReturn("new-raw-refresh-token");
            given(authTokenService.hashToken("new-raw-refresh-token")).willReturn("new-hashed-refresh-token");
            given(authTokenService.getRefreshTokenExpirationSeconds()).willReturn(604800L);
            given(authTokenService.getAccessTokenExpirationSeconds()).willReturn(900L);
            given(refreshTokenRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));

            // When
            ApiResponse<TokenResponse> response = authTokenRotationService.refreshAccessToken(rawRefreshToken, null);

            // Then
            assertThat(response.getData().getAccessToken()).isEqualTo("new-access-token");
            assertThat(response.getData().getRefreshToken()).isEqualTo("new-raw-refresh-token");
            verify(refreshTokenRepository).save(any(RefreshTokenEntity.class));
        }

        @Test
        @DisplayName("リフレッシュ時に SYSTEM_ADMIN を再判定し、解決した roles で新トークンを発行する")
        void refreshAccessToken_リフレッシュ時にSYSTEM_ADMIN再判定() {
            // Given: SYSTEM_ADMIN ユーザーが RoleClaimResolver で再判定されるケース
            String rawRefreshToken = "raw-refresh-token";
            String tokenHash = "hashed-refresh-token";
            given(authTokenService.hashToken(rawRefreshToken)).willReturn(tokenHash);

            RefreshTokenEntity existingToken = RefreshTokenEntity.builder()
                    .userId(7L)
                    .tokenHash(tokenHash)
                    .rememberMe(false)
                    .ipAddress(TEST_IP)
                    .userAgent(TEST_USER_AGENT)
                    .expiresAt(LocalDateTime.now().plusDays(7))
                    .build();
            given(refreshTokenRepository.findByTokenHash(tokenHash)).willReturn(Optional.of(existingToken));
            given(userRepository.existsById(7L)).willReturn(true);

            // RoleClaimResolver が SYSTEM_ADMIN を含む roles を返す（=リフレッシュ時の再判定）
            given(roleClaimResolver.resolveRoles(7L)).willReturn(List.of("MEMBER", "SYSTEM_ADMIN"));
            given(authTokenService.issueAccessToken(eq(7L), eq(List.of("MEMBER", "SYSTEM_ADMIN"))))
                    .willReturn("new-access-token-with-sysadmin");
            given(authTokenService.generateRefreshToken()).willReturn("new-raw-refresh-token");
            given(authTokenService.hashToken("new-raw-refresh-token")).willReturn("new-hashed-refresh-token");
            given(authTokenService.getRefreshTokenExpirationSeconds()).willReturn(604800L);
            given(authTokenService.getAccessTokenExpirationSeconds()).willReturn(900L);
            given(refreshTokenRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));

            // When
            ApiResponse<TokenResponse> response = authTokenRotationService.refreshAccessToken(rawRefreshToken, null);

            // Then: resolveRoles が当該 userId で呼ばれ、その結果が issueAccessToken に渡る
            verify(roleClaimResolver).resolveRoles(7L);
            verify(authTokenService).issueAccessToken(7L, List.of("MEMBER", "SYSTEM_ADMIN"));
            assertThat(response.getData().getAccessToken()).isEqualTo("new-access-token-with-sysadmin");
        }

        @Test
        @DisplayName("異常系: リボーク済みトークンで全トークン失効（リプレイ攻撃検出）")
        void refreshAccessToken_リボーク済み_全トークン失効() {
            // Given
            String rawRefreshToken = "revoked-refresh-token";
            String tokenHash = "hashed-revoked-token";
            given(authTokenService.hashToken(rawRefreshToken)).willReturn(tokenHash);

            RefreshTokenEntity revokedToken = RefreshTokenEntity.builder()
                    .userId(1L)
                    .tokenHash(tokenHash)
                    .rememberMe(false)
                    .expiresAt(LocalDateTime.now().plusDays(7))
                    .build();
            // revoke() を呼んで revokedAt を設定
            revokedToken.revoke();

            given(refreshTokenRepository.findByTokenHash(tokenHash)).willReturn(Optional.of(revokedToken));

            // When / Then
            assertThatThrownBy(() -> authTokenRotationService.refreshAccessToken(rawRefreshToken, null))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_029"));

            // 全デバイスログアウトが AuthSessionService 経由で発動されることを確認
            verify(authSessionService).logoutAllDevices(1L);
        }

        @Test
        @DisplayName("異常系: 存在しないRefresh TokenでAUTH_007例外")
        void refreshAccessToken_存在しない_AUTH007例外() {
            // Given
            String rawRefreshToken = "nonexistent-token";
            given(authTokenService.hashToken(rawRefreshToken)).willReturn("hashed-nonexistent");
            given(refreshTokenRepository.findByTokenHash("hashed-nonexistent")).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> authTokenRotationService.refreshAccessToken(rawRefreshToken, null))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_007"));
        }

        @Test
        @DisplayName("異常系: 期限切れRefresh TokenでAUTH_032例外")
        void refreshAccessToken_期限切れ_AUTH032例外() {
            // Given
            String rawRefreshToken = "expired-refresh-token";
            String tokenHash = "hashed-expired";
            given(authTokenService.hashToken(rawRefreshToken)).willReturn(tokenHash);

            RefreshTokenEntity expiredToken = RefreshTokenEntity.builder()
                    .userId(1L)
                    .tokenHash(tokenHash)
                    .rememberMe(false)
                    .expiresAt(LocalDateTime.now().minusDays(1)) // 期限切れ
                    .build();
            given(refreshTokenRepository.findByTokenHash(tokenHash)).willReturn(Optional.of(expiredToken));

            // When / Then
            assertThatThrownBy(() -> authTokenRotationService.refreshAccessToken(rawRefreshToken, null))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_032"));
        }

        @Test
        @DisplayName("異常系: ユーザーが存在しない場合AUTH_007例外")
        void refreshAccessToken_ユーザー不在_AUTH007例外() {
            // Given
            String rawRefreshToken = "valid-refresh-token";
            String tokenHash = "hashed-valid";
            given(authTokenService.hashToken(rawRefreshToken)).willReturn(tokenHash);

            RefreshTokenEntity existingToken = RefreshTokenEntity.builder()
                    .userId(999L)
                    .tokenHash(tokenHash)
                    .rememberMe(false)
                    .expiresAt(LocalDateTime.now().plusDays(7))
                    .build();
            given(refreshTokenRepository.findByTokenHash(tokenHash)).willReturn(Optional.of(existingToken));
            given(userRepository.existsById(999L)).willReturn(false);

            // When / Then
            assertThatThrownBy(() -> authTokenRotationService.refreshAccessToken(rawRefreshToken, null))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_007"));
        }
    }
}
