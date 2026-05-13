package com.mannschaft.app.weather.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * F02.10 天気ウィジェット — Bucket4j によるユーザー単位レートリミット設定。
 *
 * <p>設計書 §6.3: レートリミット仕様
 * <ul>
 *   <li>GET /api/v1/dashboard/weather: 60 req/h/user</li>
 *   <li>POST /api/v1/users/me/weather-location/refresh: 5 req/h/user</li>
 * </ul>
 *
 * <p>バケットはユーザー ID をキーとした in-memory {@link ConcurrentHashMap} で保持する。
 * 将来の Valkey バックエンド移行を想定したシンプルな実装（移行は別タスク）。</p>
 *
 * <p>セキュリティ: userId のみをキーとし、API キー等は一切保持しない。</p>
 */
@Component
public class WeatherRateLimiterConfig {

    /** GET エンドポイント用: 60 req/h/user。 */
    private static final int FORECAST_LIMIT = 60;

    /** POST エンドポイント用: 5 req/h/user。 */
    private static final int REFRESH_LIMIT = 5;

    private static final Duration WINDOW = Duration.ofHours(1);

    /** GET エンドポイント用バケットキャッシュ（userId → Bucket）。 */
    private final ConcurrentHashMap<Long, Bucket> forecastBuckets = new ConcurrentHashMap<>();

    /** POST エンドポイント用バケットキャッシュ（userId → Bucket）。 */
    private final ConcurrentHashMap<Long, Bucket> refreshBuckets = new ConcurrentHashMap<>();

    /**
     * GET /api/v1/dashboard/weather 用バケットを取得または生成する（60 req/h/user）。
     *
     * @param userId ユーザー ID
     * @return ユーザー専用の Bucket
     */
    public Bucket getForecastBucket(Long userId) {
        return forecastBuckets.computeIfAbsent(userId, id ->
                Bucket.builder()
                        .addLimit(Bandwidth.simple(FORECAST_LIMIT, WINDOW))
                        .build());
    }

    /**
     * POST /api/v1/users/me/weather-location/refresh 用バケットを取得または生成する（5 req/h/user）。
     *
     * @param userId ユーザー ID
     * @return ユーザー専用の Bucket
     */
    public Bucket getRefreshBucket(Long userId) {
        return refreshBuckets.computeIfAbsent(userId, id ->
                Bucket.builder()
                        .addLimit(Bandwidth.simple(REFRESH_LIMIT, WINDOW))
                        .build());
    }
}
