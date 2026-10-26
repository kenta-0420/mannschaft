package com.mannschaft.app.weather.service;

import com.mannschaft.app.weather.client.WeatherForecastData;

/**
 * F02.10 天気ウィジェット — サービス層が返す予報結果のラッパ。
 *
 * <p>{@code stale = true} のとき、直近の WeatherAPI.com 取得から
 * 通常 TTL（1 時間）を超過しているが障害時 TTL（6 時間）以内に収まる
 * 延命応答であることを示す。Controller 層はこれを {@code is_stale}
 * フィールドとしてレスポンスに含める（設計書 §5.1）。</p>
 */
public record WeatherForecastResult(WeatherForecastData data, boolean stale) {
}
