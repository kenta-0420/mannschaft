package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.AuthErrorCode;
import com.mannschaft.app.auth.dto.TokenResponse;
import com.mannschaft.app.auth.entity.RefreshTokenEntity;
import com.mannschaft.app.auth.event.DeviceFingerprintMismatchEvent;
import com.mannschaft.app.auth.event.TokenReuseDetectedEvent;
import com.mannschaft.app.auth.repository.RefreshTokenRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.DomainEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Refresh Token ローテーションサービス。
 * Refresh Token検証 → 並行更新の正規化 / リプレイ攻撃検出 → デバイスフィンガープリント検証 → 新トークン発行を担う。
 *
 * <h2>並行更新（自爆バグ）の根治方式</h2>
 * <p>複数デバイス／タブが同一 Refresh Token でほぼ同時に refresh を叩くと、片方が旧トークンを
 * revoke した直後にもう片方が使用済みトークンを再提示し得る。従来はこれを一律リプレイと誤判定して
 * {@link AuthSessionService#logoutAllDevices(Long)} を発火させ、全セッションを永久無効化していた
 * （ユーザーが 401 から回復不能に陥る自爆）。</p>
 *
 * <p>本サービスは以下の 2 点で根治する:</p>
 * <ol>
 *   <li><b>DB 行ロックで直列化</b> — {@link RefreshTokenRepository#findByTokenHashForUpdate(String)}
 *       （{@code PESSIMISTIC_WRITE}）で取得し、同一トークンへの並行 refresh をトランザクションで直列化する。</li>
 *   <li><b>grace window で並行更新を正規化</b> — 正規ローテーション時は旧トークンに後継ポインタ
 *       （{@link RefreshTokenEntity#markRotated(String)}）を記録する。後継ポインタ有りの失効済みトークンが
 *       grace window（{@code mannschaft.jwt.refresh-rotation-grace-seconds}）以内に再提示された場合は、
 *       並行更新の負け側とみなしてリプレイ扱いにせず新トークンを発行する（2 タブとも有効トークンへ収束）。
 *       grace 超過の後継有りトークン再提示のみを真リプレイとして全セッション無効化する。</li>
 * </ol>
 *
 * <p>リプレイ検出時の全デバイス無効化（logoutAllDevices）は {@link AuthSessionService} へ委譲する。</p>
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class AuthTokenRotationService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final AuthTokenService authTokenService;
    private final AuthSessionService authSessionService;
    private final DomainEventPublisher eventPublisher;
    private final RoleClaimResolver roleClaimResolver;

    /**
     * リフレッシュトークン ローテーションの grace window（秒）。
     * 並行 refresh の負け側（後継ポインタ有りの失効済みトークン）をリプレイ扱いにせず正規化する猶予。
     *
     * <p>{@code @RequiredArgsConstructor} の対象外（非 final）とし、Spring のフィールドインジェクションで
     * プロパティ値を注入する。単体テストでは {@code @InjectMocks} がコンストラクタ経由で生成するため
     * 本フィールドは注入されず、Java 初期化子の既定値 60 のまま使われる。</p>
     */
    @Value("${mannschaft.jwt.refresh-rotation-grace-seconds:60}")
    private long refreshRotationGraceSeconds = 60L;

    /**
     * Refresh Tokenを検証し、新しいAccess Token + Refresh Tokenペアを発行する。
     *
     * <p>並行更新は grace window 内なら正規化し、真リプレイ（後継有り × grace 超過）検出時のみ
     * 全トークンを無効化する。</p>
     *
     * @param rawRefreshToken   平文Refresh Token
     * @param deviceFingerprint デバイスフィンガープリント
     * @return 新しいトークンペア
     */
    @Transactional
    public ApiResponse<TokenResponse> refreshAccessToken(String rawRefreshToken, String deviceFingerprint) {
        // 1. SHA-256ハッシュ化 → 悲観ロック付きで取得（同一トークンの並行 refresh を DB 行ロックで直列化）
        String tokenHash = authTokenService.hashToken(rawRefreshToken);
        RefreshTokenEntity existingToken = refreshTokenRepository.findByTokenHashForUpdate(tokenHash)
                .orElseThrow(() -> new BusinessException(AuthErrorCode.AUTH_007));

        // 2. 失効済みトークンの再提示 → 種別に応じて分岐
        if (existingToken.getRevokedAt() != null) {
            if (existingToken.getReplacedByTokenHash() != null) {
                // ローテーションで正規に置換された（後継ポインタ有り）トークン。
                long secondsSinceRevoke = Duration
                        .between(existingToken.getRevokedAt(), LocalDateTime.now())
                        .getSeconds();

                if (secondsSinceRevoke <= refreshRotationGraceSeconds) {
                    // 2-a. grace window 内 → 並行更新の負け側。リプレイ扱いにせず正規化し新トークンを発行する。
                    // logoutAllDevices は呼ばない（自爆の根治点）。
                    log.info("並行 refresh を grace window 内で正規化: userId={}, tokenId={}, sinceRevoke={}s（grace={}s）",
                            existingToken.getUserId(), existingToken.getId(),
                            secondsSinceRevoke, refreshRotationGraceSeconds);
                    return issueRotatedTokens(existingToken, false);
                }

                // 2-b. grace 超過の後継有りトークン再提示 → 真リプレイ攻撃 → 全トークン無効化。
                log.warn("リプレイ攻撃検出（grace 超過の後継有りトークン再提示）: userId={}, tokenId={}, sinceRevoke={}s（grace={}s）",
                        existingToken.getUserId(), existingToken.getId(),
                        secondsSinceRevoke, refreshRotationGraceSeconds);
                eventPublisher.publish(new TokenReuseDetectedEvent(existingToken.getUserId(), existingToken.getId()));
                authSessionService.logoutAllDevices(existingToken.getUserId());
                throw new BusinessException(AuthErrorCode.AUTH_026);
            }

            // 2-c. 後継ポインタ無しの失効（明示ログアウト等）→ grace 対象外。
            // リプレイ扱い・全無効化はせず、無効/失効済みとして AUTH_007 を返す。
            log.warn("失効済みリフレッシュトークンの再提示（後継無し・明示ログアウト等）: userId={}, tokenId={}",
                    existingToken.getUserId(), existingToken.getId());
            throw new BusinessException(AuthErrorCode.AUTH_007);
        }

        // 3. 有効期限チェック。
        // 旧実装は退会申請不存在の AUTH_032 を誤用していた。期限切れは「無効/失効済み」の意味論に沿う AUTH_007 を返す。
        if (existingToken.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(AuthErrorCode.AUTH_007);
        }

        // 4. デバイスフィンガープリント不一致 → WARNログ + 監査ログイベント発行（ソフトモード）
        if (deviceFingerprint != null && existingToken.getDeviceFingerprint() != null
                && !deviceFingerprint.equals(existingToken.getDeviceFingerprint())) {
            log.warn("デバイスフィンガープリント不一致: userId={}, tokenId={}",
                    existingToken.getUserId(), existingToken.getId());
            eventPublisher.publish(new DeviceFingerprintMismatchEvent(existingToken.getUserId(), existingToken.getId()));
        }

        // 5. 正規ローテーション: 旧トークンに後継ポインタを記録して失効し、新トークンを発行する。
        return issueRotatedTokens(existingToken, true);
    }

    /**
     * 指定トークンを基点に新しい Access Token + Refresh Token ペアを発行する。
     *
     * @param source            発行元となる（アイデンティティを引き継ぐ）Refresh Token
     * @param markSourceRotated true の場合、発行元トークンに後継ポインタを記録して失効させる
     *                          （正規ローテーション経路）。false の場合は発行元を変更しない
     *                          （grace window 内の並行更新正規化経路。発行元は既に失効済み）。
     * @return 新しいトークンペア
     */
    private ApiResponse<TokenResponse> issueRotatedTokens(RefreshTokenEntity source, boolean markSourceRotated) {
        Long userId = source.getUserId();
        if (!userRepository.existsById(userId)) {
            throw new BusinessException(AuthErrorCode.AUTH_007);
        }

        // 認可基盤完全根治 Phase 1（§3.2.2）: リフレッシュ時も user_roles から SYSTEM_ADMIN を
        // 再判定する。これにより SYSTEM_ADMIN を剥奪されたユーザーは次回リフレッシュ（最長 15 分）で
        // SYSTEM_ADMIN authority を失う。即時失効が必要な場合は剥奪処理側で
        // AuthTokenService#setUserInvalidationTimestamp を併用する（§6）。
        String newAccessToken = authTokenService.issueAccessToken(userId, roleClaimResolver.resolveRoles(userId));
        String newRawRefreshToken = authTokenService.generateRefreshToken();
        String newTokenHash = authTokenService.hashToken(newRawRefreshToken);
        // ローテーション時も新しい jti を生成する
        String newRefreshTokenJti = UUID.randomUUID().toString();

        if (markSourceRotated) {
            // 旧トークンに後継ポインタを記録して失効（並行更新の正規化・真リプレイ判定の基点）
            source.markRotated(newTokenHash);
        }

        RefreshTokenEntity newToken = RefreshTokenEntity.builder()
                .userId(userId)
                .tokenHash(newTokenHash)
                .jti(newRefreshTokenJti)
                .rememberMe(source.getRememberMe())
                .deviceFingerprint(source.getDeviceFingerprint())
                .ipAddress(source.getIpAddress())
                .userAgent(source.getUserAgent())
                .expiresAt(LocalDateTime.now().plusSeconds(authTokenService.getRefreshTokenExpirationSeconds()))
                .build();
        refreshTokenRepository.save(newToken);

        return ApiResponse.of(new TokenResponse(
                newAccessToken, newRawRefreshToken, newToken.getId(), authTokenService.getAccessTokenExpirationSeconds()));
    }
}
