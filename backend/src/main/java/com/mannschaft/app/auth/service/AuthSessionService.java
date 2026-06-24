package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.AuditEventCategory;
import com.mannschaft.app.auth.AuthErrorCode;
import com.mannschaft.app.auth.dto.AuditLogResponse;
import com.mannschaft.app.auth.dto.LoginHistoryResponse;
import com.mannschaft.app.auth.dto.SessionResponse;
import com.mannschaft.app.auth.entity.RefreshTokenEntity;
import com.mannschaft.app.auth.event.LogoutEvent;
import com.mannschaft.app.auth.event.LogoutEvent.LogoutType;
import com.mannschaft.app.auth.repository.RefreshTokenRepository;
import com.mannschaft.app.auth.util.UserAgentParser;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CursorPagedResponse;
import com.mannschaft.app.common.DomainEventPublisher;
import com.mannschaft.app.common.util.SessionHashUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * 認証セッション管理サービス。
 * ログアウト処理（単一・全デバイス・特定デバイス）、セッション一覧、デバイス名更新、
 * ログイン履歴、セッション上限管理を担う。
 *
 * <p>セキュリティ重要: 全メソッドのロジック・トランザクション境界・イベント発行タイミング・
 * エラーコードは AuthService から移送した byte-identical な実装である。</p>
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class AuthSessionService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final AuthTokenService authTokenService;
    private final DomainEventPublisher eventPublisher;
    private final AuditLogQueryService auditLogQueryService;

    // セッション上限
    private static final int MAX_ACTIVE_SESSIONS = 10;

    // ========================================
    // ログアウト
    // ========================================

    /**
     * 単一デバイスからのログアウトを行う。
     * Refresh Token失効 + JTIブラックリスト追加。
     *
     * @param refreshTokenHash Refresh TokenのSHA-256ハッシュ
     * @param jti              Access TokenのJTI
     * @param expEpoch         Access Tokenの有効期限（epoch秒）
     */
    @Transactional
    public void logout(String refreshTokenHash, String jti, long expEpoch) {
        // 1. RefreshToken失効
        refreshTokenRepository.findByTokenHash(refreshTokenHash)
                .ifPresent(token -> {
                    token.revoke();
                    Long userId = token.getUserId();

                    // 2. JTIブラックリスト追加（残存TTL）
                    // Instant.now() で UTC epoch 秒を正確に取得する。
                    // LocalDateTime.now().toEpochSecond(UTC) は JVM TZ が非 UTC の場合にズレが生じるため使用禁止。
                    long remainingTtl = expEpoch - Instant.now().getEpochSecond();
                    // Valkey 障害時も @Transactional によるDB ロールバックを防ぐため try-catch で囲む。
                    // リフレッシュトークンは DB 側で失効済みのため新トークン発行は不可。
                    // アクセストークンは最大 remainingTtl 秒後に自然失効する（許容範囲）。
                    try {
                        authTokenService.addJtiToBlacklist(jti, remainingTtl);
                    } catch (Exception e) {
                        log.error("ログアウト時のJTIブラックリスト追加失敗（アクセストークンが{}秒有効なままになる可能性）: jti={}, error={}",
                                remainingTtl, jti, e.getMessage());
                    }

                    // 3. session_hash 計算（refresh_token の jti から）
                    String sessionHash = token.getJti() != null && !token.getJti().isBlank()
                            ? SessionHashUtil.hash(token.getJti()) : null;

                    // 4. イベント発行
                    eventPublisher.publish(new LogoutEvent(userId, 1, LogoutType.SESSION, null, sessionHash));
                });
    }

    /**
     * 全デバイスからのログアウトを行う（後方互換）。
     * 全RefreshToken失効 + Valkeyにuser_invalidated_at設定。
     *
     * @param userId ユーザーID
     */
    @Transactional
    public void logoutAllDevices(Long userId) {
        logoutAllDevices(userId, null, null, false);
    }

    /**
     * 全デバイスからのログアウトを行う。
     * keepCurrent=true の場合は現セッションを除外して無効化する。
     *
     * @param userId           ユーザーID
     * @param currentTokenHash 現セッションのトークンハッシュ（nullable）
     * @param currentSessionId 現セッションのID（nullable）
     * @param keepCurrent      true の場合、現セッションを維持する
     */
    @Transactional
    public void logoutAllDevices(Long userId, String currentTokenHash, Long currentSessionId, boolean keepCurrent) {
        // 1. 全アクティブRefreshToken取得
        List<RefreshTokenEntity> activeTokens = refreshTokenRepository
                .findByUserIdAndRevokedAtIsNull(userId);

        if (keepCurrent) {
            // 現セッションを特定
            RefreshTokenEntity currentToken = activeTokens.stream()
                    .filter(t -> isCurrentSession(t, currentTokenHash, currentSessionId))
                    .findFirst()
                    .orElse(null);

            if (currentToken != null) {
                // 現セッション以外を revoke
                int deviceCount = 0;
                for (RefreshTokenEntity token : activeTokens) {
                    if (!token.getId().equals(currentToken.getId())) {
                        token.revoke();
                        deviceCount++;
                    }
                }
                // user_invalidated_at設定（Valkey）。障害時もDB revoke は維持する。
                try {
                    authTokenService.setUserInvalidationTimestamp(userId);
                } catch (Exception e) {
                    log.error("全デバイスログアウト時のuser_invalidated_at設定失敗（既存トークンが最大15分有効なままになる可能性）: userId={}, error={}",
                            userId, e.getMessage());
                }
                eventPublisher.publish(new LogoutEvent(userId, deviceCount, LogoutType.ALL_SESSIONS));
                return;
            }
            // 現セッション特定不可 → keepCurrent=false にフォールバック（全無効化）
        }

        // 全無効化
        int deviceCount = activeTokens.size();
        activeTokens.forEach(RefreshTokenEntity::revoke);

        // 2. user_invalidated_at設定（Valkey）。障害時もDB revoke は維持する。
        try {
            authTokenService.setUserInvalidationTimestamp(userId);
        } catch (Exception e) {
            log.error("全デバイスログアウト時のuser_invalidated_at設定失敗（既存トークンが最大15分有効なままになる可能性）: userId={}, error={}",
                    userId, e.getMessage());
        }

        // 3. イベント発行
        eventPublisher.publish(new LogoutEvent(userId, deviceCount, LogoutType.ALL_SESSIONS));
    }

    /**
     * 特定デバイスからのログアウトを行う。
     *
     * @param userId           ユーザーID
     * @param refreshTokenId   Refresh TokenのID
     * @param currentTokenHash 現セッションのトークンハッシュ（nullable）
     * @param currentSessionId 現セッションのID（nullable）
     */
    @Transactional
    public void logoutDevice(Long userId, Long refreshTokenId, String currentTokenHash, Long currentSessionId) {
        RefreshTokenEntity token = refreshTokenRepository.findById(refreshTokenId)
                .filter(t -> t.getUserId().equals(userId))
                .orElseThrow(() -> new BusinessException(AuthErrorCode.AUTH_033));

        // 既に無効化済み or 期限切れのトークンは対象外
        if (token.getRevokedAt() != null || token.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(AuthErrorCode.AUTH_033);
        }

        // 現セッションの無効化は禁止
        if (isCurrentSession(token, currentTokenHash, currentSessionId)) {
            throw new BusinessException(AuthErrorCode.AUTH_034);
        }

        token.revoke();
        eventPublisher.publish(new LogoutEvent(userId, 1, LogoutType.SESSION, refreshTokenId));
    }

    // ========================================
    // セッション
    // ========================================

    /**
     * ユーザーのアクティブセッション一覧を取得する。
     *
     * @param userId           ユーザーID
     * @param currentTokenHash 現セッションのトークンハッシュ（nullable）
     * @param currentSessionId 現セッションのID（nullable）
     * @return セッション一覧（isCurrent=true が先頭、以降 lastUsedAt 降順）
     */
    public ApiResponse<List<SessionResponse>> getSessions(Long userId, String currentTokenHash, Long currentSessionId) {
        List<RefreshTokenEntity> activeTokens = refreshTokenRepository
                .findByUserIdAndRevokedAtIsNull(userId);

        List<SessionResponse> sessions = activeTokens.stream()
                .filter(token -> token.getExpiresAt().isAfter(LocalDateTime.now()))
                .map(token -> mapToSessionResponse(token, currentTokenHash, currentSessionId))
                .sorted(Comparator
                        .comparing(SessionResponse::isCurrent, Comparator.reverseOrder())
                        .thenComparing(SessionResponse::getLastUsedAt,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();

        return ApiResponse.of(sessions);
    }

    /**
     * セッションのデバイス名を更新する。
     *
     * @param userId        ユーザーID
     * @param sessionId     セッションID
     * @param newDeviceName 新しいデバイス名
     * @return 更新後のセッション情報
     */
    @Transactional
    public ApiResponse<SessionResponse> updateSessionDeviceName(Long userId, Long sessionId, String newDeviceName) {
        RefreshTokenEntity token = refreshTokenRepository.findById(sessionId)
                .filter(t -> t.getUserId().equals(userId))
                .orElseThrow(() -> new BusinessException(AuthErrorCode.AUTH_033));

        // 無効化済み or 期限切れチェック
        if (token.getRevokedAt() != null || token.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(AuthErrorCode.AUTH_033);
        }

        token.updateDeviceName(newDeviceName);
        refreshTokenRepository.save(token);

        // Controller側で判定情報がないため isCurrent=false で返す
        SessionResponse response = mapToSessionResponse(token, null, null);
        return ApiResponse.of(response);
    }

    /**
     * ユーザーのログイン履歴をカーソルベースで取得する。
     *
     * @param userId ユーザーID
     * @param cursor カーソル（null=先頭から）
     * @param limit  取得件数
     * @return ログイン履歴
     */
    public CursorPagedResponse<LoginHistoryResponse> getLoginHistory(Long userId, String cursor, int limit) {
        CursorPagedResponse<AuditLogResponse> logs = auditLogQueryService.getMyLogs(
                userId,
                null,
                List.of(AuditEventCategory.AUTH),
                null,
                null,
                cursor,
                limit);

        List<LoginHistoryResponse> history = logs.getData().stream()
                .map(log -> new LoginHistoryResponse(
                        log.getId(),
                        log.getEventType(),
                        log.getIpAddress(),
                        log.getUserAgent(),
                        resolveMethod(log.getEventType()),
                        log.getCreatedAt()))
                .toList();

        return CursorPagedResponse.of(history, logs.getMeta());
    }

    private String resolveMethod(String eventType) {
        if (eventType == null) return null;
        return switch (eventType) {
            case "WEBAUTHN_LOGIN", "WEBAUTHN_LOGIN_FAILED" -> "WebAuthn";
            default -> null;
        };
    }

    // ========================================
    // セッション上限（login から呼ばれる）
    // ========================================

    /**
     * アクティブセッション数が上限を超過している場合、古いセッションから順に無効化する。
     *
     * @param userId ユーザーID
     */
    public void enforceMaxActiveSessions(Long userId) {
        LocalDateTime now = LocalDateTime.now();
        long activeCount = refreshTokenRepository
                .countByUserIdAndRevokedAtIsNullAndExpiresAtAfter(userId, now);

        if (activeCount <= MAX_ACTIVE_SESSIONS) {
            return;
        }

        List<RefreshTokenEntity> activeTokens = refreshTokenRepository
                .findByUserIdAndRevokedAtIsNull(userId);

        // lastUsedAt ASC（null は最後尾）でソートし、上限 - 1 以下になるまで古い順に revoke
        activeTokens.stream()
                .filter(t -> t.getExpiresAt().isAfter(now))
                .sorted(Comparator.comparing(RefreshTokenEntity::getLastUsedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .limit(activeCount - MAX_ACTIVE_SESSIONS + 1)
                .forEach(RefreshTokenEntity::revoke);
    }

    // ========================================
    // ヘルパー（package-private: 同パッケージのサブサービスから利用可）
    // ========================================

    /**
     * RefreshTokenEntity から SessionResponse を生成する共通ヘルパー。
     */
    private SessionResponse mapToSessionResponse(RefreshTokenEntity token,
                                                  String currentTokenHash,
                                                  Long currentSessionId) {
        return new SessionResponse(
                token.getId(),
                resolveDeviceName(token),
                resolveDeviceType(token),
                token.getIpAddress(),
                token.getUserAgent(),
                token.getRememberMe(),
                token.getCreatedAt(),
                token.getLastUsedAt(),
                token.getExpiresAt(),
                isCurrentSession(token, currentTokenHash, currentSessionId));
    }

    /**
     * デバイス名を解決する。token に deviceName が設定されていればそれを使い、
     * なければ UserAgent をパースして取得する。
     */
    private String resolveDeviceName(RefreshTokenEntity token) {
        if (token.getDeviceName() != null) {
            return token.getDeviceName();
        }
        return UserAgentParser.parse(token.getUserAgent()).deviceName();
    }

    /**
     * デバイス種別を解決する。UserAgent をパースして取得する。
     */
    private String resolveDeviceType(RefreshTokenEntity token) {
        return UserAgentParser.parse(token.getUserAgent()).deviceType().name();
    }

    /**
     * 指定されたトークンが現セッションかどうかを判定する。
     */
    private boolean isCurrentSession(RefreshTokenEntity token, String currentTokenHash, Long currentSessionId) {
        if (currentTokenHash != null) {
            return token.getTokenHash().equals(currentTokenHash);
        }
        if (currentSessionId != null) {
            return token.getId().equals(currentSessionId);
        }
        return false;
    }
}
