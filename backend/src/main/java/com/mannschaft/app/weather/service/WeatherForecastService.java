package com.mannschaft.app.weather.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.weather.client.WeatherApiClient;
import com.mannschaft.app.weather.client.WeatherForecastData;
import com.mannschaft.app.weather.config.WeatherApiProperties;
import com.mannschaft.app.weather.exception.WeatherProviderException;
import com.mannschaft.app.weather.metrics.WeatherMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * F02.10 天気ウィジェット — Valkey キャッシュ層 + WeatherAPI.com 呼び出しの統合サービス。
 *
 * <p>設計書: docs/features/F02.10_weather_widget.md §5.1 / §8 キャッシュ・障害時動作。</p>
 *
 * <h2>処理フロー</h2>
 * <ol>
 *   <li>Valkey に問い合わせ</li>
 *   <li>ヒット → JSON を {@link WeatherForecastData} に deserialize。
 *       {@code fetchedAt} から {@link WeatherApiProperties#getCacheTtlSeconds()} 以内なら fresh で返却</li>
 *   <li>fresh 範囲外（stale）or 完全ミス → {@link WeatherApiClient#fetchForecast} を呼び出し</li>
 *   <li>取得成功 → JSON にして {@code cacheTtlSeconds} で保存、fresh で返却</li>
 *   <li>WeatherAPI.com 失敗（{@link WeatherProviderException}）
 *       → 既存 stale データがあれば stale フラグで返却（TTL を {@code staleTtlSeconds} に延長）、
 *          なければ {@link WeatherProviderException} を再 throw</li>
 * </ol>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WeatherForecastService {

    /** Valkey キーのプレフィックス（設計書 §8.1）。 */
    static final String CACHE_KEY_PREFIX = "weather:";

    private final StringRedisTemplate redisTemplate;
    private final WeatherApiClient client;
    private final WeatherApiProperties props;
    private final ObjectMapper objectMapper;
    private final WeatherMetrics weatherMetrics;

    /**
     * 居住地点の今日・明日の予報を取得する。
     *
     * @param countryCode ISO 3166-1 alpha-2
     * @param latRounded  0.5 度丸めの緯度
     * @param lonRounded  0.5 度丸めの経度
     * @param lang        表示言語（WeatherAPI.com 未対応言語は {@link WeatherApiClient}
     *                    側で {@code en} にフォールバック）
     * @return 予報結果。stale 延命の場合 {@link WeatherForecastResult#stale()} = true
     */
    public WeatherForecastResult getForecast(
            String countryCode, BigDecimal latRounded, BigDecimal lonRounded, String lang) {

        String cacheKey = buildCacheKey(countryCode, latRounded, lonRounded, lang);

        // 1. Valkey 参照
        Optional<WeatherForecastData> cached = readCache(cacheKey);

        // 2. fresh ヒット → 即返却
        if (cached.isPresent() && isFresh(cached.get())) {
            weatherMetrics.recordCacheHit();
            return new WeatherForecastResult(cached.get(), false);
        }

        // 3. ミス or stale → WeatherAPI.com 呼び出し
        weatherMetrics.recordCacheMiss();
        try {
            long startMillis = System.currentTimeMillis();
            Optional<WeatherForecastData> fetched =
                    client.fetchForecast(latRounded, lonRounded, lang);
            long elapsedMillis = System.currentTimeMillis() - startMillis;
            if (fetched.isEmpty()) {
                // フェッチは成功したが空 → stale フォールバック試行
                return fallbackToStale(cached, cacheKey, null);
            }
            weatherMetrics.recordApiCall("success");
            weatherMetrics.recordApiLatency(elapsedMillis);
            WeatherForecastData fresh = fetched.get();
            writeCache(cacheKey, fresh, props.getCacheTtlSeconds());
            return new WeatherForecastResult(fresh, false);
        } catch (WeatherProviderException e) {
            log.warn("WeatherAPI.com 取得失敗 key={}: {}", cacheKey, e.getMessage());
            weatherMetrics.recordApiCall("error");
            return fallbackToStale(cached, cacheKey, e);
        }
    }

    /**
     * stale データがあれば TTL を {@code staleTtlSeconds} に延長して返却。
     * stale すらない場合は {@code originalCause}（あれば再 throw、なければ新規例外）で例外を投げる。
     */
    private WeatherForecastResult fallbackToStale(
            Optional<WeatherForecastData> cached, String cacheKey,
            WeatherProviderException originalCause) {
        if (cached.isPresent()) {
            WeatherForecastData data = cached.get();
            extendStaleTtl(cacheKey, data);
            return new WeatherForecastResult(data, true);
        }
        if (originalCause != null) {
            throw originalCause;
        }
        throw new WeatherProviderException("WeatherAPI.com 取得失敗 + stale キャッシュなし");
    }

    /**
     * stale データを再書き込みして TTL を延長する。例外は飲み込み（キャッシュ層の障害でも
     * ユーザーへの応答は継続するため）。
     */
    private void extendStaleTtl(String cacheKey, WeatherForecastData data) {
        try {
            writeCache(cacheKey, data, props.getStaleTtlSeconds());
        } catch (Exception ex) {
            log.warn("stale キャッシュ延命失敗 key={}: {}", cacheKey, ex.getMessage());
        }
    }

    /** fresh 判定（{@code cacheTtlSeconds} 以内）。 */
    boolean isFresh(WeatherForecastData data) {
        if (data.getFetchedAt() == null) return false;
        Duration age = Duration.between(data.getFetchedAt(), Instant.now());
        return age.getSeconds() < props.getCacheTtlSeconds();
    }

    /** Valkey 読み込み（JSON → DTO）。失敗時はキャッシュなし扱い。 */
    Optional<WeatherForecastData> readCache(String cacheKey) {
        try {
            String json = redisTemplate.opsForValue().get(cacheKey);
            if (json == null || json.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, WeatherForecastData.class));
        } catch (Exception e) {
            log.warn("Valkey 読み込み失敗 key={}: {}", cacheKey, e.getMessage());
            return Optional.empty();
        }
    }

    /** Valkey 書き込み（DTO → JSON）。失敗時は WARN ログのみ。 */
    void writeCache(String cacheKey, WeatherForecastData data, long ttlSeconds) {
        try {
            String json = objectMapper.writeValueAsString(data);
            redisTemplate.opsForValue().set(cacheKey, json, ttlSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Valkey 書き込み失敗 key={}: {}", cacheKey, e.getMessage());
        }
    }

    /**
     * キャッシュキー: {@code weather:{country}:{lat_r}:{lon_r}:{lang}}（設計書 §8.1）。
     */
    static String buildCacheKey(String countryCode, BigDecimal latRounded,
                                BigDecimal lonRounded, String lang) {
        return CACHE_KEY_PREFIX + countryCode + ":"
                + latRounded.toPlainString() + ":"
                + lonRounded.toPlainString() + ":"
                + (lang == null ? "" : lang);
    }
}
