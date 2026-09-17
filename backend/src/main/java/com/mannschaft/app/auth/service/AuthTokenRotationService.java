package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.AuthErrorCode;
import com.mannschaft.app.auth.dto.TokenResponse;
import com.mannschaft.app.auth.entity.RefreshTokenEntity;
import com.mannschaft.app.auth.event.DeviceFingerprintMismatchEvent;
import com.mannschaft.app.auth.event.TokenReplaySameDeviceRescuedEvent;
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
 *       grace 超過の後継有りトークン再提示は、下記「Phase 3」の同一端末判定で救済できない場合のみ
 *       真リプレイとして全セッション無効化する。</li>
 * </ol>
 *
 * <h2>Phase 3: 同一端末からの grace 超過再試行の救済（誤リプレイ判定による全デバイス強制ログアウトの根治）</h2>
 * <p>実機で確認された因果連鎖: FE の refresh リクエストが 15 秒でクライアント側から abort される一方、
 * サーバー側ではローテーションが完了している（Set-Cookie がブラウザへ届かず古いトークンがジャーに残る）。
 * クライアントは 30 秒後に古いトークンで自動再試行するが、これが grace window（既定60秒）を超過していると
 * 従来実装は一律「真リプレイ攻撃」と誤判定し、正当な後継トークンまで巻き添えで全デバイス無効化していた。</p>
 *
 * <p>根治方針: 「同一端末からの再試行」は盗難とみなさない。grace 超過の後継有りトークン再提示は、
 * {@code deviceFingerprint} が一致し、かつ後継チェーンを辿った現行トークンが有効な場合のみリプレイ扱いを
 * 回避し、現行トークンを基点に新トークンペアを発行する（{@code logoutAllDevices} は呼ばない）。
 * フィンガープリントが片方でも null/空のときは同一端末と確認できないため fail closed とし、従来どおり
 * リプレイ扱い（全デバイス無効化）とする。別端末からの再提示（＝本物の盗難）も従来どおり全デバイス無効化する。</p>
 *
 * <p><b>救済経路の監査痕跡</b>: 全デバイス無効化はしないが、「grace 超過の再提示が起き、同一端末と判定して
 * 救済した」事実は {@link TokenReplaySameDeviceRescuedEvent} で必ず記録する。{@code deviceFingerprint} は
 * User-Agent ハッシュというなりすまし可能な弱い identity であり、この救済経路自体が悪用され得るため
 * （攻撃者が被害者の User-Agent を模倣すれば、盗難トークンでも救済され得る）、監査上の痕跡を残さないと
 * 「検知が完全に消える」退行になる。{@link TokenReuseDetectedEvent}（真リプレイ検出）とは意図的に別イベント
 * にしており、混ぜると監視側で本物の盗難検知と区別が付かず誤報が増える。救済は正当な自動リトライとして
 * 日常的に起こり得るため、本イベント発行・対応するログ（{@code log.info}）ともに警告レベルは上げない
 * （過検知で本物のアラートへの感度を下げないため）。
 * 詳細評価は {@code docs/security/06_business_logic_and_abuse_prevention.md} §7.9.4/§7.9.5 を参照。</p>
 *
 * <p>{@code deviceFingerprint} はクライアントの申告値をそのまま信頼せず、呼び出し元（Controller）が
 * User-Agent ヘッダからサーバー側で導出したものを使う（ログイン時の {@code AuthTokenService#hashToken(userAgent)}
 * と同一の導出方法。導出が食い違うと全件不一致になり本救済が機能しなくなるため）。</p>
 *
 * <p>リプレイ検出時の全デバイス無効化（logoutAllDevices）は {@link AuthSessionService} へ委譲する。
 * 当メソッドは {@code @Transactional}（REQUIRED）で動作し、真リプレイ検出時は全デバイス無効化の直後に
 * {@code BusinessException(AUTH_026)} を送出してこのトランザクションをロールバックさせる（新トークンは発行しない）。
 * このロールバックで全トークン revoke が巻き戻らないよう、{@link AuthSessionService#logoutAllDevices(Long)} は
 * {@code REQUIRES_NEW} の独立トランザクションで即コミットする（盗難トークン検出時の無効化を確実に永続化する）。</p>
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
    private final StatusClaimResolver statusClaimResolver;

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
        // 0. null / 空白トークンの即時拒否。
        //    refresh_token Cookie 欠落時は Controller から null が渡る。ここでガードしないと
        //    直後の hashToken(null) が NPE を投げ、GlobalExceptionHandler で COMMON_999（500）になる。
        //    「無効なリフレッシュトークン」の意味論に沿う AUTH_007 を返す。
        //    AUTH_007 は GlobalExceptionHandler.ERROR_CODE_STATUS_MAP で 401 にマップされている
        //    （Severity.WARN 既定の 400 を上書き。docs/security/06 §7.5）。
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new BusinessException(AuthErrorCode.AUTH_007);
        }

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

                // 2-b. grace 超過の後継有りトークン再提示。
                // Phase 3: 同一端末（deviceFingerprint 一致）からの再試行で、かつ後継チェーンの現行トークンが
                // まだ有効なら、盗難ではなく「クライアント側 abort によるジャー汚染からの自動リトライ」とみなし
                // リプレイ扱いを回避して現行トークンを基点に新トークンを発行する（自爆バグの根治点）。
                if (isSameDeviceRetry(deviceFingerprint, existingToken)) {
                    var currentHead = resolveCurrentChainHead(existingToken);
                    if (currentHead.isPresent()) {
                        log.info("grace 超過だが同一端末の再試行と判定し正規化（誤リプレイ判定回避）: "
                                        + "userId={}, tokenId={}, sinceRevoke={}s（grace={}s）, successorTokenId={}",
                                existingToken.getUserId(), existingToken.getId(),
                                secondsSinceRevoke, refreshRotationGraceSeconds, currentHead.get().getId());
                        // 監査上の痕跡を残す（全デバイス無効化はしないが「救済した」事実は記録する）。
                        // User-Agent はなりすまし可能なため、この救済経路自体が悪用され得る（詳細評価は
                        // docs/security/06_business_logic_and_abuse_prevention.md §7.9.4）。
                        // TokenReuseDetectedEvent（真リプレイ検出）とは意図的に別イベントにし、
                        // 監視側で本物の盗難検知と混同されないようにする。
                        eventPublisher.publish(new TokenReplaySameDeviceRescuedEvent(
                                existingToken.getUserId(), existingToken.getId(), currentHead.get().getId()));
                        return issueRotatedTokens(currentHead.get(), true);
                    }
                    log.warn("同一端末の再試行判定だが後継チェーンの現行トークンが解決できず、リプレイとして扱う: "
                                    + "userId={}, tokenId={}",
                            existingToken.getUserId(), existingToken.getId());
                }

                // 真リプレイ攻撃（別端末からの再提示、または deviceFingerprint 欠落で同一端末と確認できない）
                // → 全トークン無効化。
                log.warn("リプレイ攻撃検出（grace 超過の後継有りトークン再提示。同一端末と確認できず）: "
                                + "userId={}, tokenId={}, sinceRevoke={}s（grace={}s）",
                        existingToken.getUserId(), existingToken.getId(),
                        secondsSinceRevoke, refreshRotationGraceSeconds);
                eventPublisher.publish(new TokenReuseDetectedEvent(existingToken.getUserId(), existingToken.getId()));
                // logoutAllDevices は REQUIRES_NEW（独立トランザクション）で即コミットするため、
                // 直後の AUTH_026 throw による当メソッドのトランザクションのロールバックでは巻き戻らない。
                // これにより盗難トークン検出時の全デバイス無効化が確実に永続化される。
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
        String newAccessToken = authTokenService.issueAccessToken(userId, roleClaimResolver.resolveRoles(userId),
                statusClaimResolver.isPendingParentalConsent(userId));
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

    /**
     * 後継チェーンを辿る際の最大ホップ数。
     *
     * <p>データ不整合や悪意ある操作で {@code replacedByTokenHash} が循環参照を起こしても
     * 無限ループ・スタックオーバーフローに陥らないための安全弁（AC-5）。通常のローテーション連鎖が
     * この上限に達することは想定しない。</p>
     */
    private static final int MAX_CHAIN_RESOLUTION_HOPS = 25;

    /**
     * grace 超過で再提示されたトークンが「同一端末からの再試行」と確認できるかを判定する。
     *
     * <p>両方の deviceFingerprint が非 null かつ非空白で、かつ一致する場合のみ true を返す。
     * 片方でも null/空のときは同一端末と確認できないため false（fail closed）を返す。
     * 「null なら通す」実装は認可を素通しさせる穴になるため、意図的に排除している（AC-3）。</p>
     *
     * @param requestFingerprint 今回のリクエストで導出された deviceFingerprint（Controller が
     *                           User-Agent ヘッダからサーバー側で導出したもの）
     * @param token              再提示された（grace 超過・後継有りの）トークン
     * @return 同一端末からの再試行と確認できれば true
     */
    private boolean isSameDeviceRetry(String requestFingerprint, RefreshTokenEntity token) {
        String storedFingerprint = token.getDeviceFingerprint();
        if (requestFingerprint == null || requestFingerprint.isBlank()) {
            return false;
        }
        if (storedFingerprint == null || storedFingerprint.isBlank()) {
            return false;
        }
        return requestFingerprint.equals(storedFingerprint);
    }

    /**
     * 後継ポインタ（{@code replacedByTokenHash}）のチェーンを辿り、現行（まだ失効していない）トークンを解決する。
     *
     * <p>チェーンの各ホップで同一ユーザーであることを確認し、循環参照や不整合に対しては
     * {@link #MAX_CHAIN_RESOLUTION_HOPS} で打ち切って {@link Optional#empty()} を返す（真リプレイ扱いへフォールバック）。
     * 現行トークン候補が見つかった場合は悲観ロック版ファインダで取り直し（並行操作との競合を避けるため）、
     * ロック取得後に改めて失効していないことを確認する。</p>
     *
     * @param source grace 超過で再提示された（失効済み・後継有りの）起点トークン
     * @return 解決できた現行トークン。循環・不整合・期限切れ・見つからない場合は空
     */
    private java.util.Optional<RefreshTokenEntity> resolveCurrentChainHead(RefreshTokenEntity source) {
        Long userId = source.getUserId();
        String nextHash = source.getReplacedByTokenHash();

        for (int hop = 0; hop < MAX_CHAIN_RESOLUTION_HOPS && nextHash != null; hop++) {
            RefreshTokenEntity next = refreshTokenRepository.findByTokenHash(nextHash).orElse(null);
            if (next == null || !userId.equals(next.getUserId())) {
                return java.util.Optional.empty();
            }

            if (next.getRevokedAt() == null) {
                if (next.getExpiresAt().isBefore(LocalDateTime.now())) {
                    return java.util.Optional.empty();
                }
                // 並行操作との競合を避けるため悲観ロック版で取り直し、ロック取得後も未失効であることを再確認する。
                return refreshTokenRepository.findByTokenHashForUpdate(next.getTokenHash())
                        .filter(locked -> locked.getRevokedAt() == null);
            }

            nextHash = next.getReplacedByTokenHash();
        }
        return java.util.Optional.empty();
    }
}
