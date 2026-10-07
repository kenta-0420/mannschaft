/**
 * 相対時間表示 composable。
 * dayjs（日時パース・タイムゾーン処理）を土台に、本アプリ独自の表示規約で相対時間文字列を生成する。
 *
 * 表示規約（段階しきい値は #949 以来の UI 仕様。文言は i18n の現在ロケールで Intl.RelativeTimeFormat により生成する）：
 * - 1分未満: 「今」（ja）/ "now"（en）
 * - 1時間未満: 「n 分前」
 * - 24時間未満: 「n 時間前」
 * - 7日未満: 「n 日前」（1日前は numeric:'auto' により「昨日」）
 * - 7日以上: 現在ロケールの日付形式（ja 例: 2026/3/25）
 *
 * 後方互換性のため以下のオーバーロードを維持する：
 * - useRelativeTime(dateStr) → ComputedRef<string>（リアクティブな相対時間）
 * - useRelativeTime()        → { relativeTime, formatRelative }（関数オブジェクト）
 */
import dayjs from 'dayjs'

function computeRelativeTime(dateStr: string, locale: string): string {
  if (!dateStr) return ''
  const d = dayjs(dateStr)
  if (!d.isValid()) return ''

  const diffSec = dayjs().diff(d, 'second')
  const diffMin = Math.floor(diffSec / 60)
  const diffHour = Math.floor(diffMin / 60)
  const diffDay = Math.floor(diffHour / 24)

  const rtf = new Intl.RelativeTimeFormat(locale, { numeric: 'auto' })
  if (diffSec < 60) return rtf.format(0, 'second')
  if (diffMin < 60) return rtf.format(-diffMin, 'minute')
  if (diffHour < 24) return rtf.format(-diffHour, 'hour')
  if (diffDay < 7) return rtf.format(-diffDay, 'day')
  return d.toDate().toLocaleDateString(locale)
}

// Overload: called with a date ref/string → returns reactive ComputedRef<string>
export function useRelativeTime(dateStr: Ref<string> | string): ComputedRef<string>
// Overload: called without args → returns { relativeTime, formatRelative } functions
export function useRelativeTime(): {
  relativeTime: (dateStr: string) => string
  formatRelative: (dateStr: string) => string
}
export function useRelativeTime(
  dateStr?: Ref<string> | string,
):
  | ComputedRef<string>
  | { relativeTime: (dateStr: string) => string; formatRelative: (dateStr: string) => string } {
  // 現在ロケールは setup 時に nuxtApp を捕捉し、呼び出しごとに読む（言語切替に追従する）
  const { $i18n } = useNuxtApp()
  const compute = (value: string): string => computeRelativeTime(value, $i18n.locale.value)
  if (dateStr !== undefined) {
    const resolved = isRef(dateStr) ? dateStr : ref(dateStr)
    return computed(() => compute(resolved.value))
  }
  return { relativeTime: compute, formatRelative: compute }
}
