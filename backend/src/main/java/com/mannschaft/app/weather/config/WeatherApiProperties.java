package com.mannschaft.app.weather.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * F02.10 天気ウィジェット — WeatherAPI.com クライアントの設定プロパティ。
 *
 * <p>{@code weather.api.*} 配下を本クラスにバインドする。
 * 設計書: docs/features/F02.10_weather_widget.md §3 / §7.3 / §7.3.1 / §8。</p>
 *
 * <p><b>SSRF 対策</b>: {@code baseUrl} は {@code application.yml} で
 * {@code https://api.weatherapi.com/v1} を固定値として記述する。
 * テスト環境のみ {@code @TestPropertySource} で差し替える運用とし、
 * 環境変数による上書きは行わない（設計書 §7.3.1）。</p>
 */
@ConfigurationProperties(prefix = "weather.api")
@Component
@Getter
@Setter
public class WeatherApiProperties {

    /** WeatherAPI.com のベース URL（{@code application.yml} で固定）。 */
    private String baseUrl = "https://api.weatherapi.com/v1";

    /** API キー（環境変数 {@code WEATHER_API_KEY} から注入）。 */
    private String apiKey = "";

    /** HTTP タイムアウト（ミリ秒）。 */
    private int timeoutMs = 3000;

    /** 通常キャッシュの TTL（秒）。 */
    private long cacheTtlSeconds = 3600L;

    /** 障害時の stale 延命 TTL（秒）。 */
    private long staleTtlSeconds = 21600L;
}
