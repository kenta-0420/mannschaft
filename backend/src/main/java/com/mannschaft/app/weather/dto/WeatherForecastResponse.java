package com.mannschaft.app.weather.dto;

/**
 * F02.10 天気ウィジェット — 天気予報 API レスポンス DTO。
 *
 * <p>設計書 §5.1「GET /api/v1/dashboard/weather レスポンス仕様」準拠。</p>
 */
public record WeatherForecastResponse(
        /** 今日の予報。 */
        DayForecastDto today,
        /** 明日の予報。 */
        DayForecastDto tomorrow,
        /** データソース名（固定値: "WeatherAPI.com"）。 */
        String dataSource,
        /** WeatherAPI.com からフェッチした時刻（ISO 8601 UTC、Z終端文字列）。 */
        String fetchedAt,
        /** stale 延命応答の場合 true。通常 TTL 超過・障害 TTL 以内のキャッシュを返す際に立つ。 */
        boolean isStale
) {
}
