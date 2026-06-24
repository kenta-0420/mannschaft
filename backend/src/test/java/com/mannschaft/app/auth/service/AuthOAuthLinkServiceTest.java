package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.OAuthProperties;
import com.mannschaft.app.auth.entity.OAuthAccountEntity;
import com.mannschaft.app.auth.repository.OAuthAccountRepository;
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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link AuthOAuthLinkService} の単体テスト（試練 / red）。
 * <p>
 * {@code /settings/linked-accounts} の Google OAuth 連携の
 * 認可URL生成・コールバック処理ロジックについて、受け入れ条件 AC-5〜AC-10 を検証する。
 * <p>
 * <b>現時点では {@code generateAuthUrl} / {@code processCallback} が
 * {@link UnsupportedOperationException} を投げるため、すべて RED になるのが正しい。</b>
 * 出陣（実装）フェーズでロジックを充填し、本テストを green 化する。
 * <p>
 * 連携確定の正常系では state 検証 → ユーザー情報取得 → 重複チェック → 保存 という
 * 順序で複数のスタブを置くが、未実装段階では早期に例外で中断するため
 * {@code @MockitoSettings(strictness = LENIENT)} で UnnecessaryStubbing を抑止する
 * （RED の主因はあくまで実装の {@code UnsupportedOperationException} とアサーション不成立）。
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
            given(oAuthProperties.getGoogleRedirectUri())
                    .willReturn("http://localhost:8080/api/v1/auth/oauth/link/GOOGLE/callback");
            given(redisTemplate.opsForValue()).willReturn(valueOperations);

            // When: 未実装のため UnsupportedOperationException → RED
            String authUrl = authOAuthLinkService.generateAuthUrl(TEST_USER_ID, "GOOGLE");

            // Then
            assertThat(authUrl)
                    .contains("client_id=test-client-id")
                    .contains("redirect_uri=")
                    .contains("scope=openid")
                    .contains("state=");
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
            // Given: state が Redis に存在し userId=1 を指す
            given(redisTemplate.opsForValue()).willReturn(valueOperations);
            given(valueOperations.get(STATE_REDIS_KEY)).willReturn(String.valueOf(TEST_USER_ID));
            // 既存連携なし（このユーザーの連携相手はまだ存在しない）
            given(oauthAccountRepository.findByProviderAndProviderUserId(
                    OAuthAccountEntity.OAuthProvider.GOOGLE, GOOGLE_SUB))
                    .willReturn(Optional.empty());
            given(oauthAccountRepository.save(any(OAuthAccountEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            // When: 未実装のため UnsupportedOperationException → RED
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

            // When: 未実装のため UnsupportedOperationException → RED
            String redirectUrl = authOAuthLinkService.processCallback("GOOGLE", "invalid-state", "auth-code");

            // Then
            assertThat(redirectUrl).contains(LINKED_PREFIX + "?error=invalid_state");
        }

        @Test
        @DisplayName("AC-8: 別ユーザーが既に同一プロバイダIDで連携済み: error=already_taken へリダイレクトする")
        void processCallback_別ユーザー連携済み_alreadyTakenリダイレクト() {
            // Given: state は有効（userId=1）だが、google-sub-123 は別ユーザー(userId=2)が既に連携済み
            given(redisTemplate.opsForValue()).willReturn(valueOperations);
            given(valueOperations.get(anyString())).willReturn(String.valueOf(TEST_USER_ID));

            OAuthAccountEntity takenByOther = OAuthAccountEntity.builder()
                    .userId(2L)
                    .provider(OAuthAccountEntity.OAuthProvider.GOOGLE)
                    .providerUserId(GOOGLE_SUB)
                    .providerEmail("other@example.com")
                    .build();
            given(oauthAccountRepository.findByProviderAndProviderUserId(
                    OAuthAccountEntity.OAuthProvider.GOOGLE, GOOGLE_SUB))
                    .willReturn(Optional.of(takenByOther));

            // When: 未実装のため UnsupportedOperationException → RED
            String redirectUrl = authOAuthLinkService.processCallback("GOOGLE", TEST_STATE, "auth-code");

            // Then
            assertThat(redirectUrl).contains(LINKED_PREFIX + "?error=already_taken");
        }

        @Test
        @DisplayName("AC-9: Google 側エラー（code 欠落）: error=oauth_denied へリダイレクトする")
        void processCallback_codeなし_oauthDeniedリダイレクト() {
            // Given: プロバイダ側で認可拒否され code が来ない
            // When: 未実装のため UnsupportedOperationException → RED
            String redirectUrl = authOAuthLinkService.processCallback("GOOGLE", TEST_STATE, null);

            // Then
            assertThat(redirectUrl).contains(LINKED_PREFIX + "?error=oauth_denied");
        }

        @Test
        @DisplayName("AC-10: 連携成功後、Redis の state キーが削除される")
        void processCallback_成功後_state削除() {
            // Given: AC-6 と同様のセットアップ
            given(redisTemplate.opsForValue()).willReturn(valueOperations);
            given(valueOperations.get(STATE_REDIS_KEY)).willReturn(String.valueOf(TEST_USER_ID));
            given(oauthAccountRepository.findByProviderAndProviderUserId(
                    OAuthAccountEntity.OAuthProvider.GOOGLE, GOOGLE_SUB))
                    .willReturn(Optional.empty());
            given(oauthAccountRepository.save(any(OAuthAccountEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            // When: 未実装のため UnsupportedOperationException → RED
            authOAuthLinkService.processCallback("GOOGLE", TEST_STATE, "auth-code");

            // Then: 使用済み state は削除される
            verify(redisTemplate).delete(STATE_REDIS_KEY);
        }
    }
}
