package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.AuthErrorCode;
import com.mannschaft.app.auth.OAuthProperties;
import com.mannschaft.app.auth.entity.OAuthAccountEntity;
import com.mannschaft.app.auth.repository.OAuthAccountRepository;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * 設定画面（{@code /settings/linked-accounts}）からの Google OAuth 連携フローを担うサービス。
 * <p>
 * ログイン済みユーザーが既存アカウントに Google アカウントを後付けで連携するためのフローを実装する。
 * <ul>
 *   <li>{@link #generateAuthUrl(Long, String)} — 認可 URL を生成して Redis に state を保存する</li>
 *   <li>{@link #processCallback(String, String, String)} — コールバック後に連携を確定する</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthOAuthLinkService {

    private static final String GOOGLE_AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String STATE_KEY_PREFIX = "mannschaft:oauth_link_state:";
    private static final Duration STATE_TTL = Duration.ofMinutes(10);
    private static final String LINKED_ACCOUNTS_PATH = "/settings/linked-accounts";

    private final OAuthAccountRepository oauthAccountRepository;
    private final StringRedisTemplate redisTemplate;
    private final OAuthProperties oAuthProperties;
    private final AuthOAuthService authOAuthService;

    @Value("${app.base-url:http://localhost:3000}")
    private String appBaseUrl;

    /**
     * 指定プロバイダの OAuth 認可 URL を生成し、state を Redis に保存する。
     *
     * @param userId   連携を要求するユーザーID
     * @param provider プロバイダ識別子（例: {@code "GOOGLE"}）
     * @return 認可 URL
     * @throws BusinessException AUTH_028 — サポート外プロバイダ
     * @throws BusinessException AUTH_034 — 既に連携済み
     */
    public String generateAuthUrl(Long userId, String provider) {
        // 1. プロバイダ検証（GOOGLE のみサポート）
        OAuthAccountEntity.OAuthProvider oauthProvider = validateProvider(provider);

        // 2. 既に連携済みか確認
        boolean alreadyLinked = oauthAccountRepository.findByUserId(userId)
                .stream()
                .anyMatch(oa -> oa.getProvider() == oauthProvider);
        if (alreadyLinked) {
            throw new BusinessException(AuthErrorCode.AUTH_034);
        }

        // 3. state を生成して Redis に保存（TTL 10 分）
        String state = UUID.randomUUID().toString();
        String redisKey = STATE_KEY_PREFIX + state;
        redisTemplate.opsForValue().set(redisKey, String.valueOf(userId), STATE_TTL);

        // 4. 認可 URL を構築して返す
        return buildGoogleAuthUrl(state);
    }

    /**
     * OAuth プロバイダからのコールバックを処理し、連携を確定する。
     *
     * @param provider プロバイダ識別子（例: {@code "GOOGLE"}）
     * @param state    認可時に発行した state
     * @param code     認可コード
     * @return フロントエンドへのリダイレクト先 URL
     */
    @Transactional
    public String processCallback(String provider, String state, String code) {
        // 1. code が null または空 → oauth_denied
        if (code == null || code.isBlank()) {
            return appBaseUrl + LINKED_ACCOUNTS_PATH + "?error=oauth_denied";
        }

        // 2. Redis から state を検証
        String redisKey = STATE_KEY_PREFIX + state;
        String redisValue = redisTemplate.opsForValue().get(redisKey);
        if (redisValue == null) {
            return appBaseUrl + LINKED_ACCOUNTS_PATH + "?error=invalid_state";
        }

        // 3. userId を取得
        Long userId;
        try {
            userId = Long.parseLong(redisValue);
        } catch (NumberFormatException e) {
            log.warn("Redis state のユーザーID形式が不正: key={}, value={}", redisKey, redisValue);
            return appBaseUrl + LINKED_ACCOUNTS_PATH + "?error=invalid_state";
        }

        // 4. Google ユーザー情報を取得
        AuthOAuthService.OAuthUserInfo userInfo;
        try {
            userInfo = authOAuthService.fetchGoogleUserInfoPublic(code);
        } catch (BusinessException e) {
            log.warn("Google ユーザー情報取得失敗: userId={}, error={}", userId, e.getMessage());
            return appBaseUrl + LINKED_ACCOUNTS_PATH + "?error=oauth_denied";
        }

        // 5. 別ユーザーが同一 Google アカウントを連携済みか確認
        Optional<OAuthAccountEntity> existingAccount =
                oauthAccountRepository.findByProviderAndProviderUserId(
                        OAuthAccountEntity.OAuthProvider.GOOGLE, userInfo.providerUserId());
        if (existingAccount.isPresent() && !existingAccount.get().getUserId().equals(userId)) {
            return appBaseUrl + LINKED_ACCOUNTS_PATH + "?error=already_taken";
        }

        // 6. OAuthAccount を保存
        OAuthAccountEntity oauthAccount = OAuthAccountEntity.builder()
                .userId(userId)
                .provider(OAuthAccountEntity.OAuthProvider.GOOGLE)
                .providerUserId(userInfo.providerUserId())
                .providerEmail(userInfo.email())
                .build();
        oauthAccountRepository.save(oauthAccount);

        // 7. state を削除（使用済み）
        redisTemplate.delete(redisKey);

        return appBaseUrl + LINKED_ACCOUNTS_PATH + "?linked=GOOGLE";
    }

    // === ヘルパーメソッド ===

    /**
     * プロバイダ名を OAuthProvider enum に変換する。GOOGLE のみサポート。
     *
     * @param provider プロバイダ文字列（大文字想定）
     * @return OAuthProvider
     * @throws BusinessException AUTH_028 — サポート外プロバイダ
     */
    private OAuthAccountEntity.OAuthProvider validateProvider(String provider) {
        try {
            OAuthAccountEntity.OAuthProvider p = OAuthAccountEntity.OAuthProvider.valueOf(provider);
            // 設定画面連携は GOOGLE のみサポート
            if (p != OAuthAccountEntity.OAuthProvider.GOOGLE) {
                throw new BusinessException(AuthErrorCode.AUTH_028);
            }
            return p;
        } catch (IllegalArgumentException e) {
            throw new BusinessException(AuthErrorCode.AUTH_028);
        }
    }

    /**
     * Google OAuth 認可 URL を構築する。
     *
     * @param state 生成済み state 文字列
     * @return 完全な認可 URL
     */
    private String buildGoogleAuthUrl(String state) {
        String clientId = oAuthProperties.getGoogleClientId();
        String redirectUri = oAuthProperties.getGoogleLinkRedirectUri();

        return GOOGLE_AUTH_ENDPOINT
                + "?client_id=" + encode(clientId)
                + "&redirect_uri=" + encode(redirectUri)
                + "&response_type=code"
                + "&scope=" + encode("openid email profile")
                + "&state=" + encode(state)
                + "&access_type=offline";
    }

    /**
     * URL エンコード（UTF-8）。
     */
    private static String encode(String value) {
        if (value == null) return "";
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
