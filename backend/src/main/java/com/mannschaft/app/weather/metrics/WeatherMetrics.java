package com.mannschaft.app.weather.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * F02.10 天気ウィジェット — Micrometer メトリクス一元管理。
 *
 * <p>設計書 §10.1 に準拠した 5 種類のメトリクスを保持し、Service からの呼び出しで記録される。
 * Prometheus / Actuator 経由で取得される想定。</p>
 *
 * <ul>
 *   <li>{@code weather.api.calls{result=success/error/timeout}} — 外部 API 呼び出し回数</li>
 *   <li>{@code weather.api.latency} — WeatherAPI.com 応答時間</li>
 *   <li>{@code weather.cache.hits} — Valkey キャッシュヒット数</li>
 *   <li>{@code weather.cache.misses} — Valkey キャッシュミス数</li>
 *   <li>{@code weather.location.derive{result=success/postal_not_found/error}} — 郵便番号→座標導出結果</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class WeatherMetrics {

    private final MeterRegistry meterRegistry;

    private Counter apiCallSuccessCounter;
    private Counter apiCallErrorCounter;
    private Counter apiCallTimeoutCounter;
    private Timer apiLatencyTimer;
    private Counter cacheHitCounter;
    private Counter cacheMissCounter;
    private Counter locationDeriveSuccessCounter;
    private Counter locationDerivePostalNotFoundCounter;
    private Counter locationDeriveErrorCounter;

    @PostConstruct
    void init() {
        this.apiCallSuccessCounter = Counter.builder("weather.api.calls")
                .tag("result", "success")
                .description("WeatherAPI.com 呼び出し成功回数")
                .register(meterRegistry);
        this.apiCallErrorCounter = Counter.builder("weather.api.calls")
                .tag("result", "error")
                .description("WeatherAPI.com 呼び出しエラー回数")
                .register(meterRegistry);
        this.apiCallTimeoutCounter = Counter.builder("weather.api.calls")
                .tag("result", "timeout")
                .description("WeatherAPI.com 呼び出しタイムアウト回数")
                .register(meterRegistry);

        this.apiLatencyTimer = Timer.builder("weather.api.latency")
                .description("WeatherAPI.com 応答時間")
                .register(meterRegistry);

        this.cacheHitCounter = Counter.builder("weather.cache.hits")
                .description("Valkey キャッシュヒット数")
                .register(meterRegistry);
        this.cacheMissCounter = Counter.builder("weather.cache.misses")
                .description("Valkey キャッシュミス数")
                .register(meterRegistry);

        this.locationDeriveSuccessCounter = Counter.builder("weather.location.derive")
                .tag("result", "success")
                .description("郵便番号→座標導出 成功回数")
                .register(meterRegistry);
        this.locationDerivePostalNotFoundCounter = Counter.builder("weather.location.derive")
                .tag("result", "postal_not_found")
                .description("郵便番号→座標導出 郵便番号未ヒット回数")
                .register(meterRegistry);
        this.locationDeriveErrorCounter = Counter.builder("weather.location.derive")
                .tag("result", "error")
                .description("郵便番号→座標導出 その他エラー回数")
                .register(meterRegistry);
    }

    /**
     * WeatherAPI.com 呼び出し結果を記録する。
     *
     * @param result 呼び出し結果（"success" / "error" / "timeout"）
     */
    public void recordApiCall(String result) {
        switch (result) {
            case "success" -> apiCallSuccessCounter.increment();
            case "error" -> apiCallErrorCounter.increment();
            case "timeout" -> apiCallTimeoutCounter.increment();
            default -> apiCallErrorCounter.increment();
        }
    }

    /**
     * WeatherAPI.com の応答レイテンシを記録する。
     *
     * @param millis 応答時間（ミリ秒）
     */
    public void recordApiLatency(long millis) {
        apiLatencyTimer.record(millis, TimeUnit.MILLISECONDS);
    }

    /**
     * Valkey キャッシュヒットを記録する。
     */
    public void recordCacheHit() {
        cacheHitCounter.increment();
    }

    /**
     * Valkey キャッシュミスを記録する。
     */
    public void recordCacheMiss() {
        cacheMissCounter.increment();
    }

    /**
     * 郵便番号→座標導出の結果を記録する。
     *
     * @param result 導出結果（"success" / "postal_not_found" / "error"）
     */
    public void recordLocationDerive(String result) {
        switch (result) {
            case "success" -> locationDeriveSuccessCounter.increment();
            case "postal_not_found" -> locationDerivePostalNotFoundCounter.increment();
            case "error" -> locationDeriveErrorCounter.increment();
            default -> locationDeriveErrorCounter.increment();
        }
    }
}
