package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.AuthErrorCode;
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
import com.mannschaft.app.auth.dto.WebAuthnReauthenticateBeginResponse;
import com.mannschaft.app.auth.dto.WebAuthnReauthenticateCompleteRequest;
import com.mannschaft.app.auth.dto.WebAuthnReauthenticateCompleteResponse;
import com.mannschaft.app.auth.dto.WebAuthnRegisterBeginResponse;
import com.mannschaft.app.auth.dto.WebAuthnRegisterCompleteRequest;
import com.mannschaft.app.auth.event.LoginSuccessEvent;
import com.mannschaft.app.auth.event.WebAuthnCredentialRemovedEvent;
import com.mannschaft.app.auth.event.WebAuthnLoginEvent;
import com.mannschaft.app.auth.event.WebAuthnLoginFailedEvent;
import com.mannschaft.app.auth.event.WebAuthnRegisteredEvent;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.DomainEventPublisher;
import com.webauthn4j.WebAuthnManager;
import com.webauthn4j.converter.exception.DataConversionException;
import com.webauthn4j.data.AuthenticationData;
import com.webauthn4j.data.AuthenticationParameters;
import com.webauthn4j.data.AuthenticationRequest;
import com.webauthn4j.data.RegistrationData;
import com.webauthn4j.data.RegistrationParameters;
import com.webauthn4j.data.RegistrationRequest;
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.server.ServerProperty;
import com.webauthn4j.verifier.exception.VerificationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * WebAuthn（パスキー・FIDO2）認証サービス。
 * 資格情報の登録・ログイン・管理を担当する。
 * <p>
 * WebAuthn4Jライブラリを使用した attestation / assertion 検証を実装済み。
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
    private final RoleClaimResolver roleClaimResolver;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String CHALLENGE_KEY_PREFIX = "mannschaft:auth:webauthn_challenge:";
    /**
     * F18 提示モード追加保護用の再認証チャレンジキープレフィックス（設計書 §9.6）。
     * 既存のログイン用 {@code webauthn_challenge:login:*} とは独立した名前空間にして
     * 誤って AT/RT を発行する completeLogin との混線を防ぐ。
     */
    private static final String REAUTH_CHALLENGE_KEY_PREFIX = "mannschaft:auth:webauthn_reauth_challenge:";
    /**
     * F18 提示モード追加保護用の「再認証済みフラグ」キープレフィックス（設計書 §9.6 / POINT_CARD_009）。
     * 値は固定文字列 "1"。TTL は 5 分。提示モード開始 API は本キーの存在を確認し、即削除する。
     */
    private static final String REAUTH_VERIFIED_KEY_PREFIX = "mannschaft:auth:webauthn_reauth_verified:";
    private static final int CHALLENGE_TTL_MINUTES = 5;
    private static final int REAUTH_VERIFIED_TTL_MINUTES = 5;
    private static final String RP_NAME = "Mannschaft";

    private final WebAuthnManager webAuthnManager = WebAuthnManager.createNonStrictWebAuthnManager();

    @Value("${mannschaft.webauthn.rp-id:mannschaft.app}")
    private String rpId;

    @Value("${mannschaft.webauthn.origin:https://mannschaft.app}")
    private String rpOrigin;

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
                challenge, rpId, RP_NAME, userId, user.getLastName() + " " + user.getFirstName());

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
     * WebAuthn4Jによる attestation 検証を実施する。
     * </p>
     *
     * @param userId ユーザーID
     * @param req    登録完了リクエスト
     * @return メッセージレスポンス
     */
    @SuppressWarnings("deprecation")
    @Transactional
    public ApiResponse<MessageResponse> completeRegister(Long userId, WebAuthnRegisterCompleteRequest req) {
        // 1. チャレンジ検証
        String challengeKey = CHALLENGE_KEY_PREFIX + "register:" + userId;
        String storedChallenge = redisTemplate.opsForValue().get(challengeKey);
        if (storedChallenge == null) {
            throw new BusinessException(AuthErrorCode.AUTH_027);
        }
        redisTemplate.delete(challengeKey);

        // WebAuthn4J attestation 検証 + CBOR公開鍵抽出 + 保存
        try {
            byte[] attestationObject = Base64.getUrlDecoder().decode(req.getAttestationObject());
            byte[] clientDataJson = Base64.getUrlDecoder().decode(req.getClientDataJson());

            RegistrationRequest registrationRequest = new RegistrationRequest(attestationObject, clientDataJson);

            ServerProperty serverProperty = new ServerProperty(
                    new Origin(rpOrigin),
                    rpId,
                    new DefaultChallenge(Base64.getUrlDecoder().decode(storedChallenge)),
                    null
            );
            RegistrationParameters registrationParameters = new RegistrationParameters(
                    serverProperty, null, false, false);

            RegistrationData registrationData = webAuthnManager.parse(registrationRequest);
            webAuthnManager.validate(registrationData, registrationParameters);

            // RegistrationData から COSEKey を抽出して CBOR シリアライズ
            // FE から送られる getPublicKey() は DER(SubjectPublicKeyInfo)形式のため使用不可
            com.webauthn4j.data.attestation.authenticator.COSEKey parsedCoseKey =
                    registrationData.getAttestationObject()
                            .getAuthenticatorData()
                            .getAttestedCredentialData()
                            .getCOSEKey();
            com.webauthn4j.converter.util.ObjectConverter regObjectConverter =
                    new com.webauthn4j.converter.util.ObjectConverter();
            byte[] coseKeyBytes = regObjectConverter.getCborConverter().writeValueAsBytes(parsedCoseKey);
            String coseKeyB64url = Base64.getUrlEncoder().withoutPadding().encodeToString(coseKeyBytes);

            // 認証器が割り当てた credentialId を取得
            byte[] parsedCredIdBytes = registrationData.getAttestationObject()
                    .getAuthenticatorData()
                    .getAttestedCredentialData()
                    .getCredentialId();
            String parsedCredIdB64url = Base64.getUrlEncoder().withoutPadding().encodeToString(parsedCredIdBytes);

            // 2. credential_id重複チェック
            if (webAuthnCredentialRepository.findByCredentialId(parsedCredIdB64url).isPresent()) {
                throw new BusinessException(AuthErrorCode.AUTH_025);
            }

            // 3. WebAuthnCredentialEntity保存
            WebAuthnCredentialEntity credential = WebAuthnCredentialEntity.builder()
                    .userId(userId)
                    .credentialId(parsedCredIdB64url)
                    .publicKey(coseKeyB64url)
                    .signCount(0L)
                    .deviceName(req.getDeviceName())
                    .aaguid(req.getAaguid())
                    .build();
            webAuthnCredentialRepository.save(credential);

        } catch (DataConversionException | VerificationException e) {
            log.warn("WebAuthn attestation検証失敗: userId={}", userId, e);
            throw new BusinessException(AuthErrorCode.AUTH_024, e);
        }

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
                challenge, rpId, allowCredentials, 300000L);

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
     * WebAuthn4Jによる署名検証を実施する。
     * </p>
     *
     * @param req       ログイン完了リクエスト
     * @param ipAddress リクエスト元IPアドレス
     * @param userAgent ユーザーエージェント
     * @return トークンレスポンス
     */
    @SuppressWarnings("deprecation")
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

        // WebAuthn4J 署名検証
        try {
            byte[] credentialIdBytes = Base64.getUrlDecoder().decode(req.getCredentialId());
            byte[] authenticatorData = Base64.getUrlDecoder().decode(req.getAuthenticatorData());
            byte[] clientDataJson = Base64.getUrlDecoder().decode(req.getClientDataJson());
            byte[] signature = Base64.getUrlDecoder().decode(req.getSignature());

            AuthenticationRequest authenticationRequest = new AuthenticationRequest(
                    credentialIdBytes, authenticatorData, clientDataJson, signature);

            ServerProperty serverProperty = new ServerProperty(
                    new Origin(rpOrigin),
                    rpId,
                    new DefaultChallenge(Base64.getUrlDecoder().decode(storedChallenge)),
                    null
            );

            // 保存済み公開鍵からAuthenticatorを構築
            com.webauthn4j.converter.util.ObjectConverter objectConverter =
                    new com.webauthn4j.converter.util.ObjectConverter();
            com.webauthn4j.data.attestation.authenticator.COSEKey coseKey =
                    objectConverter.getCborConverter().readValue(
                            Base64.getUrlDecoder().decode(credential.getPublicKey()),
                            com.webauthn4j.data.attestation.authenticator.COSEKey.class);
            com.webauthn4j.authenticator.Authenticator authenticator =
                    new com.webauthn4j.authenticator.AuthenticatorImpl(
                            new AttestedCredentialData(null, credentialIdBytes, coseKey),
                            null, credential.getSignCount());

            AuthenticationParameters authenticationParameters = new AuthenticationParameters(
                    serverProperty, authenticator, null, false, false);

            AuthenticationData authenticationData = webAuthnManager.parse(authenticationRequest);
            webAuthnManager.validate(authenticationData, authenticationParameters);
        } catch (DataConversionException | VerificationException e) {
            log.warn("WebAuthn署名検証失敗: credentialId={}", req.getCredentialId(), e);
            eventPublisher.publish(new WebAuthnLoginFailedEvent(userId, ipAddress, userAgent, req.getCredentialId()));
            throw new BusinessException(AuthErrorCode.AUTH_024, e);
        }

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
        // WEBAUTHN_LOGIN として別途監査ログを記録する
        eventPublisher.publish(new WebAuthnLoginEvent(userId, ipAddress, userAgent, req.getCredentialId()));

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

        // イベント発行
        eventPublisher.publish(new WebAuthnCredentialRemovedEvent(userId, credentialId));
    }

    // ─────────────────────────────────────────────
    // F18 提示モード追加保護: WebAuthn 再認証（設計書 §9.6 / POINT_CARD_009）
    // ─────────────────────────────────────────────

    /**
     * F18 提示モード追加保護のための WebAuthn 再認証を開始する。
     *
     * <p>既存の {@link #beginLogin(String)} と異なり、認証済みユーザー本人を対象にする。
     * チャレンジは {@code webauthn_reauth_challenge:{userId}} に 5 分 TTL で保存する。
     * AT/RT は発行せず、後段で {@link #completeReauthenticate} がフラグだけ書き込む。
     *
     * @param userId 現在のユーザー ID（{@code SecurityUtils.getCurrentUserId} で取得済み）
     * @return 再認証開始レスポンス
     */
    @Transactional
    public ApiResponse<WebAuthnReauthenticateBeginResponse> beginReauthenticate(Long userId) {
        UserEntity user = findUserOrThrow(userId);

        // 登録済み credential が無いとそもそも再認証できない。明確に AUTH_024 で失敗させる。
        List<WebAuthnCredentialEntity> credentials = webAuthnCredentialRepository.findByUserId(user.getId());
        if (credentials.isEmpty()) {
            throw new BusinessException(AuthErrorCode.AUTH_024);
        }

        List<String> allowCredentials = credentials.stream()
                .map(WebAuthnCredentialEntity::getCredentialId)
                .collect(Collectors.toList());

        String challenge = generateChallenge();
        String challengeKey = REAUTH_CHALLENGE_KEY_PREFIX + userId;
        redisTemplate.opsForValue().set(challengeKey, challenge, CHALLENGE_TTL_MINUTES, TimeUnit.MINUTES);

        WebAuthnReauthenticateBeginResponse response = new WebAuthnReauthenticateBeginResponse(
                challenge, rpId, allowCredentials, userId, 60_000L);
        return ApiResponse.of(response);
    }

    /**
     * F18 提示モード追加保護のための WebAuthn 再認証を完了する。
     *
     * <ol>
     *   <li>チャレンジ取得・削除（{@code webauthn_reauth_challenge:{userId}}）</li>
     *   <li>credential_id 検索 + 所有者確認（{@code userId} 不一致は IDOR 防止のため AUTH_024）</li>
     *   <li>WebAuthn4J による署名検証</li>
     *   <li>sign_count 増分検証 + 保存値更新</li>
     *   <li>「再認証済みフラグ」を 5 分 TTL で記録（{@code webauthn_reauth_verified:{userId}}）</li>
     * </ol>
     *
     * <p>本メソッドは絶対に AT/RT を発行しない。トークン rotation したい場合は
     * {@link #completeLogin} を使うこと。
     *
     * @param userId 現在のユーザー ID
     * @param req    再認証完了リクエスト
     * @return 期限 (verifiedUntil) のみを含むレスポンス
     */
    @SuppressWarnings("deprecation")
    @Transactional
    public ApiResponse<WebAuthnReauthenticateCompleteResponse> completeReauthenticate(
            Long userId, WebAuthnReauthenticateCompleteRequest req) {

        // 1. チャレンジ取得・即時削除（再生攻撃防止）
        String challengeKey = REAUTH_CHALLENGE_KEY_PREFIX + userId;
        String storedChallenge = redisTemplate.opsForValue().get(challengeKey);
        if (storedChallenge == null) {
            throw new BusinessException(AuthErrorCode.AUTH_027);
        }
        redisTemplate.delete(challengeKey);

        // 2. credential_id 検索 + 所有者確認
        WebAuthnCredentialEntity credential = webAuthnCredentialRepository
                .findByCredentialId(req.getCredentialId())
                .orElseThrow(() -> new BusinessException(AuthErrorCode.AUTH_024));
        // 他人の credential を指定された場合の IDOR 防止
        if (!userId.equals(credential.getUserId())) {
            throw new BusinessException(AuthErrorCode.AUTH_024);
        }

        // 3. WebAuthn4J 署名検証
        try {
            byte[] credentialIdBytes = Base64.getUrlDecoder().decode(req.getCredentialId());
            byte[] authenticatorData = Base64.getUrlDecoder().decode(req.getAuthenticatorData());
            byte[] clientDataJson = Base64.getUrlDecoder().decode(req.getClientDataJson());
            byte[] signature = Base64.getUrlDecoder().decode(req.getSignature());

            AuthenticationRequest authenticationRequest = new AuthenticationRequest(
                    credentialIdBytes, authenticatorData, clientDataJson, signature);

            ServerProperty serverProperty = new ServerProperty(
                    new Origin(rpOrigin),
                    rpId,
                    new DefaultChallenge(Base64.getUrlDecoder().decode(storedChallenge)),
                    null
            );

            com.webauthn4j.converter.util.ObjectConverter objectConverter =
                    new com.webauthn4j.converter.util.ObjectConverter();
            com.webauthn4j.data.attestation.authenticator.COSEKey coseKey =
                    objectConverter.getCborConverter().readValue(
                            Base64.getUrlDecoder().decode(credential.getPublicKey()),
                            com.webauthn4j.data.attestation.authenticator.COSEKey.class);
            com.webauthn4j.authenticator.Authenticator authenticator =
                    new com.webauthn4j.authenticator.AuthenticatorImpl(
                            new AttestedCredentialData(null, credentialIdBytes, coseKey),
                            null, credential.getSignCount());

            AuthenticationParameters authenticationParameters = new AuthenticationParameters(
                    serverProperty, authenticator, null, false, false);

            AuthenticationData authenticationData = webAuthnManager.parse(authenticationRequest);
            webAuthnManager.validate(authenticationData, authenticationParameters);
        } catch (DataConversionException | VerificationException e) {
            log.warn("WebAuthn 再認証署名検証失敗: userId={}, credentialId={}",
                    userId, req.getCredentialId(), e);
            throw new BusinessException(AuthErrorCode.AUTH_024, e);
        }

        // 4. sign_count 増分検証
        if (req.getSignCount() <= credential.getSignCount()) {
            throw new BusinessException(AuthErrorCode.AUTH_026);
        }

        // sign_count 更新 + lastUsedAt 更新（ログイン時と同じパターン）
        WebAuthnCredentialEntity updated = credential.toBuilder()
                .signCount(req.getSignCount())
                .build();
        updated.updateLastUsedAt();
        webAuthnCredentialRepository.save(updated);

        // 5. 再認証済みフラグを 5 分 TTL で記録
        String verifiedKey = REAUTH_VERIFIED_KEY_PREFIX + userId;
        redisTemplate.opsForValue().set(verifiedKey, "1",
                REAUTH_VERIFIED_TTL_MINUTES, TimeUnit.MINUTES);

        OffsetDateTime verifiedUntil =
                OffsetDateTime.now().plusMinutes(REAUTH_VERIFIED_TTL_MINUTES);
        return ApiResponse.of(new WebAuthnReauthenticateCompleteResponse(verifiedUntil));
    }

    /**
     * F18 提示モード追加保護のため、ユーザーが直近 5 分以内に WebAuthn 再認証済みかを返す。
     * {@link com.mannschaft.app.pointcard.service.PointCardGroupService#startPresentation}
     * から呼び出される。
     *
     * @param userId 現在のユーザー ID
     * @return フラグが Valkey に存在すれば true、なければ false
     */
    public boolean isReauthenticatedRecently(Long userId) {
        if (userId == null) return false;
        String key = REAUTH_VERIFIED_KEY_PREFIX + userId;
        Boolean exists = redisTemplate.hasKey(key);
        return Boolean.TRUE.equals(exists);
    }

    /**
     * F18 提示モード追加保護のフラグを 1 回限りで消費する。
     * 提示モード開始 API は本メソッドで即時無効化することで、同一フラグの再利用を防ぐ。
     *
     * @param userId 現在のユーザー ID
     */
    public void consumeReauthentication(Long userId) {
        if (userId == null) return;
        String key = REAUTH_VERIFIED_KEY_PREFIX + userId;
        redisTemplate.delete(key);
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
