package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.OAuthProperties;
import com.mannschaft.app.auth.entity.OAuthAccountEntity;
import com.mannschaft.app.auth.repository.OAuthAccountRepository;
import com.mannschaft.app.schedule.service.GoogleCalendarService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link AuthOAuthLinkService} の単体テスト。
 * <p>
 * {@code /settings/linked-accounts} の Google OAuth 連携の
 * 認可URL生成・コールバック処理ロジックについて、受け入れ条件 AC-5〜AC-10 および
 * Google Calendar 同時設定（includeCalendar）の AC-11〜AC-12 を検証する。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AuthOAuthLinkService 単体テスト")
class AuthOAuthLinkServiceTest {

    @Mock
    private OAuthAccountRepository oauthAccountRepository;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private OAuthProperties oAuthProperties;

    /** fetchGoogleUserInfo 相当の流用元（実装が依存注入して使う想定）。private メソッドは直接モックしない。 */
    @Mock
    private AuthOAuthService authOAuthService;

    @Mock
    private GoogleCalendarService googleCalendarService;

    @InjectMocks
    private AuthOAuthLinkService authOAuthLinkService;

    // ── テスト用定数 ──
    private static final Long TEST_USER_ID = 1L;
    private static final String TEST_STATE = "test-state";
    private static final String STATE_REDIS_KEY = "mannschaft:oauth_link_state:test-state";
    private static final String GOOGLE_SUB = "google-sub-123";
    private static final String LINKED_PREFIX = "/settings/linked-accounts";

    // ──────────────────────────────────────────────
    // AC-5: authUrl に必須パラメータが含まれる
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("generateAuthUrl")
    class GenerateAuthUrl {

        @Test
        @DisplayName("AC-5: 認可URLに client_id / redirect_uri / scope=openid / state が含まれる")
        void generateAuthUrl_google_含む必須パラメータ() {
            // Given
            given(oAuthProperties.getGoogleClientId()).willReturn("test-client-id");
            given(oAuthProperties.getGoogleLinkRedirectUri())
                    .willReturn("http://localhost:8080/api/v1/auth/oauth/link/GOOGLE/callback");
            given(oauthAccountRepository.findByUserId(TEST_USER_ID)).willReturn(List.of());
            given(redisTemplate.opsForValue()).willReturn(valueOperations);

            // When
            String authUrl = authOAuthLinkService.generateAuthUrl(TEST_USER_ID, "GOOGLE", false);

            // Then
            assertThat(authUrl)
                    .contains("client_id=test-client-id")
                    .contains("redirect_uri=")
                    .contains("scope=openid")
                    .contains("state=");
        }
    }

    // ──────────────────────────────────────────────
    // AC-11 / AC-12: includeCalendar によるスコープ分岐
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("generateAuthUrl with includeCalendar")
    class GenerateAuthUrlWithCalendar {

        @Test
        @DisplayName("AC-11: includeCalendar=true で calendar スコープが含まれる")
        void includeCalendarTrueでcalendarスコープが含まれる() {
            // Given
            given(oAuthProperties.getGoogleClientId()).willReturn("client-id");
            given(oAuthProperties.getGoogleLinkRedirectUri())
                    .willReturn("http://localhost:8080/api/v1/auth/oauth/link/GOOGLE/callback");
            given(oauthAccountRepository.findByUserId(TEST_USER_ID)).willReturn(List.of());
            given(redisTemplate.opsForValue()).willReturn(valueOperations);

            // When
            String authUrl = authOAuthLinkService.generateAuthUrl(TEST_USER_ID, "GOOGLE", true);

            // Then: calendar スコープが含まれる
            assertThat(authUrl).contains("calendar");
            // prompt=consent も含まれる（refresh_token を確実に取得するため）
            assertThat(authUrl).contains("prompt=consent");
        }

        @Test
        @DisplayName("AC-12: includeCalendar=false で calendar スコープが含まれない")
        void includeCalendarFalseでcalendarスコープが含まれない() {
            // Given
            given(oAuthProperties.getGoogleClientId()).willReturn("client-id");
            given(oAuthProperties.getGoogleLinkRedirectUri())
                    .willReturn("http://localhost:8080/api/v1/auth/oauth/link/GOOGLE/callback");
            given(oauthAccountRepository.findByUserId(TEST_USER_ID)).willReturn(List.of());
            given(redisTemplate.opsForValue()).willReturn(valueOperations);

            // When
            String authUrl = authOAuthLinkService.generateAuthUrl(TEST_USER_ID, "GOOGLE", false);

            // Then: calendar スコープが含まれない
            assertThat(authUrl).doesNotContain("calendar");
        }
    }

    // ──────────────────────────────────────────────
    // AC-6 / AC-7 / AC-8 / AC-9 / AC-10: processCallback
    // ──────────────────────────────────────────────

    @Nested
    @DisplayName("processCallback")
    class ProcessCallback {

        @Test
        @DisplayName("AC-6: 正常系: OAuthAccount を保存し linked=GOOGLE へリダイレクトする")
        void processCallback_正常_保存しリダイレクト() {
            // Given: state が Redis に存在し userId=1 / includeCalendar=false を指す
            given(redisTemplate.opsForValue()).willReturn(valueOperations);
            given(valueOperations.get(STATE_REDIS_KEY)).willReturn(TEST_USER_ID + ":false");
            // Google ユーザー情報取得のモック
            given(authOAuthService.fetchGoogleUserInfoForLink(eq("auth-code"), any()))
                    .willReturn(new AuthOAuthService.OAuthUserInfo(
                            GOOGLE_SUB, "test@example.com", null, null, "Test User"));
            // 既存連携なし
            given(oauthAccountRepository.findByProviderAndProviderUserId(
                    OAuthAccountEntity.OAuthProvider.GOOGLE, GOOGLE_SUB))
                    .willReturn(Optional.empty());
            given(oauthAccountRepository.save(any(OAuthAccountEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            // When
            String redirectUrl = authOAuthLinkService.processCallback("GOOGLE", TEST_STATE, "auth-code");

            // Then
            verify(oauthAccountRepository).save(any(OAuthAccountEntity.class));
            assertThat(redirectUrl).contains(LINKED_PREFIX + "?linked=GOOGLE");
        }

        @Test
        @DisplayName("AC-7: state が Redis に無い: error=invalid_state へリダイレクトする")
        void processCallback_state不在_invalidStateリダイレクト() {
            // Given: state がどのキーでも Redis に存在しない
            given(redisTemplate.opsForValue()).willReturn(valueOperations);
            given(valueOperations.get(anyString())).willReturn(null);

            // When
            String redirectUrl = authOAuthLinkService.processCallback("GOOGLE", "invalid-state", "auth-code");

            // Then
            assertThat(redirectUrl).contains(LINKED_PREFIX + "?error=invalid_state");
        }

        @Test
        @DisplayName("AC-8: 別ユーザーが既に同一プロバイダIDで連携済み: error=already_taken へリダイレクトする")
        void processCallback_別ユーザー連携済み_alreadyTakenリダイレクト() {
            // Given: state は有効（userId=1）だが、google-sub-123 は別ユーザー(userId=2)が既に連携済み
            given(redisTemplate.opsForValue()).willReturn(valueOperations);
            given(valueOperations.get(anyString())).willReturn(TEST_USER_ID + ":false");
            given(authOAuthService.fetchGoogleUserInfoForLink(eq("auth-code"), any()))
                    .willReturn(new AuthOAuthService.OAuthUserInfo(
                            GOOGLE_SUB, "test@example.com", null, null, "Test User"));

            OAuthAccountEntity takenByOther = OAuthAccountEntity.builder()
                    .userId(2L)
                    .provider(OAuthAccountEntity.OAuthProvider.GOOGLE)
                    .providerUserId(GOOGLE_SUB)
                    .providerEmail("other@example.com")
                    .build();
            given(oauthAccountRepository.findByProviderAndProviderUserId(
                    OAuthAccountEntity.OAuthProvider.GOOGLE, GOOGLE_SUB))
                    .willReturn(Optional.of(takenByOther));

            // When
            String redirectUrl = authOAuthLinkService.processCallback("GOOGLE", TEST_STATE, "auth-code");

            // Then
            assertThat(redirectUrl).contains(LINKED_PREFIX + "?error=already_taken");
        }

        @Test
        @DisplayName("AC-9: Google 側エラー（code 欠落）: error=oauth_denied へリダイレクトする")
        void processCallback_codeなし_oauthDeniedリダイレクト() {
            // Given: プロバイダ側で認可拒否され code が来ない
            // When
            String redirectUrl = authOAuthLinkService.processCallback("GOOGLE", TEST_STATE, null);

            // Then
            assertThat(redirectUrl).contains(LINKED_PREFIX + "?error=oauth_denied");
        }

        @Test
        @DisplayName("AC-10: 連携成功後、Redis の state キーが削除される")
        void processCallback_成功後_state削除() {
            // Given: AC-6 と同様のセットアップ
            given(redisTemplate.opsForValue()).willReturn(valueOperations);
            given(valueOperations.get(STATE_REDIS_KEY)).willReturn(TEST_USER_ID + ":false");
            given(authOAuthService.fetchGoogleUserInfoForLink(eq("auth-code"), any()))
                    .willReturn(new AuthOAuthService.OAuthUserInfo(
                            GOOGLE_SUB, "test@example.com", null, null, "Test User"));
            given(oauthAccountRepository.findByProviderAndProviderUserId(
                    OAuthAccountEntity.OAuthProvider.GOOGLE, GOOGLE_SUB))
                    .willReturn(Optional.empty());
            given(oauthAccountRepository.save(any(OAuthAccountEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            // When
            authOAuthLinkService.processCallback("GOOGLE", TEST_STATE, "auth-code");

            // Then: 使用済み state は削除される
            verify(redisTemplate).delete(STATE_REDIS_KEY);
        }

        @Test
        @DisplayName("AC-13: includeCalendar=true の場合 googleCalendarService.connectWithOAuthTokens が呼ばれる")
        void processCallback_includeCalendarTrue_GCal接続が呼ばれる() {
            // Given: includeCalendar=true で state が保存されている
            given(redisTemplate.opsForValue()).willReturn(valueOperations);
            given(valueOperations.get(STATE_REDIS_KEY)).willReturn(TEST_USER_ID + ":true");
            given(authOAuthService.fetchGoogleUserInfoForLinkWithTokens(eq("auth-code"), any()))
                    .willReturn(new AuthOAuthService.OAuthLinkTokenResult(
                            new AuthOAuthService.OAuthUserInfo(
                                    GOOGLE_SUB, "test@example.com", null, null, "Test User"),
                            "access-token-123",
                            "refresh-token-456"));
            given(oauthAccountRepository.findByProviderAndProviderUserId(
                    OAuthAccountEntity.OAuthProvider.GOOGLE, GOOGLE_SUB))
                    .willReturn(Optional.empty());
            given(oauthAccountRepository.save(any(OAuthAccountEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            // When
            String redirectUrl = authOAuthLinkService.processCallback("GOOGLE", TEST_STATE, "auth-code");

            // Then: GCal接続が呼ばれ calendarConnected=true が返る
            verify(googleCalendarService).connectWithOAuthTokens(
                    eq(TEST_USER_ID), eq("access-token-123"), eq("refresh-token-456"), eq("test@example.com"));
            assertThat(redirectUrl).contains("calendarConnected=true");
        }

        @Test
        @DisplayName("AC-14: includeCalendar=true でGCal接続失敗してもOAuth連携はOK（calendarConnected=false）")
        void processCallback_includeCalendarTrue_GCal接続失敗でも連携は成功() {
            // Given: GCal接続が例外を投げる
            given(redisTemplate.opsForValue()).willReturn(valueOperations);
            given(valueOperations.get(STATE_REDIS_KEY)).willReturn(TEST_USER_ID + ":true");
            given(authOAuthService.fetchGoogleUserInfoForLinkWithTokens(eq("auth-code"), any()))
                    .willReturn(new AuthOAuthService.OAuthLinkTokenResult(
                            new AuthOAuthService.OAuthUserInfo(
                                    GOOGLE_SUB, "test@example.com", null, null, "Test User"),
                            "access-token-123",
                            null));
            given(oauthAccountRepository.findByProviderAndProviderUserId(
                    OAuthAccountEntity.OAuthProvider.GOOGLE, GOOGLE_SUB))
                    .willReturn(Optional.empty());
            given(oauthAccountRepository.save(any(OAuthAccountEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));
            org.mockito.Mockito.doThrow(new RuntimeException("GCal接続失敗"))
                    .when(googleCalendarService).connectWithOAuthTokens(any(), any(), any(), any());

            // When
            String redirectUrl = authOAuthLinkService.processCallback("GOOGLE", TEST_STATE, "auth-code");

            // Then: OAuth連携は完了、calendarConnected=false
            verify(oauthAccountRepository).save(any(OAuthAccountEntity.class));
            assertThat(redirectUrl).contains("calendarConnected=false");
        }
    }
}
