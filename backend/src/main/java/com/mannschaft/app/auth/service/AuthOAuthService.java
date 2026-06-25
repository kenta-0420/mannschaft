package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.AuthErrorCode;
import com.mannschaft.app.auth.OAuthProperties;
import com.mannschaft.app.auth.entity.OAuthAccountEntity;
import com.mannschaft.app.auth.repository.OAuthAccountRepository;
import com.mannschaft.app.auth.entity.OAuthLinkTokenEntity;
import com.mannschaft.app.auth.repository.OAuthLinkTokenRepository;
import com.mannschaft.app.auth.entity.RefreshTokenEntity;
import com.mannschaft.app.auth.repository.RefreshTokenRepository;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.repository.WebAuthnCredentialRepository;
import com.mannschaft.app.auth.dto.OAuthConflictResponse;
import com.mannschaft.app.auth.dto.OAuthProviderResponse;
import com.mannschaft.app.auth.dto.TokenResponse;
import com.mannschaft.app.auth.event.LoginSuccessEvent;
import com.mannschaft.app.auth.event.OAuthLinkedEvent;
import com.mannschaft.app.auth.event.OAuthLinkRequestedEvent;
import com.mannschaft.app.auth.event.OAuthUnlinkedEvent;
import com.mannschaft.app.auth.event.OAuthUserRegisteredEvent;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.DomainEventPublisher;
import com.mannschaft.app.common.EncryptionService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * OAuth認証サービス。OAuthプロバイダ連携によるログイン・アカウント連携・連携解除を担当する。
 * <p>
 * ※ 各OAuthプロバイダーのAPI呼び出しは将来実装。現時点ではインターフェース定義。
 * </p>
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AuthOAuthService {

    private final OAuthAccountRepository oauthAccountRepository;
    private final OAuthLinkTokenRepository oauthLinkTokenRepository;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final WebAuthnCredentialRepository webAuthnCredentialRepository;
    private final AuthTokenService authTokenService;
    private final DomainEventPublisher eventPublisher;
    private final EncryptionService encryptionService;
    private final OAuthProperties oAuthProperties;
    private final RoleClaimResolver roleClaimResolver;

    /** OAuthプロバイダとのHTTP通信に使用するWebClient。@PostConstructで初期化する。 */
    private WebClient webClient;

    @PostConstruct
    void initWebClient() {
        this.webClient = WebClient.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(512 * 1024))
                .build();
    }

    /**
     * OAuthプロバイダを使用してログインする。
     * <ol>
     *   <li>プロバイダ検証</li>
     *   <li>プロバイダAPIでユーザー情報取得</li>
     *   <li>既存連携の確認と処理分岐:
     *     <ul>
     *       <li>連携あり → トークン発行</li>
     *       <li>連携なし + メール一致 → 連携確認メール（OAuthConflictResponse, 202）</li>
     *       <li>連携なし + 新規 → ユーザー作成 + OAuth連携 + トークン発行</li>
     *     </ul>
     *   </li>
     * </ol>
     *
     * @param provider          OAuthプロバイダ名
     * @param authorizationCode 認可コード
     * @param ipAddress         リクエスト元IPアドレス
     * @param userAgent         ユーザーエージェント
     * @return トークンレスポンスまたはOAuth競合レスポンス
     */
    @Transactional
    public ApiResponse<?> loginWithOAuth(
            String provider, String authorizationCode, String ipAddress, String userAgent) {

        // 1. プロバイダ検証
        OAuthAccountEntity.OAuthProvider oauthProvider = validateProvider(provider);

        // 2. プロバイダAPIでユーザー情報取得（各プロバイダ統合時に fetchOAuthUserInfo を実装）
        OAuthUserInfo oauthUserInfo = fetchOAuthUserInfo(oauthProvider, authorizationCode);

        // 3. oauth_accounts検索
        Optional<OAuthAccountEntity> existingAccount = oauthAccountRepository
                .findByProviderAndProviderUserId(oauthProvider, oauthUserInfo.providerUserId());

        if (existingAccount.isPresent()) {
            // 既存連携あり → Access Token + Refresh Token発行
            Long userId = existingAccount.get().getUserId();
            UserEntity user = findUserOrThrow(userId);
            user.updateLastLoginAt();
            userRepository.save(user);

            TokenResponse tokenResponse = issueTokenPair(userId, ipAddress, userAgent);

            // イベント発行
            eventPublisher.publish(new LoginSuccessEvent(userId, "OAUTH_" + provider.toUpperCase(), ipAddress, userAgent));

            return ApiResponse.of(tokenResponse);
        }

        // 既存連携なし → メール一致ユーザーを検索
        Optional<UserEntity> existingUser = userRepository.findByEmail(oauthUserInfo.email());

        if (existingUser.isPresent()) {
            // メール一致ユーザーあり → OAuthLinkToken生成 + 確認メール → OAuthConflictResponse(202)
            Long userId = existingUser.get().getId();
            String rawToken = UUID.randomUUID().toString();
            String tokenHash = authTokenService.hashToken(rawToken);

            OAuthLinkTokenEntity linkToken = OAuthLinkTokenEntity.builder()
                    .userId(userId)
                    .provider(oauthProvider)
                    .providerUserId(oauthUserInfo.providerUserId())
                    .providerEmail(oauthUserInfo.email())
                    .tokenHash(tokenHash)
                    .expiresAt(LocalDateTime.now().plusHours(1))
                    .build();
            oauthLinkTokenRepository.save(linkToken);

            OAuthConflictResponse conflictResponse = new OAuthConflictResponse(
                    "このメールアドレスに既存のアカウントが存在します。連携確認メールを送信しました。",
                    oauthUserInfo.email(),
                    provider);

            // イベント発行: OAuth連携確認メール送信
            eventPublisher.publish(new OAuthLinkRequestedEvent(userId, ipAddress, userAgent, provider));

            return ApiResponse.of(conflictResponse);
        }

        // 新規ユーザー → UserEntity作成(password_hash=NULL) + OAuthAccount作成 → TokenResponse
        String oauthLastName = oauthUserInfo.lastName() != null ? oauthUserInfo.lastName() : "";
        String oauthFirstName = oauthUserInfo.firstName() != null ? oauthUserInfo.firstName() : "";
        UserEntity newUser = UserEntity.builder()
                .email(oauthUserInfo.email())
                .passwordHash(null)
                .lastName(oauthLastName)
                .firstName(oauthFirstName)
                .lastNameHash(encryptionService.hmac(oauthLastName))
                .firstNameHash(encryptionService.hmac(oauthFirstName))
                .displayName(oauthUserInfo.displayName() != null ? oauthUserInfo.displayName() : oauthUserInfo.email())
                .isSearchable(true)
                .locale("ja")
                .timezone("Asia/Tokyo")
                .status(UserEntity.UserStatus.ACTIVE)
                .build();
        newUser = userRepository.save(newUser);

        OAuthAccountEntity oauthAccount = OAuthAccountEntity.builder()
                .userId(newUser.getId())
                .provider(oauthProvider)
                .providerUserId(oauthUserInfo.providerUserId())
                .providerEmail(oauthUserInfo.email())
                .build();
        oauthAccountRepository.save(oauthAccount);

        TokenResponse tokenResponse = issueTokenPair(newUser.getId(), ipAddress, userAgent);

        // イベント発行: LoginSuccess + OAuth新規ユーザー登録
        eventPublisher.publish(new LoginSuccessEvent(newUser.getId(), "OAUTH_" + provider.toUpperCase(), ipAddress, userAgent));
        eventPublisher.publish(new OAuthUserRegisteredEvent(newUser.getId(), ipAddress, userAgent, provider.toUpperCase()));

        return ApiResponse.of(tokenResponse);
    }

    /**
     * OAuth連携を確認する。連携トークンを検証し、アカウントを連携してトークンを発行する。
     *
     * @param token OAuth連携トークン（平文）
     * @return トークンレスポンス
     */
    @Transactional
    public ApiResponse<TokenResponse> confirmOAuthLinkage(String token) {
        String tokenHash = authTokenService.hashToken(token);

        // 1. トークン検証
        OAuthLinkTokenEntity linkToken = oauthLinkTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new BusinessException(AuthErrorCode.AUTH_031));

        if (linkToken.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(AuthErrorCode.AUTH_031);
        }

        if (linkToken.getUsedAt() != null) {
            throw new BusinessException(AuthErrorCode.AUTH_031);
        }

        linkToken.markUsed();
        oauthLinkTokenRepository.save(linkToken);

        // 2. OAuthAccount作成
        OAuthAccountEntity oauthAccount = OAuthAccountEntity.builder()
                .userId(linkToken.getUserId())
                .provider(linkToken.getProvider())
                .providerUserId(linkToken.getProviderUserId())
                .providerEmail(linkToken.getProviderEmail())
                .build();
        oauthAccountRepository.save(oauthAccount);

        // 3. Access Token + Refresh Token発行
        TokenResponse tokenResponse = issueTokenPair(linkToken.getUserId(), null, null);

        // 4. イベント発行
        eventPublisher.publish(new OAuthLinkedEvent(linkToken.getUserId(), linkToken.getProvider().name()));

        return ApiResponse.of(tokenResponse);
    }

    /**
     * ユーザーの連携済みOAuthプロバイダ一覧を取得する。
     *
     * @param userId ユーザーID
     * @return OAuthプロバイダ連携情報リスト
     */
    public ApiResponse<List<OAuthProviderResponse>> getConnectedProviders(Long userId) {
        List<OAuthProviderResponse> providers = oauthAccountRepository.findByUserId(userId).stream()
                .map(oa -> new OAuthProviderResponse(
                        oa.getProvider().name(),
                        oa.getProviderEmail(),
                        oa.getCreatedAt()))
                .collect(Collectors.toList());

        return ApiResponse.of(providers);
    }

    /**
     * OAuthプロバイダの連携を解除する。
     * <ol>
     *   <li>連携存在チェック</li>
     *   <li>ログイン手段確認（最後のログイン手段を削除できない）</li>
     *   <li>OAuthAccount削除</li>
     *   <li>OAuthUnlinkedEvent発行</li>
     * </ol>
     *
     * @param userId   ユーザーID
     * @param provider OAuthプロバイダ名
     */
    @Transactional
    public void disconnectProvider(Long userId, String provider) {
        OAuthAccountEntity.OAuthProvider oauthProvider = validateProvider(provider);

        // 1. 連携存在チェック
        List<OAuthAccountEntity> userOAuthAccounts = oauthAccountRepository.findByUserId(userId);
        OAuthAccountEntity targetAccount = userOAuthAccounts.stream()
                .filter(oa -> oa.getProvider() == oauthProvider)
                .findFirst()
                .orElseThrow(() -> new BusinessException(AuthErrorCode.AUTH_029));

        // 2. ログイン手段確認
        UserEntity user = findUserOrThrow(userId);
        boolean hasPassword = user.getPasswordHash() != null;
        boolean hasOtherProvider = userOAuthAccounts.stream()
                .anyMatch(oa -> oa.getProvider() != oauthProvider);
        boolean hasWebauthn = !webAuthnCredentialRepository.findByUserId(userId).isEmpty();

        // パスワードも他プロバイダもWebAuthnもなければ最後のログイン手段
        if (!hasPassword && !hasOtherProvider && !hasWebauthn) {
            throw new BusinessException(AuthErrorCode.AUTH_030);
        }

        // 3. OAuthAccount削除
        oauthAccountRepository.delete(targetAccount);

        // 4. イベント発行
        eventPublisher.publish(new OAuthUnlinkedEvent(userId, provider));
    }

    // === ヘルパーメソッド ===

    /**
     * OAuthプロバイダ名を検証し、Enum値を返す。
     */
    private OAuthAccountEntity.OAuthProvider validateProvider(String provider) {
        try {
            return OAuthAccountEntity.OAuthProvider.valueOf(provider.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(AuthErrorCode.AUTH_028);
        }
    }

    /**
     * ユーザーを取得する。
     */
    private UserEntity findUserOrThrow(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(AuthErrorCode.AUTH_015));
    }

    /**
     * Access Token + Refresh Token のペアを発行する。
     */
    private TokenResponse issueTokenPair(Long userId, String ipAddress, String userAgent) {
        // 認可基盤完全根治 Phase 1（§3.2）: RoleClaimResolver で SYSTEM_ADMIN を判定して roles に載せる。
        String accessToken = authTokenService.issueAccessToken(userId, roleClaimResolver.resolveRoles(userId));
        String refreshToken = authTokenService.generateRefreshToken();
        String refreshTokenHash = authTokenService.hashToken(refreshToken);

        RefreshTokenEntity refreshTokenEntity = RefreshTokenEntity.builder()
                .userId(userId)
                .tokenHash(refreshTokenHash)
                .rememberMe(false)
                .ipAddress(ipAddress)
                .userAgent(userAgent)
                .expiresAt(LocalDateTime.now().plusDays(7))
                .build();
        RefreshTokenEntity saved = refreshTokenRepository.save(refreshTokenEntity);

        return new TokenResponse(accessToken, refreshToken, saved.getId(), 3600);
    }

    /**
     * Google OAuth: 認可コードをアクセストークンに交換し、userinfo エンドポイントからユーザー情報を取得する。
     * <p>
     * {@code AuthOAuthLinkService}（設定画面での連携フロー）から利用するために public に公開する。
     *
     * @param authorizationCode 認可コード
     * @return Googleユーザー情報
     * @throws BusinessException AUTH_027 — Token Exchange失敗またはユーザー情報取得失敗
     */
    public OAuthUserInfo fetchGoogleUserInfoPublic(String authorizationCode) {
        return fetchGoogleUserInfo(authorizationCode);
    }

    /**
     * Google OAuth 連携フロー専用: 任意の redirect_uri でトークン交換を行いユーザー情報を返す。
     * <p>
     * ログインフロー（{@link #fetchGoogleUserInfoPublic}）とは別の redirect_uri を使うため分離する。
     *
     * @param authorizationCode 認可コード
     * @param redirectUri       認可 URL 生成時に使用した redirect_uri
     * @return Googleユーザー情報
     * @throws BusinessException AUTH_027 — Token Exchange失敗またはユーザー情報取得失敗
     */
    public OAuthUserInfo fetchGoogleUserInfoForLink(String authorizationCode, String redirectUri) {
        return fetchGoogleUserInfo(authorizationCode, redirectUri);
    }

    /**
     * OAuthプロバイダAPIからユーザー情報を取得する。
     * <ul>
     *   <li>Google: 認可コード → Token Exchange → userinfo endpoint</li>
     *   <li>LINE: 認可コード → Token Exchange → ID Token (JWT) デコードまたはprofile API</li>
     *   <li>Apple: 認可コード → Token Exchange → ID Token (JWT) デコード</li>
     * </ul>
     *
     * @param provider          OAuthプロバイダ
     * @param authorizationCode 認可コード
     * @return OAuthユーザー情報
     * @throws BusinessException AUTH_027 — Token Exchange失敗またはユーザー情報取得失敗
     */
    private OAuthUserInfo fetchOAuthUserInfo(OAuthAccountEntity.OAuthProvider provider, String authorizationCode) {
        return switch (provider) {
            case GOOGLE -> fetchGoogleUserInfo(authorizationCode);
            case LINE   -> fetchLineUserInfo(authorizationCode);
            case APPLE  -> fetchAppleUserInfo(authorizationCode);
        };
    }

    // =========================================================
    // Google OAuth
    // =========================================================

    /**
     * Google OAuth: 認可コードをアクセストークンに交換し、userinfo エンドポイントからユーザー情報を取得する。
     * <p>
     * ログインフローのデフォルト redirect_uri（{@code oAuthProperties.getGoogleRedirectUri()}）を使用する。
     */
    private OAuthUserInfo fetchGoogleUserInfo(String authorizationCode) {
        return fetchGoogleUserInfo(authorizationCode, oAuthProperties.getGoogleRedirectUri());
    }

    /**
     * Google OAuth: 任意の redirect_uri でトークン交換を行い、userinfo エンドポイントからユーザー情報を取得する。
     * <p>
     * Google OAuth 仕様では token exchange の redirect_uri は認可 URL 生成時と完全一致が必須。
     * ログインフローと連携フローで異なる redirect_uri を使うため、引数で受け取る。
     */
    private OAuthUserInfo fetchGoogleUserInfo(String authorizationCode, String redirectUri) {
        // 1. Token Exchange
        MultiValueMap<String, String> tokenParams = new LinkedMultiValueMap<>();
        tokenParams.add("code", authorizationCode);
        tokenParams.add("client_id", oAuthProperties.getGoogleClientId());
        tokenParams.add("client_secret", oAuthProperties.getGoogleClientSecret());
        tokenParams.add("redirect_uri", redirectUri);
        tokenParams.add("grant_type", "authorization_code");

        Map<?, ?> tokenResponse;
        try {
            tokenResponse = webClient.post()
                    .uri(oAuthProperties.getGoogleTokenUri())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromFormData(tokenParams))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
        } catch (WebClientResponseException e) {
            log.warn("Google Token Exchange失敗: status={}, body={}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new BusinessException(AuthErrorCode.AUTH_027);
        }

        if (tokenResponse == null || tokenResponse.get("access_token") == null) {
            log.warn("Google Token Exchangeレスポンスにaccess_tokenが含まれていません");
            throw new BusinessException(AuthErrorCode.AUTH_027);
        }
        String accessToken = (String) tokenResponse.get("access_token");

        // 2. userinfo エンドポイントでユーザー情報取得
        Map<?, ?> userInfo;
        try {
            userInfo = webClient.get()
                    .uri(oAuthProperties.getGoogleUserinfoUri())
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
        } catch (WebClientResponseException e) {
            log.warn("Google userinfo取得失敗: status={}", e.getStatusCode());
            throw new BusinessException(AuthErrorCode.AUTH_027);
        }

        if (userInfo == null || userInfo.get("sub") == null) {
            log.warn("Google userinfoレスポンスにsubが含まれていません");
            throw new BusinessException(AuthErrorCode.AUTH_027);
        }

        String sub         = (String) userInfo.get("sub");
        String email       = (String) userInfo.get("email");
        String givenName   = (String) userInfo.get("given_name");
        String familyName  = (String) userInfo.get("family_name");
        String displayName = (String) userInfo.get("name");

        return new OAuthUserInfo(sub, email, familyName, givenName, displayName);
    }

    // =========================================================
    // LINE OAuth
    // =========================================================

    /**
     * LINE OAuth: 認可コードをトークンに交換し、IDトークン(JWT)をデコードしてユーザー情報を取得する。
     * IDトークンにプロフィール情報が含まれない場合は profile API にフォールバックする。
     */
    private OAuthUserInfo fetchLineUserInfo(String authorizationCode) {
        // 1. Token Exchange
        MultiValueMap<String, String> tokenParams = new LinkedMultiValueMap<>();
        tokenParams.add("grant_type", "authorization_code");
        tokenParams.add("code", authorizationCode);
        tokenParams.add("redirect_uri", oAuthProperties.getLineRedirectUri());
        tokenParams.add("client_id", oAuthProperties.getLineClientId());
        tokenParams.add("client_secret", oAuthProperties.getLineClientSecret());

        Map<?, ?> tokenResponse;
        try {
            tokenResponse = webClient.post()
                    .uri(oAuthProperties.getLineTokenUri())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromFormData(tokenParams))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
        } catch (WebClientResponseException e) {
            log.warn("LINE Token Exchange失敗: status={}, body={}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new BusinessException(AuthErrorCode.AUTH_027);
        }

        if (tokenResponse == null || tokenResponse.get("access_token") == null) {
            log.warn("LINE Token Exchangeレスポンスにaccess_tokenが含まれていません");
            throw new BusinessException(AuthErrorCode.AUTH_027);
        }

        String accessToken = (String) tokenResponse.get("access_token");
        String idToken     = (String) tokenResponse.get("id_token");

        // 2. IDトークンからユーザー情報を取得（ペイロードのbase64デコード）
        if (idToken != null && !idToken.isBlank()) {
            try {
                Map<?, ?> claims = decodeJwtPayload(idToken);
                String sub         = (String) claims.get("sub");
                String email       = (String) claims.get("email");
                String displayName = (String) claims.get("name");
                if (sub != null) {
                    return new OAuthUserInfo(sub, email, null, null, displayName);
                }
            } catch (Exception e) {
                log.warn("LINE IDトークンのデコードに失敗しました。profile APIにフォールバックします: {}", e.getMessage());
            }
        }

        // 3. IDトークンからサブが取れなかった場合は profile API にフォールバック
        Map<?, ?> profile;
        try {
            profile = webClient.get()
                    .uri(oAuthProperties.getLineProfileUri())
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
        } catch (WebClientResponseException e) {
            log.warn("LINE profile取得失敗: status={}", e.getStatusCode());
            throw new BusinessException(AuthErrorCode.AUTH_027);
        }

        if (profile == null || profile.get("userId") == null) {
            log.warn("LINE profileレスポンスにuserIdが含まれていません");
            throw new BusinessException(AuthErrorCode.AUTH_027);
        }

        String userId      = (String) profile.get("userId");
        String displayName = (String) profile.get("displayName");
        // LINE profile API はメールアドレスを返さない（scope: email が必要でIDトークン経由のみ）
        return new OAuthUserInfo(userId, null, null, null, displayName);
    }

    // =========================================================
    // Apple Sign-In
    // =========================================================

    /**
     * Apple Sign-In: 認可コードをトークンに交換し、IDトークン(JWT)をデコードしてユーザー情報を取得する。
     * Apple はIDトークンにのみメールを含み、profile APIを持たない。
     */
    private OAuthUserInfo fetchAppleUserInfo(String authorizationCode) {
        // 1. Token Exchange
        MultiValueMap<String, String> tokenParams = new LinkedMultiValueMap<>();
        tokenParams.add("grant_type", "authorization_code");
        tokenParams.add("code", authorizationCode);
        tokenParams.add("redirect_uri", oAuthProperties.getAppleRedirectUri());
        tokenParams.add("client_id", oAuthProperties.getAppleClientId());
        tokenParams.add("client_secret", oAuthProperties.getAppleClientSecret());

        Map<?, ?> tokenResponse;
        try {
            tokenResponse = webClient.post()
                    .uri(oAuthProperties.getAppleTokenUri())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromFormData(tokenParams))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
        } catch (WebClientResponseException e) {
            log.warn("Apple Token Exchange失敗: status={}, body={}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new BusinessException(AuthErrorCode.AUTH_027);
        }

        if (tokenResponse == null || tokenResponse.get("id_token") == null) {
            log.warn("Apple Token ExchangeレスポンスにID tokenが含まれていません");
            throw new BusinessException(AuthErrorCode.AUTH_027);
        }

        String idToken = (String) tokenResponse.get("id_token");

        // 2. IDトークンのペイロードをデコードしてユーザー情報取得
        Map<?, ?> claims;
        try {
            claims = decodeJwtPayload(idToken);
        } catch (Exception e) {
            log.warn("Apple IDトークンのデコードに失敗しました: {}", e.getMessage());
            throw new BusinessException(AuthErrorCode.AUTH_027);
        }

        String sub   = (String) claims.get("sub");
        String email = (String) claims.get("email");

        if (sub == null) {
            log.warn("Apple IDトークンにsubが含まれていません");
            throw new BusinessException(AuthErrorCode.AUTH_027);
        }

        // Apple はフルネームを提供しない（初回認証時のみフロント経由で受け取る設計が一般的）
        return new OAuthUserInfo(sub, email, null, null, null);
    }

    // =========================================================
    // JWT ペイロードデコードユーティリティ
    // =========================================================

    /**
     * JWTのペイロード部分（第2セクション）をBase64 URLデコードしてMapとして返す。
     * 署名検証は行わない（プロバイダ側でtoken exchangeが完了しているため信頼する）。
     *
     * @param jwt JWT文字列
     * @return ペイロードのクレームMap
     * @throws IllegalArgumentException JWTフォーマットが不正な場合
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> decodeJwtPayload(String jwt) {
        String[] parts = jwt.split("\\.");
        if (parts.length < 2) {
            throw new IllegalArgumentException("JWTフォーマットが不正です");
        }
        byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
        String payloadJson = new String(payloadBytes, StandardCharsets.UTF_8);

        // ObjectMapperを使わず、手動JSON解析は複雑なため com.fasterxml.jackson が利用可能と想定
        // Spring Boot はjacksonを標準搭載しているため安全に利用できる
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(payloadJson, Map.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("JWTペイロードのJSON解析に失敗しました: " + e.getMessage(), e);
        }
    }

    /**
     * OAuthプロバイダから取得したユーザー情報。
     */
    record OAuthUserInfo(
            String providerUserId,
            String email,
            String lastName,
            String firstName,
            String displayName
    ) {}
}
