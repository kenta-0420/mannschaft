package com.mannschaft.app.weather.client;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * F02.10 天気ウィジェット — WeatherAPI.com から取得した今日／明日の予報を保持する内部 DTO。
 *
 * <p>WeatherAPI.com のレスポンス JSON（{@code forecast.forecastday[0]/[1]}）から
 * 必要なフィールドだけ抽出した、サービス層／キャッシュ層共通の正規化済みデータ。
 * 設計書: docs/features/F02.10_weather_widget.md §3「レスポンスから利用するフィールド」。</p>
 */
@Value
@Builder
public class WeatherForecastData {

    /** 今日の日付（WeatherAPI.com が緯度経度から判定したローカルタイムゾーン基準）。 */
    LocalDate todayDate;

    /** 明日の日付。 */
    LocalDate tomorrowDate;

    /** 今日の天気コード（{@code condition.code}）。 */
    int todayConditionCode;

    /** 明日の天気コード。 */
    int tomorrowConditionCode;

    /** 今日の天気テキスト（{@code lang} パラメータの言語）。 */
    String todayConditionText;

    /** 明日の天気テキスト。 */
    String tomorrowConditionText;

    /** 今日の最高気温（℃）。 */
    BigDecimal todayMaxTempC;

    /** 今日の最低気温（℃）。 */
    BigDecimal todayMinTempC;

    /** 明日の最高気温（℃）。 */
    BigDecimal tomorrowMaxTempC;

    /** 明日の最低気温（℃）。 */
    BigDecimal tomorrowMinTempC;

    /** 今日の平均湿度（%）。 */
    int todayAvgHumidity;

    /** 明日の平均湿度（%）。 */
    int tomorrowAvgHumidity;

    /** 今日の降水確率（%）。 */
    int todayChanceOfRain;

    /** 明日の降水確率（%）。 */
    int tomorrowChanceOfRain;

    /** WeatherAPI.com から取得した時刻（UTC）。 stale 判定に利用。 */
    Instant fetchedAt;

    /**
     * Jackson による JSON デシリアライズ用のコンストラクタ。
     * Lombok の {@code @Builder} は private コンストラクタを生成するため、
     * 明示的に {@code @JsonCreator} を備える。
     */
    @JsonCreator
    public WeatherForecastData(
            @JsonProperty("todayDate") LocalDate todayDate,
            @JsonProperty("tomorrowDate") LocalDate tomorrowDate,
            @JsonProperty("todayConditionCode") int todayConditionCode,
            @JsonProperty("tomorrowConditionCode") int tomorrowConditionCode,
            @JsonProperty("todayConditionText") String todayConditionText,
            @JsonProperty("tomorrowConditionText") String tomorrowConditionText,
            @JsonProperty("todayMaxTempC") BigDecimal todayMaxTempC,
            @JsonProperty("todayMinTempC") BigDecimal todayMinTempC,
            @JsonProperty("tomorrowMaxTempC") BigDecimal tomorrowMaxTempC,
            @JsonProperty("tomorrowMinTempC") BigDecimal tomorrowMinTempC,
            @JsonProperty("todayAvgHumidity") int todayAvgHumidity,
            @JsonProperty("tomorrowAvgHumidity") int tomorrowAvgHumidity,
            @JsonProperty("todayChanceOfRain") int todayChanceOfRain,
            @JsonProperty("tomorrowChanceOfRain") int tomorrowChanceOfRain,
            @JsonProperty("fetchedAt") Instant fetchedAt) {
        this.todayDate = todayDate;
        this.tomorrowDate = tomorrowDate;
        this.todayConditionCode = todayConditionCode;
        this.tomorrowConditionCode = tomorrowConditionCode;
        this.todayConditionText = todayConditionText;
        this.tomorrowConditionText = tomorrowConditionText;
        this.todayMaxTempC = todayMaxTempC;
        this.todayMinTempC = todayMinTempC;
        this.tomorrowMaxTempC = tomorrowMaxTempC;
        this.tomorrowMinTempC = tomorrowMinTempC;
        this.todayAvgHumidity = todayAvgHumidity;
        this.tomorrowAvgHumidity = tomorrowAvgHumidity;
        this.todayChanceOfRain = todayChanceOfRain;
        this.tomorrowChanceOfRain = tomorrowChanceOfRain;
        this.fetchedAt = fetchedAt;
    }
}
