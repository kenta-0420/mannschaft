package com.mannschaft.app.common.ratelimit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * Valkey ベースの分散レートリミッタ（docs/security/06 §4.3 正準実装）。
 *
 * <p>従来の Bucket4j + Caffeine（プロセス内カウント）は ECS 複数タスク構成で
 * タスク数に比例して実効上限が緩むため、Valkey の固定ウィンドウカウンタに置き換える。</p>
 *
 * <p>アルゴリズム（§4.3 固定ウィンドウ方式）:</p>
 * <ol>
 *   <li>{@code windowStart} = 現在 Unix 秒をウィンドウ長で切り捨て</li>
 *   <li>キー {@code mannschaft:rate:{zone}:{key}:{windowStart}} を Lua スクリプトで
 *       INCR し、初回（count == 1）のみ EXPIRE（ウィンドウ長 + 5 秒マージン）を設定する。
 *       INCR と EXPIRE を Lua で原子化することで「INCR 成功直後にクラッシュして
 *       TTL 無しキーが永久残留する」隙間を塞ぐ</li>
 *   <li>count &gt; limit なら拒否（呼び出し側が 429 を返す）</li>
 * </ol>
 *
 * <p><b>fail-open 設計（可用性優先）</b>: Valkey 障害（{@link DataAccessException} 系）や
 * Redis Bean 不在時は <b>リクエストを通す</b>。レートリミットは悪用防止の補助線であり、
 * その基盤障害でサービス全体を止めるのは本末転倒のため。fail-open 発生は
 * {@code log.warn} + Micrometer カウンタ {@value #FAIL_OPEN_METRIC} で必ず可視化し、
 * 「静かな無効化」にしない（エラー握り潰し禁止の原則と両立させる）。
 * 既存の auth 系 Valkey fail-open（{@code AuthService} / {@code AuthTokenService}）と同方針。</p>
 *
 * <p>{@link StringRedisTemplate} は {@link ObjectProvider} 経由で遅延解決する。
 * {@code @WebMvcTest} スライス等 Redis Bean が存在しない最小コンテキストでも
 * 本 Bean / 依存フィルタの生成が死なないようにするため。</p>
 */
@Slf4j
@Component
public class ValkeyRateLimiter {

    /** Valkey キーの接頭辞（§4.3）。 */
    static final String KEY_PREFIX = "mannschaft:rate:";

    /** TTL マージン秒。ウィンドウ跨ぎ直後の参照とクロックずれを吸収する。 */
    static final long TTL_MARGIN_SECONDS = 5L;

    /** fail-open 発生カウンタのメトリクス名（tag: zone / reason）。 */
    public static final String FAIL_OPEN_METRIC = "mannschaft.ratelimit.failopen";

    /**
     * INCR + 初回 EXPIRE を原子化する Lua スクリプト。
     * ARGV[1] = TTL 秒（ウィンドウ長 + マージン）。返り値は INCR 後のカウント値。
     */
    private static final String INCR_WITH_EXPIRE_LUA = """
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
              redis.call('EXPIRE', KEYS[1], tonumber(ARGV[1]))
            end
            return count
            """;

    private static final RedisScript<Long> INCR_WITH_EXPIRE_SCRIPT =
            new DefaultRedisScript<>(INCR_WITH_EXPIRE_LUA, Long.class);

    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

    /** 時刻源。テストからウィンドウ境界を制御できるよう差し替え可能にする。 */
    private Clock clock = Clock.systemUTC();

    public ValkeyRateLimiter(ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                             ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.redisTemplateProvider = redisTemplateProvider;
        this.meterRegistryProvider = meterRegistryProvider;
    }

    /**
     * 1 リクエスト分を消費し、通過可否と §4.3 ヘッダー用の値を返す。
     *
     * @param zone   バケット名前空間（フィルタ × エンドポイント単位で一意）
     * @param key    制限主体キー（{@code "u:{userId}"} / {@code "ip:{ip}"}）
     * @param limit  ウィンドウあたりの上限
     * @param window 固定ウィンドウ長
     * @return 判定結果（Valkey 障害時は fail-open で {@code allowed=true}）
     */
    public RateLimitResult tryConsume(String zone, String key, int limit, Duration window) {
        long windowSeconds = Math.max(1L, window.getSeconds());
        long nowEpochSeconds = clock.instant().getEpochSecond();
        long windowStart = (nowEpochSeconds / windowSeconds) * windowSeconds;
        long resetEpochSeconds = windowStart + windowSeconds;
        long retryAfterSeconds = Math.max(1L, resetEpochSeconds - nowEpochSeconds);
        String redisKey = KEY_PREFIX + zone + ":" + key + ":" + windowStart;

        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redisTemplate == null) {
            // Redis Bean 不在（@WebMvcTest スライス・最小テストコンテキスト等）。
            // 本番では Boot 自動構成により必ず存在するため、ここに来るのはテスト構成のみ。
            recordFailOpen(zone, "no_redis_bean");
            return RateLimitResult.failOpen(limit, resetEpochSeconds, retryAfterSeconds);
        }

        try {
            Long count = redisTemplate.execute(
                    INCR_WITH_EXPIRE_SCRIPT,
                    List.of(redisKey),
                    String.valueOf(windowSeconds + TTL_MARGIN_SECONDS));
            if (count == null) {
                // パイプライン/トランザクション中の実行など、結果が得られない異常系。
                log.warn("レートリミット Lua 実行結果が null（fail-open で通過させる）: zone={} key={}", zone, key);
                recordFailOpen(zone, "null_result");
                return RateLimitResult.failOpen(limit, resetEpochSeconds, retryAfterSeconds);
            }
            boolean allowed = count <= limit;
            long remaining = Math.max(0L, limit - count);
            return new RateLimitResult(allowed, limit, remaining, resetEpochSeconds, retryAfterSeconds);
        } catch (DataAccessException e) {
            // fail-open: Valkey 障害でレートリミットを諦め、リクエスト自体は通す（可用性優先）。
            // 障害は warn ログ + メトリクスで可視化する（握り潰さない）。
            log.warn("Valkey レートリミット障害のため fail-open で通過させる: zone={} key={}", zone, key, e);
            recordFailOpen(zone, "data_access");
            return RateLimitResult.failOpen(limit, resetEpochSeconds, retryAfterSeconds);
        }
    }

    private void recordFailOpen(String zone, String reason) {
        meterRegistryProvider.ifAvailable(registry ->
                Counter.builder(FAIL_OPEN_METRIC)
                        .description("Valkey レートリミットの fail-open 発生回数")
                        .tag("zone", zone)
                        .tag("reason", reason)
                        .register(registry)
                        .increment());
    }

    /** テスト専用: 時刻源を差し替える（ウィンドウ境界の検証用）。 */
    void setClock(Clock clock) {
        this.clock = clock;
    }
}
