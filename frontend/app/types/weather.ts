/**
 * F02.10 天気ウィジェット — フロントエンド型定義。
 *
 * BE の WeatherForecastResponse / DayForecastDto に対応。
 * Nuxt の useFetch はレスポンスを camelCase に変換しないため、
 * useApi() の実装に合わせてフィールド名を確認して定義している。
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
  today: DayForecast
  tomorrow: DayForecast
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
