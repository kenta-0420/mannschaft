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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Refresh Token ローテーションサービス。
 * Refresh Token検証 → リプレイ攻撃検出 → デバイスフィンガープリント検証 → 新トークン発行を担う。
 *
 * <p>セキュリティ重要: 全ロジック・トランザクション境界・イベント発行タイミング・エラーコードは
 * AuthService から移送した byte-identical な実装である。
 * リプレイ検出時の全デバイス無効化（logoutAllDevices）は {@link AuthSessionService} へ委譲する。</p>
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
     * Refresh Tokenを検証し、新しいAccess Token + Refresh Tokenペアを発行する。
     * リプレイ攻撃検出時は全トークンを無効化する。
     *
     * @param rawRefreshToken   平文Refresh Token
     * @param deviceFingerprint デバイスフィンガープリント
     * @return 新しいトークンペア
     */
    @Transactional
    public ApiResponse<TokenResponse> refreshAccessToken(String rawRefreshToken, String deviceFingerprint) {
        // 1. SHA-256ハッシュ化 → DB検索
        String tokenHash = authTokenService.hashToken(rawRefreshToken);
        RefreshTokenEntity existingToken = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new BusinessException(AuthErrorCode.AUTH_007));

        // 2. revoked_at設定済み → リプレイ攻撃の疑い → 全トークン無効化
        if (existingToken.getRevokedAt() != null) {
            log.warn("リプレイ攻撃の疑い検出: userId={}, tokenId={}",
                    existingToken.getUserId(), existingToken.getId());
            eventPublisher.publish(new TokenReuseDetectedEvent(existingToken.getUserId(), existingToken.getId()));
            authSessionService.logoutAllDevices(existingToken.getUserId());
            throw new BusinessException(AuthErrorCode.AUTH_029);
        }

        // 有効期限チェック
        if (existingToken.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(AuthErrorCode.AUTH_032);
        }

        // 3. デバイスフィンガープリント不一致 → WARNログ + 監査ログイベント発行（ソフトモード）
        if (deviceFingerprint != null && existingToken.getDeviceFingerprint() != null
                && !deviceFingerprint.equals(existingToken.getDeviceFingerprint())) {
            log.warn("デバイスフィンガープリント不一致: userId={}, tokenId={}",
                    existingToken.getUserId(), existingToken.getId());
            eventPublisher.publish(new DeviceFingerprintMismatchEvent(existingToken.getUserId(), existingToken.getId()));
        }

        // 4. 旧トークンを失効
        existingToken.revoke();

        // 5. 新Access Token + 新Refresh Token発行
        Long userId = existingToken.getUserId();
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

        RefreshTokenEntity newToken = RefreshTokenEntity.builder()
                .userId(userId)
                .tokenHash(newTokenHash)
                .jti(newRefreshTokenJti)
                .rememberMe(existingToken.getRememberMe())
                .deviceFingerprint(existingToken.getDeviceFingerprint())
                .ipAddress(existingToken.getIpAddress())
                .userAgent(existingToken.getUserAgent())
                .expiresAt(LocalDateTime.now().plusSeconds(authTokenService.getRefreshTokenExpirationSeconds()))
                .build();
        refreshTokenRepository.save(newToken);

        return ApiResponse.of(new TokenResponse(
                newAccessToken, newRawRefreshToken, newToken.getId(), authTokenService.getAccessTokenExpirationSeconds()));
    }
}
