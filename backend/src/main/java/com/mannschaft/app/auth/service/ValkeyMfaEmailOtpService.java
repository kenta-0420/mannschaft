package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.AuthErrorCode;
import com.mannschaft.app.auth.MfaEmailOtpPurpose;
import com.mannschaft.app.auth.dto.MfaEmailSendResponse;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.RetryAfterBusinessException;
import com.mannschaft.app.mail.outbox.EmailOutboxRequest;
import com.mannschaft.app.mail.outbox.EmailOutboxService;
import com.mannschaft.app.mail.outbox.EmailTemplateKind;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * {@link MfaEmailOtpService} の Valkey 実装（CMP-261012-0910 AC-19〜23・AC-45・AC-50・AC-51）。
 *
 * <h2>送信（{@link #send}）の手順</h2>
 * <ol>
 *   <li>宛先の可否（{@link MfaMethodPolicy#isEmailDeliverable}。不可は AUTH_081・何も消費しない）</li>
 *   <li>再送待ち（{@code SET NX}・60 秒。待ち中は 429 AUTH_076 ＋ {@code Retry-After}）</li>
 *   <li>送信枠（LOGIN はセッション 5 回、全用途合算でユーザー 10 回/時。超過は 429 AUTH_077 ＋ {@code Retry-After}）</li>
 *   <li>LOGIN のみ MFA セッションを延長（10 分。ただしログインから 15 分を上限）</li>
 *   <li>email_outbox へ<b>同期</b> enqueue（呼び出し側の送信 TX に参加する）</li>
 *   <li>コードのハッシュを保存（上書き＝旧コードは無効。AC-21）</li>
 * </ol>
 *
 * <p><b>enqueue 失敗時（殿裁定・AC-50）</b>: 送信要求は「無かったこと」にする。再送待ちと送信枠の計数を
 * 取り消してから例外をそのまま投げ直す（握りつぶさない。API は 200 を返さず、送信 TX はロールバックする）。
 * コードは enqueue の成功後に保存するため、失敗した要求が直前に届いたコードを無効にすることもない。</p>
 *
 * <p><b>保存形式</b>: コード保持キーの値は {@link #hash(MfaEmailOtpPurpose, String, String)}
 * （用途・主体・コードを連結した SHA-256 の 16 進）。平文コードは Valkey にもログにも置かない。用途・主体を
 * ハッシュに含めるため、仮にキーを取り違えても別用途・別主体のコードとは一致しない（AC-23 の二重防御）。
 * 平文コードが存在するのは email_outbox の暗号化 payload の中だけ。</p>
 *
 * <p><b>D-3 境界</b>: メール送信は mail ドメインの公開入口 {@link EmailOutboxService}（interface）だけを呼ぶ。
 * 同期 enqueue は呼び出し側（auth）の送信 TX に参加するが、これは「enqueue に失敗した送信を成功と偽らない」
 * ために殿裁定で選んだ形であり、既存の {@code ParentalConsentService} と同じ作法（業務 TX 内での同期 enqueue）。
 * mail ドメインの Repository へは直接触れない。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ValkeyMfaEmailOtpService implements MfaEmailOtpService {

    /** コードの有効期限（AC-19。試練の設計諸元 10 分）。 */
    public static final Duration CODE_TTL = Duration.ofMinutes(10);
    /** 再送待ち（AC-20）。 */
    public static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);
    /** 同一 MFA セッションからの送信上限（LOGIN。AC-20）。 */
    public static final int SESSION_SEND_LIMIT = 5;
    /** ユーザー単位の送信上限（全用途合算・1 時間。AC-20）。 */
    public static final int USER_SEND_LIMIT = 10;
    /** ユーザー単位の送信計数窓。 */
    public static final Duration USER_SEND_WINDOW = Duration.ofHours(1);
    /** SETUP / REAUTH の照合試行上限（AC-45 / AC-51）。 */
    public static final int MAX_VERIFY_ATTEMPTS = 5;
    /** LOGIN の送信回数キーの TTL（セッションの最大寿命 15 分より長く取り、先に消えて枠が戻ることを防ぐ）。 */
    private static final Duration SESSION_SENT_COUNT_TTL = Duration.ofMinutes(30);
    /** email_outbox の送信元ドメイン。 */
    private static final String SOURCE_DOMAIN = "auth";
    /** OTP メールは ja 固定（裁可事項 9）。 */
    private static final String MAIL_LOCALE = "ja";

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final StringRedisTemplate redisTemplate;
    private final UserRepository userRepository;
    private final EmailOutboxService emailOutboxService;
    private final MfaSessionService mfaSessionService;

    @Override
    public MfaEmailSendResponse send(MfaEmailOtpPurpose purpose, String subject, Long userId, String ipAddress) {
        // 1. 宛先の可否は送信時点で判定する（ログイン後にアドレスが変わった場合も含む。AC-24）
        String email = userRepository.findById(userId)
                .map(UserEntity::getEmail)
                .filter(MfaMethodPolicy::isEmailDeliverable)
                .orElseThrow(() -> new BusinessException(AuthErrorCode.AUTH_081));

        // 2〜3. 再送待ち・送信枠（失敗時は自分が取った分だけ戻す）
        SendReservation reservation = reserve(purpose, subject, userId);
        try {
            // 4. LOGIN のみ MFA セッションを延長（ログインから最大 15 分）
            if (purpose == MfaEmailOtpPurpose.LOGIN) {
                extendLoginSession(subject, userId);
            }
            // 5. 同期 enqueue → 6. コード保存
            String code = String.format("%06d", SECURE_RANDOM.nextInt(1_000_000));
            enqueueMail(purpose, userId, email, code);
            redisTemplate.opsForValue().set(MfaEmailOtpKeys.codeKey(purpose, subject),
                    hash(purpose, subject, code), CODE_TTL);
        } catch (RuntimeException e) {
            // 届かない送信を「送った」ことにしない。枠・待ちを戻して原因の例外をそのまま上げる（AC-50）
            reservation.cancel();
            throw e;
        }
        log.info("メールOTP を enqueue した: userId={}, purpose={}", userId, purpose);
        return new MfaEmailSendResponse(maskEmail(email), CODE_TTL.getSeconds(), RESEND_COOLDOWN.getSeconds());
    }

    @Override
    public boolean verifyAndConsume(MfaEmailOtpPurpose purpose, String subject, String code) {
        return verify(purpose, subject, code) == VerifyOutcome.MATCHED;
    }

    @Override
    public VerifyOutcome verify(MfaEmailOtpPurpose purpose, String subject, String code) {
        if (code == null || !code.matches("\\d{6}")) {
            // 形式違反は計上しない（AC-10。通常は DTO で 400 になり、ここへは来ない）
            return VerifyOutcome.MISMATCHED;
        }
        String codeKey = MfaEmailOtpKeys.codeKey(purpose, subject);
        boolean counted = purpose != MfaEmailOtpPurpose.LOGIN;
        long attempt = 0;
        if (counted) {
            attempt = incrementWithTtl(MfaEmailOtpKeys.attemptCountKey(purpose, subject), CODE_TTL);
            if (attempt > MAX_VERIFY_ATTEMPTS) {
                redisTemplate.delete(codeKey);
                return VerifyOutcome.EXHAUSTED;
            }
        }
        if (matches(purpose, subject, code, codeKey)
                // DEL の戻り値で消費者を 1 人に決める（同一コードの並列提示で成功は 1 件）
                && Boolean.TRUE.equals(redisTemplate.delete(codeKey))) {
            if (counted) {
                redisTemplate.delete(MfaEmailOtpKeys.attemptCountKey(purpose, subject));
            }
            return VerifyOutcome.MATCHED;
        }
        if (counted && attempt >= MAX_VERIFY_ATTEMPTS) {
            redisTemplate.delete(codeKey);
            return VerifyOutcome.EXHAUSTED;
        }
        return VerifyOutcome.MISMATCHED;
    }

    @Override
    public void invalidate(MfaEmailOtpPurpose purpose, String subject) {
        redisTemplate.delete(List.of(
                MfaEmailOtpKeys.codeKey(purpose, subject),
                MfaEmailOtpKeys.attemptCountKey(purpose, subject)));
    }

    /**
     * コード保持キーに保存する値（SHA-256 16 進）。送信側はこの関数で保存し、照合側と形式を一致させる。
     *
     * @param purpose 用途
     * @param subject 主体
     * @param code    平文 6 桁コード
     * @return ハッシュ値
     */
    static String hash(MfaEmailOtpPurpose purpose, String subject, String code) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] h = digest.digest((purpose.name() + ":" + subject + ":" + code).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(h);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * 送信先のマスク表示（ローカル部は先頭 1 文字だけ残し、ドメインは残す。例: {@code t***@example.com}）。
     * ローカル部が 1 文字なら全部伏せる。
     */
    static String maskEmail(String email) {
        int at = email.lastIndexOf('@');
        if (at <= 0) {
            return "***";
        }
        String local = email.substring(0, at);
        String head = local.length() >= 2 ? local.substring(0, 1) : "";
        return head + "***" + email.substring(at);
    }

    // ─────────────────────────────────────────────────────────────
    // 内部
    // ─────────────────────────────────────────────────────────────

    private boolean matches(MfaEmailOtpPurpose purpose, String subject, String code, String codeKey) {
        String stored = redisTemplate.opsForValue().get(codeKey);
        if (stored == null) {
            return false;
        }
        return MessageDigest.isEqual(stored.getBytes(StandardCharsets.UTF_8),
                hash(purpose, subject, code).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 再送待ちと送信枠を取る。超過したら取った分を戻して 429（{@code Retry-After} 付き）を投げる。
     */
    private SendReservation reserve(MfaEmailOtpPurpose purpose, String subject, Long userId) {
        String cooldownKey = MfaEmailOtpKeys.resendCooldownKey(purpose, subject);
        if (!Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(cooldownKey, "1", RESEND_COOLDOWN))) {
            throw new RetryAfterBusinessException(AuthErrorCode.AUTH_076, remainingSeconds(cooldownKey));
        }
        SendReservation reservation = new SendReservation(cooldownKey);

        if (purpose == MfaEmailOtpPurpose.LOGIN) {
            String sessionCountKey = MfaEmailOtpKeys.sentCountKey(purpose, subject);
            long sent = incrementWithTtl(sessionCountKey, SESSION_SENT_COUNT_TTL);
            reservation.counted(sessionCountKey);
            if (sent > SESSION_SEND_LIMIT) {
                reservation.cancel();
                throw new RetryAfterBusinessException(AuthErrorCode.AUTH_077, remainingSeconds(sessionCountKey));
            }
        }

        String userCountKey = MfaEmailOtpKeys.userSentCountKey(userId);
        long userSent = incrementWithTtl(userCountKey, USER_SEND_WINDOW);
        reservation.counted(userCountKey);
        if (userSent > USER_SEND_LIMIT) {
            reservation.cancel();
            throw new RetryAfterBusinessException(AuthErrorCode.AUTH_077, remainingSeconds(userCountKey));
        }
        return reservation;
    }

    /**
     * LOGIN の MFA セッションを 10 分へ延長する。ただしログインから 15 分（{@link MfaSessionService#SESSION_MAX_LIFETIME}）
     * を越えない。上限時刻は初回送信時に「延長前のセッション残り TTL」から逆算して
     * {@link MfaEmailOtpKeys#loginSessionLimitKey} の TTL として固定する（セッションはログイン時刻を持たないため）。
     */
    private void extendLoginSession(String sessionHash, Long userId) {
        String limitKey = MfaEmailOtpKeys.loginSessionLimitKey(sessionHash);
        long limitMillis = remainingMillis(limitKey);
        if (limitMillis <= 0) {
            long sessionRemaining = remainingMillis(MfaSessionService.sessionKey(sessionHash));
            if (sessionRemaining <= 0) {
                throw new BusinessException(AuthErrorCode.AUTH_073);
            }
            long elapsed = Math.max(0L, MfaSessionService.SESSION_TTL.toMillis() - sessionRemaining);
            long computed = MfaSessionService.SESSION_MAX_LIFETIME.toMillis() - elapsed;
            redisTemplate.opsForValue().setIfAbsent(limitKey, "1", Duration.ofMillis(computed));
            long stored = remainingMillis(limitKey);
            limitMillis = stored > 0 ? stored : computed;
        }
        long targetSeconds = Math.min(CODE_TTL.toMillis(), limitMillis) / 1000L;
        if (targetSeconds <= 0) {
            throw new BusinessException(AuthErrorCode.AUTH_073);
        }
        if (!mfaSessionService.extendTtl(new MfaSessionService.MfaSession(sessionHash, userId),
                Duration.ofSeconds(targetSeconds))) {
            throw new BusinessException(AuthErrorCode.AUTH_073);
        }
    }

    /** email_outbox へ同期 enqueue する（payload は Worker が日本語本文に描画する構造化変数のみ）。 */
    private void enqueueMail(MfaEmailOtpPurpose purpose, Long userId, String email, String code) {
        emailOutboxService.enqueue(new EmailOutboxRequest(
                EmailTemplateKind.MFA_EMAIL_OTP.name(),
                MAIL_LOCALE,
                email,
                Map.of(
                        "code", code,
                        "purpose", purpose.name(),
                        "expiresInMinutes", String.valueOf(CODE_TTL.toMinutes())),
                SOURCE_DOMAIN,
                // 送信ごとに一意（冪等キーの衝突で 2 通目が落ちないように。AC-26）
                "mfa-email-otp:" + UUID.randomUUID(),
                null,
                userId,
                null));
    }

    /** INCR し、初回（=1）なら TTL を付ける。 */
    private long incrementWithTtl(String key, Duration ttl) {
        Long n = redisTemplate.opsForValue().increment(key);
        if (n == null) {
            // パイプライン/トランザクション中のみ null。計上できないまま通さない（フェイルクローズ）
            throw new IllegalStateException("メールOTP の計数 INCR が値を返さなかった");
        }
        if (n == 1L) {
            redisTemplate.expire(key, ttl.toMillis(), TimeUnit.MILLISECONDS);
        }
        return n;
    }

    /** キーの残り TTL（ミリ秒）。キー無し・期限無しは 0 以下。 */
    private long remainingMillis(String key) {
        Long ms = redisTemplate.getExpire(key, TimeUnit.MILLISECONDS);
        return ms == null ? -2L : ms;
    }

    /** {@code Retry-After} 用の残り秒数（切り上げ・最小 1）。 */
    private long remainingSeconds(String key) {
        long ms = remainingMillis(key);
        return ms <= 0 ? 1L : (ms + 999L) / 1000L;
    }

    /** 送信 1 回分として取った再送待ち・計数。失敗時に {@link #cancel} で取り消す。 */
    private final class SendReservation {
        private final String cooldownKey;
        private final java.util.List<String> countKeys = new java.util.ArrayList<>(2);

        private SendReservation(String cooldownKey) {
            this.cooldownKey = cooldownKey;
        }

        private void counted(String key) {
            countKeys.add(key);
        }

        private void cancel() {
            for (String key : countKeys) {
                Long left = redisTemplate.opsForValue().decrement(key);
                if (left != null && left <= 0) {
                    // 0 以下は「この取り消しで空になった」か「途中で期限切れになり DECR が TTL 無しの負値を作った」。
                    // どちらも消しておく（TTL 無しのキーが残ると次の INCR で TTL が付かず、枠が永久に戻らない）
                    redisTemplate.delete(key);
                }
            }
            countKeys.clear();
            redisTemplate.delete(cooldownKey);
        }
    }
}
