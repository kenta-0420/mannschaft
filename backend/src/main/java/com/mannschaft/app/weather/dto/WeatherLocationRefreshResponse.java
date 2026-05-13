package com.mannschaft.app.weather.dto;

/**
 * F02.10 天気ウィジェット — 地点リフレッシュ API レスポンス DTO。
 *
 * <p>設計書 §5.3「POST /api/v1/users/me/weather-location/refresh レスポンス仕様」準拠。</p>
 */
public record WeatherLocationRefreshResponse(
        /** UI 表示用の地名スナップショット（例: "東京都千代田区"）。 */
        String placeName,
        /** ISO 3166-1 alpha-2 国コード。 */
        String countryCode,
        /** 地点導出日時（ISO 8601 UTC、Z終端文字列）。 */
        String derivedAt
) {
}
