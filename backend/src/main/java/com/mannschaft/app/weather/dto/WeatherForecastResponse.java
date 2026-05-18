package com.mannschaft.app.weather.dto;

import java.util.List;

/**
 * F02.10 天気ウィジェット — 天気予報 API レスポンス DTO。
 *
 * <p>設計書 §5.1「GET /api/v1/dashboard/weather レスポンス仕様」準拠。</p>
 *
 * <p>2026-05-18 変更: WeatherAPI.com 無料プラン上限（3 日）に合わせ、
 * 従来の today / tomorrow フィールドを {@code forecasts} 配列に統合した。
 * {@code forecasts.get(0)}=今日、{@code forecasts.get(1)}=明日、{@code forecasts.get(2)}=明後日。
 * 内部 API のため後方互換 shim は設けない（CLAUDE.md「障害対応の原則」§根治治療）。</p>
 */
public record WeatherForecastResponse(
        /** ユーザーの居住地点名スナップショット（例: 「東京都千代田区」）。 */
        String placeName,
        /** 今日・明日・明後日の予報（インデックス 0/1/2 の固定順、要素数 3）。 */
        List<DayForecastDto> forecasts,
        /** データソース名（固定値: "WeatherAPI.com"）。 */
        String dataSource,
        /** WeatherAPI.com からフェッチした時刻（ISO 8601 UTC、Z終端文字列）。 */
        String fetchedAt,
        /** stale 延命応答の場合 true。通常 TTL 超過・障害 TTL 以内のキャッシュを返す際に立つ。 */
        boolean isStale
) {
}
