/**
 * F02.10 天気ウィジェット — フロントエンド型定義。
 *
 * BE の WeatherForecastResponse / DayForecastDto に対応。
 * Nuxt の useFetch はレスポンスを camelCase に変換しないため、
 * useApi() の実装に合わせてフィールド名を確認して定義している。
 *
 * 2026-05-18 変更: WeatherAPI.com 無料プラン上限（3 日）に合わせ、
 * today / tomorrow フィールドを {@code forecasts} 配列に統合した。
 * forecasts[0]=今日、[1]=明日、[2]=明後日。
 */

export interface DayForecast {
  date: string
  conditionCode: number
  conditionText: string
  iconKey: string
  maxTempC: number
  minTempC: number
  avgHumidity: number
  chanceOfRain: number
}

export interface WeatherForecastResponse {
  placeName: string
  /** 今日・明日・明後日の予報。インデックス 0/1/2 の固定順、要素数 3。 */
  forecasts: DayForecast[]
  dataSource: string
  fetchedAt: string
  isStale: boolean
}

export interface WeatherLocationRefreshResponse {
  placeName: string
  countryCode: string
  derivedAt: string
}

export type WeatherErrorCode =
  | 'POSTAL_CODE_MISSING'
  | 'POSTAL_CODE_NOT_FOUND'
  | 'COUNTRY_NOT_SUPPORTED'
  | 'WEATHER_PROVIDER_UNAVAILABLE'
