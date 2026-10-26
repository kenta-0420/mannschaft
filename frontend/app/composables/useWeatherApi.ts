import type { WeatherForecastResponse, WeatherLocationRefreshResponse } from '~/types/weather'

/**
 * F02.10 天気ウィジェット — API composable。
 *
 * useDashboardApi.ts と同じ useApi() ベースを使う。
 * エラーは throw させ、呼び出し側 WidgetWeather.vue で catch する。
 */
export function useWeatherApi() {
  const api = useApi()

  /**
   * 今日・明日の天気予報を取得する。
   * GET /api/v1/dashboard/weather
   */
  async function getDashboardWeather(): Promise<WeatherForecastResponse> {
    const res = await api<{ data: WeatherForecastResponse }>('/api/v1/dashboard/weather')
    return res.data
  }

  /**
   * 郵便番号から居住地点を再導出する。
   * POST /api/v1/users/me/weather-location/refresh
   */
  async function refreshWeatherLocation(): Promise<WeatherLocationRefreshResponse> {
    const res = await api<{ data: WeatherLocationRefreshResponse }>(
      '/api/v1/users/me/weather-location/refresh',
      { method: 'POST' },
    )
    return res.data
  }

  return { getDashboardWeather, refreshWeatherLocation }
}
