package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.AuthErrorCode;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.auth.entity.RefreshTokenEntity;
import com.mannschaft.app.auth.repository.RefreshTokenRepository;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.entity.WebAuthnCredentialEntity;
import com.mannschaft.app.auth.repository.WebAuthnCredentialRepository;
import com.mannschaft.app.auth.dto.MessageResponse;
import com.mannschaft.app.auth.dto.TokenResponse;
import com.mannschaft.app.auth.dto.UpdateWebAuthnCredentialRequest;
import com.mannschaft.app.auth.dto.WebAuthnCredentialResponse;
import com.mannschaft.app.auth.dto.WebAuthnLoginBeginResponse;
import com.mannschaft.app.auth.dto.WebAuthnLoginCompleteRequest;
import com.mannschaft.app.auth.dto.WebAuthnRegisterBeginResponse;
import com.mannschaft.app.auth.dto.WebAuthnRegisterCompleteRequest;
import com.mannschaft.app.auth.event.LoginSuccessEvent;
import com.mannschaft.app.auth.event.WebAuthnRegisteredEvent;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.DomainEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * WebAuthn（パスキー・FIDO2）認証サービス。
 * 資格情報の登録・ログイン・管理を担当する。
 * <p>
 * ※ WebAuthn4Jライブラリは将来統合。現時点ではインターフェースとダミー実装。
 * </p>
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AuthWebAuthnService {

    private final WebAuthnCredentialRepository webAuthnCredentialRepository;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final AuthTokenService authTokenService;
    private final StringRedisTemplate redisTemplate;
    private final DomainEventPublisher eventPublisher;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String CHALLENGE_KEY_PREFIX = "mannschaft:auth:webauthn_challenge:";
    private static final int CHALLENGE_TTL_MINUTES = 5;
    private static final String RP_ID = "mannschaft.app";
    private static final String RP_NAME = "Mannschaft";

    /**
     * WebAuthn登録を開始する。チャレンジを生成してValkeyに保存する。
     *
     * @param userId ユーザーID
     * @return 登録開始レスポンス
     */
    @Transactional
    public ApiResponse<WebAuthnRegisterBeginResponse> beginRegister(Long userId) {
        UserEntity user = findUserOrThrow(userId);

        // チャレンジ生成
        String challenge = generateChallenge();

        // Valkey に保存（5分有効）
        String challengeKey = CHALLENGE_KEY_PREFIX + "register:" + userId;
        redisTemplate.opsForValue().set(challengeKey, challenge, CHALLENGE_TTL_MINUTES, TimeUnit.MINUTES);

        WebAuthnRegisterBeginResponse response = new WebAuthnRegisterBeginResponse(
                challenge, RP_ID, RP_NAME, userId, user.getDisplayName());

        return ApiResponse.of(response);
    }

    /**
     * WebAuthn登録を完了する。
     * <ol>
     *   <li>チャレンジ検証（Valkey取得+削除）</li>
     *   <li>credential_id重複チェック</li>
     *   <li>WebAuthnCredentialEntity保存</li>
     *   <li>WebAuthnRegisteredEvent発行</li>
     * </ol>
     * <p>
     * ※ attestation検証はTODO（WebAuthn4J統合時に実装）
     * </p>
     *
     * @param userId ユーザーID
     * @param req    登録完了リクエスト
     * @return メッセージレスポンス
     */
    @Transactional
    public ApiResponse<MessageResponse> completeRegister(Long userId, WebAuthnRegisterCompleteRequest req) {
        // 1. チャレンジ検証
        String challengeKey = CHALLENGE_KEY_PREFIX + "register:" + userId;
        String storedChallenge = redisTemplate.opsForValue().get(challengeKey);
        if (storedChallenge == null) {
            throw new BusinessException(AuthErrorCode.AUTH_027);
        }
        redisTemplate.delete(challengeKey);

        // TODO: WebAuthn4J統合時にattestation検証を実装する
        log.warn("WebAuthn attestation検証はダミー実装です。WebAuthn4J統合時に正式実装してください。");

        // 2. credential_id重複チェック
        if (webAuthnCredentialRepository.findByCredentialId(req.getCredentialId()).isPresent()) {
            throw new BusinessException(AuthErrorCode.AUTH_025);
        }

        // 3. WebAuthnCredentialEntity保存
        WebAuthnCredentialEntity credential = WebAuthnCredentialEntity.builder()
                .userId(userId)
                .credentialId(req.getCredentialId())
                .publicKey(req.getPublicKey())
                .signCount(0L)
                .deviceName(req.getDeviceName())
                .aaguid(req.getAaguid())
                .build();
        webAuthnCredentialRepository.save(credential);

        // 4. イベント発行
        eventPublisher.publish(new WebAuthnRegisteredEvent(userId, req.getDeviceName()));

        return ApiResponse.of(MessageResponse.of("WebAuthn資格情報を登録しました"));
    }

    /**
     * WebAuthnログインを開始する。登録済みcredential一覧とチャレンジを返す。
     *
     * @param email ユーザーのメールアドレス
     * @return ログイン開始レスポンス
     */
    public ApiResponse<WebAuthnLoginBeginResponse> beginLogin(String email) {
        UserEntity user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(AuthErrorCode.AUTH_015));

        // 1. ユーザーの登録済みcredential一覧取得
        List<WebAuthnCredentialEntity> credentials = webAuthnCredentialRepository.findByUserId(user.getId());
        if (credentials.isEmpty()) {
            throw new BusinessException(AuthErrorCode.AUTH_024);
        }

        List<String> allowCredentials = credentials.stream()
                .map(WebAuthnCredentialEntity::getCredentialId)
                .collect(Collectors.toList());

        // 2. チャレンジ生成 → Valkey保存（5分有効）
        String challenge = generateChallenge();
        String challengeKey = CHALLENGE_KEY_PREFIX + "login:" + user.getId();
        redisTemplate.opsForValue().set(challengeKey, challenge, CHALLENGE_TTL_MINUTES, TimeUnit.MINUTES);

        WebAuthnLoginBeginResponse response = new WebAuthnLoginBeginResponse(
                challenge, RP_ID, allowCredentials, 300000L);

        return ApiResponse.of(response);
    }

    /**
     * WebAuthnログインを完了する。
     * <ol>
     *   <li>チャレンジ検証</li>
     *   <li>credential_id検索</li>
     *   <li>sign_count検証（リプレイ攻撃防止）</li>
     *   <li>sign_count更新 + lastUsedAt更新</li>
     *   <li>Access Token + Refresh Token発行</li>
     *   <li>LoginSuccessEvent発行</li>
     * </ol>
     * <p>
     * ※ 署名検証はTODO（WebAuthn4J統合時に実装）
     * </p>
     *
     * @param req       ログイン完了リクエスト
     * @param ipAddress リクエスト元IPアドレス
     * @param userAgent ユーザーエージェント
     * @return トークンレスポンス
     */
    @Transactional
    public ApiResponse<TokenResponse> completeLogin(
            WebAuthnLoginCompleteRequest req, String ipAddress, String userAgent) {

        // 2. credential_id検索
        WebAuthnCredentialEntity credential = webAuthnCredentialRepository
                .findByCredentialId(req.getCredentialId())
                .orElseThrow(() -> new BusinessException(AuthErrorCode.AUTH_024));

        Long userId = credential.getUserId();

        // 1. チャレンジ検証
        String challengeKey = CHALLENGE_KEY_PREFIX + "login:" + userId;
        String storedChallenge = redisTemplate.opsForValue().get(challengeKey);
        if (storedChallenge == null) {
            throw new BusinessException(AuthErrorCode.AUTH_027);
        }
        redisTemplate.delete(challengeKey);

        // TODO: WebAuthn4J統合時に署名検証を実装する
        log.warn("WebAuthn署名検証はダミー実装です。WebAuthn4J統合時に正式実装してください。");

        // 3. sign_count検証（リプレイ攻撃防止）
        if (req.getSignCount() <= credential.getSignCount()) {
            throw new BusinessException(AuthErrorCode.AUTH_026);
        }

        // 4. sign_count更新 + lastUsedAt更新
        // sign_countはリクエストの値で上書き
        WebAuthnCredentialEntity updated = credential.toBuilder()
                .signCount(req.getSignCount())
                .build();
        updated.updateLastUsedAt();
        webAuthnCredentialRepository.save(updated);

        // 5. Access Token + Refresh Token発行
        TokenResponse tokenResponse = issueTokenPair(userId, ipAddress, userAgent);

        // 6. イベント発行
        eventPublisher.publish(new LoginSuccessEvent(userId, "WEBAUTHN", ipAddress, userAgent));

        return ApiResponse.of(tokenResponse);
    }

    /**
     * ユーザーの登録済みWebAuthn資格情報一覧を取得する。
     *
     * @param userId ユーザーID
     * @return 資格情報レスポンスリスト
     */
    public ApiResponse<List<WebAuthnCredentialResponse>> getCredentials(Long userId) {
        List<WebAuthnCredentialResponse> credentials = webAuthnCredentialRepository.findByUserId(userId).stream()
                .map(this::toCredentialResponse)
                .collect(Collectors.toList());

        return ApiResponse.of(credentials);
    }

    /**
     * WebAuthn資格情報のデバイス名を更新する。
     *
     * @param userId       ユーザーID
     * @param credentialId 資格情報ID
     * @param req          更新リクエスト
     * @return 更新後の資格情報レスポンス
     */
    @Transactional
    public ApiResponse<WebAuthnCredentialResponse> updateCredentialName(
            Long userId, Long credentialId, UpdateWebAuthnCredentialRequest req) {

        WebAuthnCredentialEntity credential = webAuthnCredentialRepository.findById(credentialId)
                .orElseThrow(() -> new BusinessException(AuthErrorCode.AUTH_024));

        // 所有者チェック
        if (!credential.getUserId().equals(userId)) {
            throw new BusinessException(AuthErrorCode.AUTH_024);
        }

        WebAuthnCredentialEntity updated = credential.toBuilder()
                .deviceName(req.getDeviceName())
                .build();
        webAuthnCredentialRepository.save(updated);

        return ApiResponse.of(toCredentialResponse(updated));
    }

    /**
     * WebAuthn資格情報を削除する。
     *
     * @param userId       ユーザーID
     * @param credentialId 資格情報ID
     */
    @Transactional
    public void deleteCredential(Long userId, Long credentialId) {
        WebAuthnCredentialEntity credential = webAuthnCredentialRepository.findById(credentialId)
                .orElseThrow(() -> new BusinessException(AuthErrorCode.AUTH_024));

        // 所有者チェック
        if (!credential.getUserId().equals(userId)) {
            throw new BusinessException(AuthErrorCode.AUTH_024);
        }

        webAuthnCredentialRepository.delete(credential);
    }

    // === ヘルパーメソッド ===

    /**
     * WebAuthnチャレンジを生成する。
     */
    private String generateChallenge() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
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
     * WebAuthnCredentialEntity をレスポンスDTOに変換する。
     */
    private WebAuthnCredentialResponse toCredentialResponse(WebAuthnCredentialEntity entity) {
        return new WebAuthnCredentialResponse(
                entity.getId(),
                entity.getCredentialId(),
                entity.getDeviceName(),
                entity.getAaguid(),
                entity.getLastUsedAt(),
                entity.getCreatedAt());
    }
}
