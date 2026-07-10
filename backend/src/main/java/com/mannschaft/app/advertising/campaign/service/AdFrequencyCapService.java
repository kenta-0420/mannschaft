package com.mannschaft.app.advertising.campaign.service;

import com.mannschaft.app.auth.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * F09.17 フリークエンシーキャップ（広告疲れ防止）の Valkey 原子的カウンタサービス。
 *
 * <p>設計書 §5: 1 ユーザーが 1 週間に受け取る広告は最大 3 件、同一広告主からは 1 件まで。
 * 週境界はユーザーローカル時刻の月曜 00:00。</p>
 *
 * <h3>Valkey キー設計</h3>
 * <ul>
 *   <li>{@code mannschaft:ad:freq:{userId}:{weekStartLocalDate}} — 個人合計（全広告主）</li>
 *   <li>{@code mannschaft:ad:freq-adv:{advertiserAccountId}:{userId}:{weekStartLocalDate}} — 同一広告主単位</li>
 * </ul>
 *
 * <h3>原子性</h3>
 * <p>INCR で先に「両方とも上限以下になるか」を検証し、超過していれば DECR でロールバック。
 * Valkey の INCR は単一サーバー上で原子的なため、レースコンディションは起きない。</p>
 *
 * <h3>TTL</h3>
 * <p>初回 INCR で「次週月曜 00:00（ユーザー TZ）」までの残秒を EXPIRE で設定する。
 * これにより自動失効するため明示削除は不要。日次 flush バッチが TTL 失効前に
 * RDB に転記し、保持期間 90 日分のクエリ可能データを残す。</p>
 *
 * @see AdFrequencyCapFlushBatch  Valkey の週次カウンタを RDB に転記する日次バッチ
 * @see AdFrequencyCapConfig       上限値設定（weekly-total / weekly-per-advertiser）
 */
@Service
@Slf4j
public class AdFrequencyCapService {

    /** Valkey キー: 個人合計カウンタ（全広告主合算）。 */
    static final String KEY_PREFIX_TOTAL = "mannschaft:ad:freq:";

    /** Valkey キー: 同一広告主単位カウンタ。 */
    static final String KEY_PREFIX_PER_ADV = "mannschaft:ad:freq-adv:";

    /** ユーザー TZ 取得失敗時のフォールバック。 */
    private static final ZoneId FALLBACK_ZONE = ZoneId.of("Asia/Tokyo");

    private final StringRedisTemplate redisTemplate;
    private final UserRepository userRepository;
    private final AdFrequencyCapConfig config;

    public AdFrequencyCapService(
            StringRedisTemplate redisTemplate,
            UserRepository userRepository,
            AdFrequencyCapConfig config) {
        this.redisTemplate = redisTemplate;
        this.userRepository = userRepository;
        this.config = config;
    }

    /**
     * フリークエンシーキャップ枠を 1 件消費しようと試みる。
     *
     * <p>個人合計（{@code weekly-total}）と同一広告主単位（{@code weekly-per-advertiser}）の
     * 両方の上限を満たす場合のみ true を返し、Valkey 上の 2 つのカウンタをそれぞれ +1 する。
     * いずれか一方でも超過したら、先に増やしたカウンタを DECR でロールバックして false を返す。</p>
     *
     * <p>campaignId は現状ログ出力のみに使用するが、将来キャンペーン単位の集計が必要になった
     * 場合に備えて API に含めておく。</p>
     *
     * @param userId               ユーザー ID（必須）
     * @param advertiserAccountId  広告主アカウント ID（必須）
     * @param campaignId           キャンペーン ID（ログ用、null 可）
     * @return 枠を確保できた場合 true。上限超過の場合 false。
     */
    public boolean tryConsume(Long userId, Long advertiserAccountId, UUID campaignId) {
        if (userId == null || advertiserAccountId == null) {
            throw new IllegalArgumentException("userId と advertiserAccountId は必須です");
        }

        ZoneId userZone = resolveUserZone(userId);
        LocalDate weekStart = currentWeekStart(userZone);
        long ttlSeconds = secondsUntilNextWeekStart(userZone);

        String totalKey = buildTotalKey(userId, weekStart);
        String perAdvKey = buildPerAdvertiserKey(advertiserAccountId, userId, weekStart);

        Long totalCount = null;
        try {
            totalCount = incrementWithTtl(totalKey, ttlSeconds);
            if (totalCount == null || totalCount > config.getWeeklyTotal()) {
                // 個人合計の上限超過 → ロールバック
                decrementSafely(totalKey);
                log.debug("フリークエンシーキャップ: 個人合計超過 userId={} count={} limit={}",
                        userId, totalCount, config.getWeeklyTotal());
                return false;
            }

            Long perAdvCount = incrementWithTtl(perAdvKey, ttlSeconds);
            if (perAdvCount == null || perAdvCount > config.getWeeklyPerAdvertiser()) {
                // 同一広告主上限超過 → 両方ロールバック
                decrementSafely(perAdvKey);
                decrementSafely(totalKey);
                log.debug("フリークエンシーキャップ: 同一広告主超過 userId={} advertiser={} count={} limit={}",
                        userId, advertiserAccountId, perAdvCount, config.getWeeklyPerAdvertiser());
                return false;
            }

            log.debug("フリークエンシーキャップ: 枠確保 userId={} advertiser={} campaign={} total={} perAdv={}",
                    userId, advertiserAccountId, campaignId, totalCount, perAdvCount);
            return true;
        } catch (RuntimeException ex) {
            // 例外発生時も totalKey を増やしていたらロールバック
            if (totalCount != null) {
                try {
                    decrementSafely(totalKey);
                } catch (RuntimeException rollbackEx) {
                    log.warn("ロールバック失敗 key={}", totalKey, rollbackEx);
                }
            }
            throw ex;
        }
    }

    /**
     * 指定ユーザーの「現在の週」の個人合計カウントを Valkey から取得する。読み取り専用。
     *
     * @param userId    ユーザー ID
     * @param weekStart 週開始日（ユーザー TZ の月曜）
     * @return 現在のカウント値（キー未存在は 0）
     */
    public int getCurrentCount(Long userId, LocalDate weekStart) {
        if (userId == null || weekStart == null) {
            throw new IllegalArgumentException("userId と weekStart は必須です");
        }
        String key = buildTotalKey(userId, weekStart);
        String raw = redisTemplate.opsForValue().get(key);
        if (raw == null) {
            return 0;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            log.warn("Valkey カウンタが数値でない key={} value={}", key, raw);
            return 0;
        }
    }

    // ========================================
    // 内部ヘルパー（package-private で flush バッチからも利用）
    // ========================================

    /**
     * INCR 実行 + 初回時のみ EXPIRE 設定。
     *
     * @return INCR 後のカウント。Valkey が null を返した場合（接続異常等）も null を伝播する。
     */
    Long incrementWithTtl(String key, long ttlSeconds) {
        Long count = redisTemplate.opsForValue().increment(key);
        if (count == null) {
            return null;
        }
        if (count == 1L && ttlSeconds > 0L) {
            redisTemplate.expire(key, ttlSeconds, TimeUnit.SECONDS);
        }
        return count;
    }

    /**
     * DECR 実行。上限超過時のロールバック専用（直前に INCR 済みで値 ≥ 1 が保証される文脈でのみ使用）。
     * 例外を握り潰さず呼び出し元に伝播する。
     */
    void decrementSafely(String key) {
        redisTemplate.opsForValue().decrement(key);
    }

    /**
     * F09.19.3 §10.4 / §16 AC-3.8: 0 未満禁止のデクリメント。
     *
     * <p>GET して値が正のときのみ DECR する。キー不在（TTL 失効 or 未消費）や値 ≤ 0 は no-op とし、
     * カウンタが負値に落ちないことを保証する。予約 EXPIRED による FreqCap 返却で使用する。</p>
     */
    void decrementIfPositive(String key) {
        String raw = redisTemplate.opsForValue().get(key);
        if (raw == null) {
            return; // 失効済み or 未消費 → 安全な no-op（冪等）
        }
        long current;
        try {
            current = Long.parseLong(raw);
        } catch (NumberFormatException e) {
            log.warn("フリークエンシーキャップ: カウンタが数値でない key={} value={}", key, raw);
            return;
        }
        if (current > 0) {
            redisTemplate.opsForValue().decrement(key);
        }
    }

    /**
     * F09.19.3 §10.4 / §16 AC-3.8: 予約枠のロールバック（FreqCap 返却）。
     *
     * <p>予約 EXPIRED（{@code served_at IS NULL} のまま 14 日超過）時に、<b>予約の消費週</b>の
     * total キー・per-advertiser キーを 0 未満禁止で 1 ずつ返却する。
     * FreqCap キーは週境界 TTL（最大 7 日）で、14 日後の EXPIRED 発火時に現在週キーを DECR すると
     * <b>別週の生きた消費カウンタを誤って減らす</b>ため、必ず消費週（{@code consumptionWeekStart}）を対象にする。
     * 消費週キーが既に TTL 失効していれば両キーとも no-op（＝安全・冪等）。</p>
     *
     * @param userId               ユーザー ID（必須）
     * @param advertiserAccountId  広告主アカウント ID（必須）
     * @param consumptionWeekStart 予約を消費した週の月曜（ユーザー TZ）
     */
    public void releaseSlot(Long userId, Long advertiserAccountId, LocalDate consumptionWeekStart) {
        if (userId == null || advertiserAccountId == null || consumptionWeekStart == null) {
            throw new IllegalArgumentException("userId, advertiserAccountId, consumptionWeekStart は必須です");
        }
        String totalKey = buildTotalKey(userId, consumptionWeekStart);
        String perAdvKey = buildPerAdvertiserKey(advertiserAccountId, userId, consumptionWeekStart);
        decrementIfPositive(totalKey);
        decrementIfPositive(perAdvKey);
        log.debug("フリークエンシーキャップ返却 userId={} advertiser={} weekStart={}",
                userId, advertiserAccountId, consumptionWeekStart);
    }

    /**
     * 指定日を含む週の月曜（週開始）を返す。予約 {@code created_at} から消費週を求めるのに使う。
     */
    public static LocalDate weekStartOf(LocalDate date) {
        if (date.getDayOfWeek() == DayOfWeek.MONDAY) {
            return date;
        }
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    /**
     * ユーザー TZ を解決する。未設定・取得失敗時は {@link #FALLBACK_ZONE Asia/Tokyo} を返す。
     */
    ZoneId resolveUserZone(Long userId) {
        try {
            return userRepository.findTimezoneById(userId)
                    .filter(tz -> !tz.isBlank())
                    .map(tz -> {
                        try {
                            return ZoneId.of(tz);
                        } catch (RuntimeException ex) {
                            log.warn("不正なタイムゾーン userId={} tz={}", userId, tz);
                            return FALLBACK_ZONE;
                        }
                    })
                    .orElse(FALLBACK_ZONE);
        } catch (RuntimeException ex) {
            log.warn("タイムゾーン取得失敗 userId={}", userId, ex);
            return FALLBACK_ZONE;
        }
    }

    /**
     * 指定 TZ における「今週の月曜日」を返す。
     */
    static LocalDate currentWeekStart(ZoneId zone) {
        LocalDate today = LocalDate.now(zone);
        if (today.getDayOfWeek() == DayOfWeek.MONDAY) {
            return today;
        }
        return today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    /**
     * 「次週月曜 00:00（ユーザー TZ）」までの残秒を返す。
     */
    static long secondsUntilNextWeekStart(ZoneId zone) {
        ZonedDateTime now = ZonedDateTime.now(zone);
        LocalDate nextMonday = currentWeekStart(zone).plusWeeks(1);
        ZonedDateTime nextWeekStart = LocalDateTime.of(nextMonday, java.time.LocalTime.MIDNIGHT).atZone(zone);
        long seconds = java.time.Duration.between(now, nextWeekStart).getSeconds();
        // クロックずれ等で 0 以下になる場合の保護
        return Math.max(seconds, 60L);
    }

    static String buildTotalKey(Long userId, LocalDate weekStart) {
        return KEY_PREFIX_TOTAL + userId + ":" + weekStart;
    }

    static String buildPerAdvertiserKey(Long advertiserAccountId, Long userId, LocalDate weekStart) {
        return KEY_PREFIX_PER_ADV + advertiserAccountId + ":" + userId + ":" + weekStart;
    }
}
