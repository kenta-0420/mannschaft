package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.AuthErrorCode;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.auth.entity.OAuthAccountEntity;
import com.mannschaft.app.auth.repository.OAuthAccountRepository;
import com.mannschaft.app.auth.entity.OAuthLinkTokenEntity;
import com.mannschaft.app.auth.repository.OAuthLinkTokenRepository;
import com.mannschaft.app.auth.entity.RefreshTokenEntity;
import com.mannschaft.app.auth.repository.RefreshTokenRepository;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.repository.WebAuthnCredentialRepository;
import com.mannschaft.app.auth.dto.MessageResponse;
import com.mannschaft.app.auth.dto.OAuthConflictResponse;
import com.mannschaft.app.auth.dto.OAuthProviderResponse;
import com.mannschaft.app.auth.dto.TokenResponse;
import com.mannschaft.app.auth.event.LoginSuccessEvent;
import com.mannschaft.app.auth.event.OAuthLinkedEvent;
import com.mannschaft.app.auth.event.OAuthUnlinkedEvent;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.DomainEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
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
    private final PasswordEncoder passwordEncoder;
    private final DomainEventPublisher eventPublisher;

    /**
     * OAuthプロバイダを使用してログインする。
     * <ol>
     *   <li>プロバイダ検証</li>
     *   <li>プロバイダAPIでユーザー情報取得（TODO）</li>
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
    @SuppressWarnings("unchecked")
    public ApiResponse<?> loginWithOAuth(
            String provider, String authorizationCode, String ipAddress, String userAgent) {

        // 1. プロバイダ検証
        OAuthAccountEntity.OAuthProvider oauthProvider = validateProvider(provider);

        // 2. プロバイダAPIでユーザー情報取得
        // TODO: 各OAuthプロバイダーのAPI呼び出しを実装する
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

            return ApiResponse.of(conflictResponse);
        }

        // 新規ユーザー → UserEntity作成(password_hash=NULL) + OAuthAccount作成 → TokenResponse
        UserEntity newUser = UserEntity.builder()
                .email(oauthUserInfo.email())
                .passwordHash(null)
                .lastName(oauthUserInfo.lastName() != null ? oauthUserInfo.lastName() : "")
                .firstName(oauthUserInfo.firstName() != null ? oauthUserInfo.firstName() : "")
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

        // イベント発行
        eventPublisher.publish(new LoginSuccessEvent(newUser.getId(), "OAUTH_" + provider.toUpperCase(), ipAddress, userAgent));

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
        String accessToken = authTokenService.issueAccessToken(userId, List.of("MEMBER"));
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
        refreshTokenRepository.save(refreshTokenEntity);

        return new TokenResponse(accessToken, refreshToken, 3600);
    }

    /**
     * OAuthプロバイダAPIからユーザー情報を取得する。
     * <p>
     * TODO: 各プロバイダー（Google, LINE, Apple）のAPI呼び出しを実装する。
     * 現時点ではダミー実装。
     * </p>
     */
    private OAuthUserInfo fetchOAuthUserInfo(OAuthAccountEntity.OAuthProvider provider, String authorizationCode) {
        // TODO: 各OAuthプロバイダーのAPI呼び出しを実装する
        // Google: OAuth2 Token Exchange → userinfo endpoint
        // LINE: OAuth2 Token Exchange → profile endpoint
        // Apple: ID Token (JWT) の検証
        log.warn("OAuthプロバイダAPI呼び出しはダミー実装です。各プロバイダーAPI統合時に正式実装してください。");
        throw new UnsupportedOperationException(
                "OAuthプロバイダAPI呼び出しは未実装です。provider=" + provider.name());
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
