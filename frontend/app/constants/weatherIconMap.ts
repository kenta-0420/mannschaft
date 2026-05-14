/**
 * F02.10 天気ウィジェット — アイコンキーから SVG パスへのマッピング。
 *
 * BE がすでに condition_code → icon_key 変換済みで返してくるため、
 * このファイルは icon_key → 静的ファイルパスのマッピングのみを担当する。
 * SVG ファイルは public/icons/weather/ 配下に配置する。
 *
 * 設計書: docs/features/F02.10_weather_widget.md §6.3
 */
export const WEATHER_ICON_PATHS: Record<string, string> = {
  sunny: '/icons/weather/sunny.svg',
  partly_cloudy: '/icons/weather/partly-cloudy.svg',
  cloudy: '/icons/weather/cloudy.svg',
  overcast: '/icons/weather/overcast.svg',
  mist: '/icons/weather/mist.svg',
  rain: '/icons/weather/rain.svg',
  heavy_rain: '/icons/weather/heavy-rain.svg',
  snow: '/icons/weather/snow.svg',
  sleet: '/icons/weather/sleet.svg',
  thunderstorm: '/icons/weather/thunderstorm.svg',
}

/**
 * icon_key に対応する SVG ファイルパスを返す。
 * 未知のキーの場合は cloudy にフォールバックする。
 */
export function getWeatherIconPath(iconKey: string): string {
  return WEATHER_ICON_PATHS[iconKey] ?? (WEATHER_ICON_PATHS.cloudy as string)
}
