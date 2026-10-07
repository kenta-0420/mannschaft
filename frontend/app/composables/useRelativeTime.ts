/**
 * 相対時間表示 composable。
 * dayjs（日時パース・タイムゾーン処理）を土台に、本アプリ独自の表示規約で相対時間文字列を生成する。
 * 文言は i18n（common.relativeTime.*）で持つ。Intl.RelativeTimeFormat は ja で
 * 「今」「5 分前」「昨日」と従来表示から変わるため使わない（ja は従来文言を維持する）。
 *
 * 表示規約（段階しきい値は #949 以来の UI 仕様）：
 * - 1分未満: 「たった今」
 * - 1時間未満: 「n分前」
 * - 24時間未満: 「n時間前」
 * - 7日未満: 「n日前」
 * - 7日以上: 現在ロケールの日付形式（ja 例: 2026/3/25）
 *
 * 後方互換性のため以下のオーバーロードを維持する：
 * - useRelativeTime(dateStr) → ComputedRef<string>（リアクティブな相対時間）
 * - useRelativeTime()        → { relativeTime, formatRelative }（関数オブジェクト）
 */
import dayjs from 'dayjs'

type Translate = (key: string, params?: Record<string, unknown>) => string

function computeRelativeTime(dateStr: string, t: Translate, locale: string): string {
  if (!dateStr) return ''
  const d = dayjs(dateStr)
  if (!d.isValid()) return ''

  const diffSec = dayjs().diff(d, 'second')
  const diffMin = Math.floor(diffSec / 60)
  const diffHour = Math.floor(diffMin / 60)
  const diffDay = Math.floor(diffHour / 24)

  if (diffSec < 60) return t('relativeTime.justNow')
  if (diffMin < 60) return t('relativeTime.minutesAgo', { count: diffMin })
  if (diffHour < 24) return t('relativeTime.hoursAgo', { count: diffHour })
  if (diffDay < 7) return t('relativeTime.daysAgo', { count: diffDay })
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
  // 現在ロケールは呼び出しごとに読む（言語切替に追従する）
  const { $i18n } = useNuxtApp()
  const compute = (value: string): string =>
    computeRelativeTime(value, $i18n.t as Translate, $i18n.locale.value)
  if (dateStr !== undefined) {
    const resolved = isRef(dateStr) ? dateStr : ref(dateStr)
    return computed(() => compute(resolved.value))
  }
  return { relativeTime: compute, formatRelative: compute }
}
