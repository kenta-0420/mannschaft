package com.mannschaft.app.weather.client;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * F02.10 天気ウィジェット — WeatherAPI.com から取得した予報を保持する内部 DTO。
 *
 * <p>WeatherAPI.com のレスポンス JSON（{@code forecast.forecastday[0..N-1]}）から
 * 必要なフィールドだけ抽出した、サービス層／キャッシュ層共通の正規化済みデータ。
 * 設計書: docs/features/F02.10_weather_widget.md §3「レスポンスから利用するフィールド」。</p>
 *
 * <p>2026-05-18 変更: 無料プラン上限（3 日）対応のため、今日／明日固定のフラットフィールドを
 * {@code days: List<DayData>} に統合した（インデックス 0=今日、1=明日、2=明後日）。</p>
 */
@Value
@Builder
public class WeatherForecastData {

    /** 各日の予報データ（インデックス 0 から時系列順、要素数は通常 3）。 */
    List<DayData> days;

    /** WeatherAPI.com から取得した時刻（UTC）。 stale 判定に利用。 */
    Instant fetchedAt;

    /**
     * Jackson による JSON デシリアライズ用のコンストラクタ。
     * Lombok の {@code @Builder} は private コンストラクタを生成するため、
     * 明示的に {@code @JsonCreator} を備える。
     */
    @JsonCreator
    public WeatherForecastData(
            @JsonProperty("days") List<DayData> days,
            @JsonProperty("fetchedAt") Instant fetchedAt) {
        this.days = days;
        this.fetchedAt = fetchedAt;
    }

    /**
     * 1 日分の予報データ。
     */
    @Value
    @Builder
    public static class DayData {
        /** 日付（WeatherAPI.com が緯度経度から判定したローカルタイムゾーン基準）。 */
        LocalDate date;
        /** 天気コード（{@code condition.code}）。 */
        int conditionCode;
        /** 天気テキスト（{@code lang} パラメータの言語）。 */
        String conditionText;
        /** 最高気温（℃）。 */
        BigDecimal maxTempC;
        /** 最低気温（℃）。 */
        BigDecimal minTempC;
        /** 平均湿度（%）。 */
        int avgHumidity;
        /** 降水確率（%）。 */
        int chanceOfRain;

        @JsonCreator
        public DayData(
                @JsonProperty("date") LocalDate date,
                @JsonProperty("conditionCode") int conditionCode,
                @JsonProperty("conditionText") String conditionText,
                @JsonProperty("maxTempC") BigDecimal maxTempC,
                @JsonProperty("minTempC") BigDecimal minTempC,
                @JsonProperty("avgHumidity") int avgHumidity,
                @JsonProperty("chanceOfRain") int chanceOfRain) {
            this.date = date;
            this.conditionCode = conditionCode;
            this.conditionText = conditionText;
            this.maxTempC = maxTempC;
            this.minTempC = minTempC;
            this.avgHumidity = avgHumidity;
            this.chanceOfRain = chanceOfRain;
        }
    }
}
