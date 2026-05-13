package com.mannschaft.app.weather.dto;

/**
 * F02.10 天気ウィジェット — 1日分の予報データ DTO。
 *
 * <p>設計書 §5.1 レスポンス仕様に準拠。
 * {@code iconKey} は {@link com.mannschaft.app.weather.util.WeatherConditionMapper} で変換済みの値。</p>
 */
public record DayForecastDto(
        /** 日付（例: "2026-05-09"）。 */
        String date,
        /** WeatherAPI.com の condition.code。 */
        int conditionCode,
        /** WeatherAPI.com が返した翻訳済み天気テキスト。 */
        String conditionText,
        /** WeatherConditionMapper で変換済みの icon_key（例: "sunny"）。 */
        String iconKey,
        /** 最高気温（℃）。 */
        double maxTempC,
        /** 最低気温（℃）。 */
        double minTempC,
        /** 平均湿度（%）。 */
        int avgHumidity,
        /** 降水確率（%）。 */
        int chanceOfRain
) {
}
