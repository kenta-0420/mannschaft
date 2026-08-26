package com.mannschaft.app.auth;

import com.mannschaft.app.auth.dto.TokenResponse;
import com.mannschaft.app.auth.entity.RefreshTokenEntity;
import com.mannschaft.app.auth.repository.RefreshTokenRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuthSessionService;
import com.mannschaft.app.auth.service.AuthTokenRotationService;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.auth.service.RoleClaimResolver;
import com.mannschaft.app.auth.service.StatusClaimResolver;
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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link AuthTokenRotationService} の単体テスト。
 * AuthService から分離された Refresh Token ローテーション / リプレイ検出ロジックを検証する。
 *
 * <p><b>本ファイルはリフレッシュトークン並行更新の自爆バグ根治（Phase1 試練）で全面改訂した。</b>
 * 従来の実装は「行ロックなし・grace なし」で旧トークン revoke → 新トークン発行を行っていたため、
 * 並行 refresh で片方 revoke 後にもう片方が使用済みトークンを再提示すると、これをリプレイと誤判定して
 * {@link AuthSessionService#logoutAllDevices(Long)} を発火させ、全セッションを永久無効化していた。</p>
 *
 * <p>根治方式は「DB 行ロック（{@code findByTokenHashForUpdate}）で直列化 + grace window
 * （後継ポインタ {@code replacedByTokenHash}）で並行更新を正規化」である。
 * 真リプレイは「grace 超過」または「後継無し revoke」のみ検知する。
 * 本テスト群は受け入れ条件 AC-1〜AC-6 / AC-9 を表明し、実装本体が未達の間は red（失敗）で正しい。</p>
 *
 * <p>取得は一貫して悲観ロック版 {@link RefreshTokenRepository#findByTokenHashForUpdate(String)} を経由するため、
 * テストのスタブもこれのみを用意する（Strictness は既定の STRICT_STUBS）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthTokenRotationService 単体テスト（並行更新 競合制御）")
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

    @Mock
    private StatusClaimResolver statusClaimResolver;

    @InjectMocks
    private AuthTokenRotationService authTokenRotationService;

    private static final String TEST_IP = "127.0.0.1";
    private static final String TEST_USER_AGENT = "Mozilla/5.0";

    /**
     * grace window「以内」を表現するための近過去の revokedAt（1 秒前）。
     * grace window は最低でも数十秒はある想定なので、1 秒前は確実に「以内」。
     */
    private static LocalDateTime justRevoked() {
        return LocalDateTime.now().minusSeconds(1);
    }

    /**
     * grace window「超過」を表現するための遠過去の revokedAt（10 分前）。
     * いかなる現実的な grace window（推奨 60 秒）も確実に超過する。
     */
    private static LocalDateTime longAgoRevoked() {
        return LocalDateTime.now().minusMinutes(10);
    }

    /**
     * 正常ローテーション経路で必要になる発行系スタブをまとめて用意する。
     * 悲観ロック版・非ロック版どちらのファインダを実装が使っても同じトークンを返すよう両方スタブする。
     */
    private void stubIssuePath(Long userId) {
        given(userRepository.existsById(userId)).willReturn(true);
        given(roleClaimResolver.resolveRoles(userId)).willReturn(List.of("MEMBER"));
        given(authTokenService.issueAccessToken(any(), any(), anyBoolean())).willReturn("new-access-token");
        given(authTokenService.generateRefreshToken()).willReturn("new-raw-refresh-token");
        given(authTokenService.hashToken("new-raw-refresh-token")).willReturn("new-hashed-refresh-token");
        given(authTokenService.getRefreshTokenExpirationSeconds()).willReturn(604800L);
        given(authTokenService.getAccessTokenExpirationSeconds()).willReturn(900L);
        given(refreshTokenRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
    }

    /** 悲観ロック版ファインダにトークンを返させる（ローテーションはこの経路で取得する）。 */
    private void stubTokenLookup(String tokenHash, RefreshTokenEntity token) {
        given(refreshTokenRepository.findByTokenHashForUpdate(tokenHash)).willReturn(Optional.of(token));
    }

    @Nested
    @DisplayName("並行更新の正規化（grace window）")
    class ConcurrencyGraceNormalization {

        @Test
        @DisplayName("AC-1: 同一トークンでの並行refresh（grace内・後継有）は全セッション無効化を発火せず新トークンを得る")
        void ac1_並行refresh_全セッション無効化しない() {
            // Given: 直前にローテーションで正規に置換された（後継有・grace内）トークンを再提示
            String rawRefreshToken = "raw-refresh-token";
            String tokenHash = "hashed-refresh-token";
            given(authTokenService.hashToken(rawRefreshToken)).willReturn(tokenHash);

            RefreshTokenEntity rotatedWithinGrace = RefreshTokenEntity.builder()
                    .userId(1L)
                    .tokenHash(tokenHash)
                    .rememberMe(false)
                    .ipAddress(TEST_IP)
                    .userAgent(TEST_USER_AGENT)
                    .expiresAt(LocalDateTime.now().plusDays(7))
                    .revokedAt(justRevoked())
                    .replacedByTokenHash("successor-token-hash")
                    .build();
            stubTokenLookup(tokenHash, rotatedWithinGrace);
            stubIssuePath(1L);

            // When
            ApiResponse<TokenResponse> response =
                    authTokenRotationService.refreshAccessToken(rawRefreshToken, null);

            // Then: リプレイ誤判定による全セッション無効化は起こらず、新トークンが返る
            assertThat(response.getData().getAccessToken()).isNotBlank();
            verify(authSessionService, never()).logoutAllDevices(anyLong());
            verify(authSessionService, never()).logoutAllDevices(anyLong(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        }

        @Test
        @DisplayName("AC-2: grace window内（後継有）の旧トークン再提示はリプレイにせず成功応答を返す")
        void ac2_grace内の旧トークン_成功応答() {
            // Given
            String rawRefreshToken = "raw-refresh-token-2";
            String tokenHash = "hashed-refresh-token-2";
            given(authTokenService.hashToken(rawRefreshToken)).willReturn(tokenHash);

            RefreshTokenEntity rotatedWithinGrace = RefreshTokenEntity.builder()
                    .userId(2L)
                    .tokenHash(tokenHash)
                    .rememberMe(false)
                    .expiresAt(LocalDateTime.now().plusDays(7))
                    .revokedAt(justRevoked())
                    .replacedByTokenHash("successor-token-hash-2")
                    .build();
            stubTokenLookup(tokenHash, rotatedWithinGrace);
            stubIssuePath(2L);

            // When / Then: 例外を投げず、有効なトークンペアを返す
            assertThatCode(() -> {
                ApiResponse<TokenResponse> response =
                        authTokenRotationService.refreshAccessToken(rawRefreshToken, null);
                assertThat(response.getData().getAccessToken()).isNotBlank();
                assertThat(response.getData().getRefreshToken()).isNotBlank();
            }).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("真リプレイ検出とリボーク種別の区別")
    class ReplayDetection {

        @Test
        @DisplayName("AC-3: grace超過（後継有）のリボーク済みトークン再提示は全セッション無効化＋AUTH_026")
        void ac3_grace超過_全セッション無効化_AUTH026() {
            // Given: 後継は有るが revoke から grace を大幅超過 = 真のリプレイ
            String rawRefreshToken = "replayed-token";
            String tokenHash = "hashed-replayed-token";
            given(authTokenService.hashToken(rawRefreshToken)).willReturn(tokenHash);

            RefreshTokenEntity replayed = RefreshTokenEntity.builder()
                    .userId(1L)
                    .tokenHash(tokenHash)
                    .rememberMe(false)
                    .expiresAt(LocalDateTime.now().plusDays(7))
                    .revokedAt(longAgoRevoked())
                    .replacedByTokenHash("successor-token-hash")
                    .build();
            stubTokenLookup(tokenHash, replayed);

            // When / Then
            assertThatThrownBy(() -> authTokenRotationService.refreshAccessToken(rawRefreshToken, null))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_026"));

            verify(authSessionService).logoutAllDevices(1L);
        }

        @Test
        @DisplayName("AC-4: 後継無しのリボーク済みトークン（明示ログアウト等）再提示はAUTH_007・全セッション無効化しない")
        void ac4_後継無しリボーク_AUTH007_無効化しない() {
            // Given: replacedByTokenHash 無し = ローテーション以外の revoke（明示ログアウト等）。grace 対象外。
            String rawRefreshToken = "explicitly-logged-out-token";
            String tokenHash = "hashed-logged-out-token";
            given(authTokenService.hashToken(rawRefreshToken)).willReturn(tokenHash);

            RefreshTokenEntity loggedOut = RefreshTokenEntity.builder()
                    .userId(1L)
                    .tokenHash(tokenHash)
                    .rememberMe(false)
                    .expiresAt(LocalDateTime.now().plusDays(7))
                    .revokedAt(longAgoRevoked())
                    .replacedByTokenHash(null)
                    .build();
            stubTokenLookup(tokenHash, loggedOut);

            // When / Then
            assertThatThrownBy(() -> authTokenRotationService.refreshAccessToken(rawRefreshToken, null))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_007"));

            verify(authSessionService, never()).logoutAllDevices(anyLong());
        }
    }

    @Nested
    @DisplayName("行ロック直列化とエラーコード是正")
    class LockingAndErrorCodes {

        @Test
        @DisplayName("AC-5: ローテーションでは悲観ロック版 findByTokenHashForUpdate が使われる")
        void ac5_悲観ロック版ファインダが使われる() {
            // Given: 正常な有効トークン
            String rawRefreshToken = "valid-token";
            String tokenHash = "hashed-valid-token";
            given(authTokenService.hashToken(rawRefreshToken)).willReturn(tokenHash);

            RefreshTokenEntity valid = RefreshTokenEntity.builder()
                    .userId(1L)
                    .tokenHash(tokenHash)
                    .rememberMe(false)
                    .expiresAt(LocalDateTime.now().plusDays(7))
                    .build();
            stubTokenLookup(tokenHash, valid);
            stubIssuePath(1L);

            // When: 例外の有無に関わらず、ファインダ呼び出しの事実を検証する
            try {
                authTokenRotationService.refreshAccessToken(rawRefreshToken, null);
            } catch (RuntimeException ignored) {
                // 実装未達で throw され得るが、本 AC は「行ロック版ファインダの利用」を検証するため無視
            }

            // Then: DB 行ロックで直列化するため、非ロック版ではなくロック版が呼ばれること
            verify(refreshTokenRepository).findByTokenHashForUpdate(tokenHash);
        }

        @Test
        @DisplayName("AC-6: 期限切れトークンは期限切れ用のエラーコードを返す（退会申請 AUTH_032 の誤用でない）")
        void ac6_期限切れ_AUTH032ではない() {
            // Given: revoke されていないが期限切れのトークン
            String rawRefreshToken = "expired-token";
            String tokenHash = "hashed-expired-token";
            given(authTokenService.hashToken(rawRefreshToken)).willReturn(tokenHash);

            RefreshTokenEntity expired = RefreshTokenEntity.builder()
                    .userId(1L)
                    .tokenHash(tokenHash)
                    .rememberMe(false)
                    .expiresAt(LocalDateTime.now().minusDays(1)) // 期限切れ
                    .build();
            stubTokenLookup(tokenHash, expired);

            // When / Then: BusinessException は投げるが、そのコードは AUTH_032（退会申請不存在）であってはならない
            assertThatThrownBy(() -> authTokenRotationService.refreshAccessToken(rawRefreshToken, null))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .as("期限切れに退会申請用の AUTH_032 を流用してはならない")
                            .isNotEqualTo("AUTH_032"));
        }
    }

    @Nested
    @DisplayName("AC-10: null / 空白トークンの即時拒否")
    class NullOrBlankToken {

        @Test
        @DisplayName("AC-10a: rawRefreshToken が null の場合は NPE でなく AUTH_007 の BusinessException")
        void ac10a_null_rawToken_throws_auth007() {
            // When / Then: Cookie 無し（null）はハッシュ化に到達する前に AUTH_007 を投げるべき
            assertThatThrownBy(() -> authTokenRotationService.refreshAccessToken(null, null))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_007"));
        }

        @Test
        @DisplayName("AC-10b: rawRefreshToken が空文字の場合は AUTH_007 の BusinessException")
        void ac10b_blank_rawToken_throws_auth007() {
            // When / Then: でたらめ Cookie（空文字）は AUTH_007 を投げるべき
            assertThatThrownBy(() -> authTokenRotationService.refreshAccessToken("", null))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_007"));
        }

        @Test
        @DisplayName("AC-10c: rawRefreshToken が空白文字のみの場合は AUTH_007 の BusinessException")
        void ac10c_whitespace_rawToken_throws_auth007() {
            // When / Then: スペースのみのトークンも AUTH_007 を投げるべき
            assertThatThrownBy(() -> authTokenRotationService.refreshAccessToken("   ", null))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_007"));
        }
    }

    @Nested
    @DisplayName("正常系 回帰")
    class NormalRegression {

        @Test
        @DisplayName("AC-9: 正常な単発refreshで新トークンペアが発行される")
        void ac9_正常_新トークン発行() {
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
            stubTokenLookup(tokenHash, existingToken);
            stubIssuePath(1L);

            // When
            ApiResponse<TokenResponse> response =
                    authTokenRotationService.refreshAccessToken(rawRefreshToken, null);

            // Then
            assertThat(response.getData().getAccessToken()).isEqualTo("new-access-token");
            assertThat(response.getData().getRefreshToken()).isEqualTo("new-raw-refresh-token");
            verify(refreshTokenRepository).save(any(RefreshTokenEntity.class));
        }

        @Test
        @DisplayName("AC-9: リフレッシュ時に SYSTEM_ADMIN を再判定し、解決した roles で新トークンを発行する")
        void ac9_リフレッシュ時にSYSTEM_ADMIN再判定() {
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
            stubTokenLookup(tokenHash, existingToken);
            given(userRepository.existsById(7L)).willReturn(true);
            given(roleClaimResolver.resolveRoles(7L)).willReturn(List.of("MEMBER", "SYSTEM_ADMIN"));
            given(authTokenService.issueAccessToken(eq(7L), eq(List.of("MEMBER", "SYSTEM_ADMIN")), anyBoolean()))
                    .willReturn("new-access-token-with-sysadmin");
            given(authTokenService.generateRefreshToken()).willReturn("new-raw-refresh-token");
            given(authTokenService.hashToken("new-raw-refresh-token")).willReturn("new-hashed-refresh-token");
            given(authTokenService.getRefreshTokenExpirationSeconds()).willReturn(604800L);
            given(authTokenService.getAccessTokenExpirationSeconds()).willReturn(900L);
            given(refreshTokenRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));

            // When
            ApiResponse<TokenResponse> response =
                    authTokenRotationService.refreshAccessToken(rawRefreshToken, null);

            // Then
            verify(roleClaimResolver).resolveRoles(7L);
            verify(authTokenService).issueAccessToken(eq(7L), eq(List.of("MEMBER", "SYSTEM_ADMIN")), anyBoolean());
            assertThat(response.getData().getAccessToken()).isEqualTo("new-access-token-with-sysadmin");
        }

        @Test
        @DisplayName("AC-9: 存在しないRefresh TokenでAUTH_007例外")
        void ac9_存在しない_AUTH007例外() {
            // Given
            String rawRefreshToken = "nonexistent-token";
            given(authTokenService.hashToken(rawRefreshToken)).willReturn("hashed-nonexistent");
            given(refreshTokenRepository.findByTokenHashForUpdate("hashed-nonexistent")).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> authTokenRotationService.refreshAccessToken(rawRefreshToken, null))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_007"));
        }

        @Test
        @DisplayName("AC-9: ユーザーが存在しない場合AUTH_007例外")
        void ac9_ユーザー不在_AUTH007例外() {
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
            stubTokenLookup(tokenHash, existingToken);
            given(userRepository.existsById(999L)).willReturn(false);

            // When / Then
            assertThatThrownBy(() -> authTokenRotationService.refreshAccessToken(rawRefreshToken, null))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AUTH_007"));
        }
    }
}
