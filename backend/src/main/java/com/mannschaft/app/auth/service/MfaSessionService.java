package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.AuthErrorCode;
import com.mannschaft.app.auth.MfaEmailOtpPurpose;
import com.mannschaft.app.auth.event.AccountLockedEvent;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.DomainEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 2FA ログインの MFA セッション（パスワード認証済み・2FA 未完了の状態）を Valkey 上で管理する（CMP-261012-0910）。
 *
 * <h2>総当たり対策（裁可事項 6 / AC-14〜17・AC-46）</h2>
 * <ul>
 *   <li><b>セッション単位</b>: 全手段（TOTP・バックアップコード・メールOTP）合計で 5 回まで。
 *       照合<b>前</b>に {@code INCR} で試行を計上し（{@link #beginAttempt}）、5 回目の失敗でセッションを削除する。
 *       旧実装の「照合前に削除する単回使用」は 1 回の打ち間違いで再ログインを強いるため置き換えた。</li>
 *   <li><b>ユーザー単位</b>: 30 分窓で MFA 失敗 10 回に達したら既存のアカウントロック（{@code account_lock:{userId}}・
 *       30 分・AUTH_003）を掛ける。セッションを作り直しても回避できず、ロック前に発行済みのセッションも
 *       {@link #assertNotLocked} で全方式拒否される。</li>
 *   <li><b>成功</b>: セッションキーの {@code DEL} の戻り値で勝者を 1 人に決める（{@link #consume}）。
 *       同一セッションへの並列成功でもトークン発行は 1 組になる（AC-15）。</li>
 * </ul>
 *
 * <p>Valkey 操作は単一コマンドの原子プリミティブ（GET / SET / SET NX / INCR / DEL / EXPIRE）のみで書く
 * （Lua 不使用）。DB を触らないため {@code @Transactional} は付けない（呼び出し側の TX がロールバックしても
 * 試行計上・ロックは残る＝失敗の痕跡を TX に巻き戻させない）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MfaSessionService {

    /** MFA セッションキー接頭辞（値は userId）。F01.1・AuthService と共通。 */
    public static final String SESSION_KEY_PREFIX = "mannschaft:auth:mfa_session_token:";
    /** セッション単位の試行回数（照合前 INCR）。 */
    static final String ATTEMPT_COUNT_KEY_PREFIX = "mannschaft:auth:mfa_session_attempt_count:";
    /** ユーザー単位の MFA 失敗回数（30 分窓）。 */
    static final String USER_FAIL_COUNT_KEY_PREFIX = "mannschaft:auth:mfa_fail_count:";
    /** アカウントロック（AuthService のパスワード失敗ロックと共通のキー）。 */
    public static final String ACCOUNT_LOCK_KEY_PREFIX = "mannschaft:auth:account_lock:";

    /** ログイン直後の MFA セッション有効期間。 */
    public static final Duration SESSION_TTL = Duration.ofMinutes(5);
    /** MFA セッションの最大寿命（メールOTP 送信時の延長込み。ログインから最大 15 分）。 */
    public static final Duration SESSION_MAX_LIFETIME = Duration.ofMinutes(15);
    /** セッション単位の試行上限（全手段合計）。 */
    public static final int MAX_SESSION_ATTEMPTS = 5;
    /** ユーザー単位の MFA 失敗ロック閾値。 */
    public static final int USER_FAIL_THRESHOLD = 10;
    /** ユーザー単位の MFA 失敗計数窓。 */
    public static final Duration USER_FAIL_WINDOW = Duration.ofMinutes(30);
    /** アカウントロック期間（パスワード失敗ロックと同じ 30 分）。 */
    public static final Duration ACCOUNT_LOCK_DURATION = Duration.ofMinutes(30);
    /** 試行カウンタの TTL。セッションの最大寿命より長く取り、セッションより先に消えて計数が戻ることを防ぐ。 */
    private static final Duration ATTEMPT_COUNTER_TTL = Duration.ofMinutes(30);

    private final StringRedisTemplate redisTemplate;
    private final AuthTokenService authTokenService;
    private final DomainEventPublisher eventPublisher;
    @Qualifier("wallClock")
    private final Clock wallClock;

    /**
     * 解決済みの MFA セッション。
     *
     * @param sessionHash 生トークンの SHA-256（Valkey キー・ログイン用メールOTP の紐付けに使う）
     * @param userId      パスワード認証を通過したユーザー
     */
    public record MfaSession(String sessionHash, Long userId) {
    }

    /**
     * ユーザー単位の失敗記録の結果。
     *
     * @param sessionExhausted このセッションが試行上限に達し削除された
     * @param lockedNow        この失敗でユーザーがロックされた
     */
    public record FailureOutcome(boolean sessionExhausted, boolean lockedNow) {
    }

    /** MFA セッションキー。 */
    public static String sessionKey(String sessionHash) {
        return SESSION_KEY_PREFIX + sessionHash;
    }

    /** アカウントロックキー。 */
    public static String accountLockKey(Long userId) {
        return ACCOUNT_LOCK_KEY_PREFIX + userId;
    }

    /**
     * 生の MFA セッショントークンを解決する。不在・期限切れ・空はすべて同一の AUTH_073（AC-17）。
     *
     * @param rawToken クライアントが持つ MFA セッショントークン
     * @return 解決済みセッション
     */
    public MfaSession resolve(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new BusinessException(AuthErrorCode.AUTH_073);
        }
        String sessionHash = authTokenService.hashToken(rawToken);
        String userIdStr = redisTemplate.opsForValue().get(sessionKey(sessionHash));
        if (userIdStr == null) {
            throw new BusinessException(AuthErrorCode.AUTH_073);
        }
        try {
            return new MfaSession(sessionHash, Long.valueOf(userIdStr));
        } catch (NumberFormatException e) {
            log.error("MFA セッションの値が userId として解釈できない（キー破損）");
            throw new BusinessException(AuthErrorCode.AUTH_073);
        }
    }

    /**
     * ユーザーがロック中なら AUTH_003 で拒否する（ロック前に発行済みのセッションも含め全方式。AC-46）。
     *
     * @param userId ユーザー ID
     */
    public void assertNotLocked(Long userId) {
        if (Boolean.TRUE.equals(redisTemplate.hasKey(accountLockKey(userId)))) {
            throw new BusinessException(AuthErrorCode.AUTH_003);
        }
    }

    /**
     * 照合の前に試行を 1 回計上する（原子 INCR。AC-14）。
     *
     * <p>上限超過（並列で 5 回目を越えた呼び出し）は照合させずにセッションを削除し AUTH_075 を返す。</p>
     *
     * @param session 解決済みセッション
     * @return 計上後の試行回数（1〜{@value #MAX_SESSION_ATTEMPTS}）
     */
    public long beginAttempt(MfaSession session) {
        String key = ATTEMPT_COUNT_KEY_PREFIX + session.sessionHash();
        Long count = redisTemplate.opsForValue().increment(key);
        if (count == null) {
            // パイプライン/トランザクション中のみ null になる。計上できない状態で照合させない（フェイルクローズ）。
            throw new IllegalStateException("MFA 試行回数の INCR が値を返さなかった");
        }
        if (count == 1L) {
            redisTemplate.expire(key, ATTEMPT_COUNTER_TTL.getSeconds(), TimeUnit.SECONDS);
        }
        if (count > MAX_SESSION_ATTEMPTS) {
            terminate(session);
            throw new BusinessException(AuthErrorCode.AUTH_075);
        }
        return count;
    }

    /**
     * 検証成功時にセッションを原子的に消費する（DEL の戻り値で勝者を 1 人に決める。AC-15）。
     *
     * @param session 解決済みセッション
     * @return この呼び出しがセッションを消費できた（勝者）なら true。既に消費・削除済みなら false
     */
    public boolean consume(MfaSession session) {
        boolean won = Boolean.TRUE.equals(redisTemplate.delete(sessionKey(session.sessionHash())));
        if (won) {
            cleanUpSessionScopedKeys(session);
        }
        return won;
    }

    /**
     * セッションを破棄する（試行上限到達・2FA 設定消失時）。紐付くログイン用メールOTP も消す。
     *
     * @param session 解決済みセッション
     */
    public void terminate(MfaSession session) {
        redisTemplate.delete(sessionKey(session.sessionHash()));
        cleanUpSessionScopedKeys(session);
    }

    /**
     * 照合失敗を記録する。セッション試行上限に達していればセッションを削除し、ユーザー単位の失敗を計上して
     * 閾値に達したらアカウントをロックする（AC-14/AC-16）。
     *
     * @param session 解決済みセッション
     * @param attempt {@link #beginAttempt} が返した試行回数
     * @return 記録結果
     */
    public FailureOutcome recordFailure(MfaSession session, long attempt) {
        boolean exhausted = attempt >= MAX_SESSION_ATTEMPTS;
        if (exhausted) {
            terminate(session);
        }

        Long userId = session.userId();
        String failKey = USER_FAIL_COUNT_KEY_PREFIX + userId;
        Long fails = redisTemplate.opsForValue().increment(failKey);
        if (fails != null && fails == 1L) {
            redisTemplate.expire(failKey, USER_FAIL_WINDOW.getSeconds(), TimeUnit.SECONDS);
        }
        boolean locked = false;
        if (fails != null && fails >= USER_FAIL_THRESHOLD) {
            redisTemplate.opsForValue().set(accountLockKey(userId), "1",
                    ACCOUNT_LOCK_DURATION.getSeconds(), TimeUnit.SECONDS);
            // ロック解除後に古い計数で即再ロックしないよう、窓をリセットする
            redisTemplate.delete(failKey);
            locked = true;
            log.warn("MFA 失敗によるアカウントロック発動: userId={}, failCount={}", userId, fails);
            eventPublisher.publish(new AccountLockedEvent(userId, "MFA_BRUTE_FORCE",
                    LocalDateTime.now(wallClock).plus(ACCOUNT_LOCK_DURATION)));
        }
        return new FailureOutcome(exhausted, locked);
    }

    /**
     * 2FA 成功時にユーザー単位の MFA 失敗計数をリセットする。
     *
     * @param userId ユーザー ID
     */
    public void resetUserFailures(Long userId) {
        redisTemplate.delete(USER_FAIL_COUNT_KEY_PREFIX + userId);
    }

    /**
     * セッションの残り有効期間を {@code ttl} まで延ばす（短くはしない）。メールOTP 送信時の延長に使う（AC-19）。
     *
     * <p>呼び出し側（メールOTP 送信）は {@link #SESSION_MAX_LIFETIME}（ログインから最大 15 分）を超えないよう
     * {@code ttl} を決めること。</p>
     *
     * @param session 解決済みセッション
     * @param ttl     延長後の残り有効期間
     * @return 延長した（または既に十分長かった）なら true。セッションが既に消えていれば false
     */
    public boolean extendTtl(MfaSession session, Duration ttl) {
        String key = sessionKey(session.sessionHash());
        Long remaining = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        if (remaining == null || remaining == -2L) {
            return false;
        }
        if (remaining >= 0 && remaining >= ttl.getSeconds()) {
            return true;
        }
        return Boolean.TRUE.equals(redisTemplate.expire(key, ttl.getSeconds(), TimeUnit.SECONDS));
    }

    private void cleanUpSessionScopedKeys(MfaSession session) {
        redisTemplate.delete(List.of(
                ATTEMPT_COUNT_KEY_PREFIX + session.sessionHash(),
                MfaEmailOtpKeys.codeKey(MfaEmailOtpPurpose.LOGIN, session.sessionHash())));
    }
}
