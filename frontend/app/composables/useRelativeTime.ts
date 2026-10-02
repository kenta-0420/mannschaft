/**
 * 相対時間表示 composable。
 * dayjs（日時パース・タイムゾーン処理）を土台に、本アプリ独自の表示規約で相対時間文字列を生成する。
 *
 * 表示規約（#949 でのタイムゾーン対応前から続く UI 仕様。#2623 で dayjs.fromNow() への
 * 置き換えにより意図せず失われていたため、dayjs の相対差分計算はそのまま活かしつつ表示文言のみ復元する）：
 * - 1分未満: 「たった今」
 * - 1時間未満: 「n分前」
 * - 24時間未満: 「n時間前」
 * - 7日未満: 「n日前」
 * - 7日以上: 日付形式（例: 2026/3/25）
 *
 * 後方互換性のため以下のオーバーロードを維持する：
 * - useRelativeTime(dateStr) → ComputedRef<string>（リアクティブな相対時間）
 * - useRelativeTime()        → { relativeTime, formatRelative }（関数オブジェクト）
 */
import dayjs from 'dayjs'

function computeRelativeTime(dateStr: string): string {
  if (!dateStr) return ''
  const d = dayjs(dateStr)
  if (!d.isValid()) return ''

  const diffSec = dayjs().diff(d, 'second')
  const diffMin = Math.floor(diffSec / 60)
  const diffHour = Math.floor(diffMin / 60)
  const diffDay = Math.floor(diffHour / 24)

  if (diffSec < 60) return 'たった今'
  if (diffMin < 60) return `${diffMin}分前`
  if (diffHour < 24) return `${diffHour}時間前`
  if (diffDay < 7) return `${diffDay}日前`
  return d.toDate().toLocaleDateString('ja-JP')
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
  if (dateStr !== undefined) {
    const resolved = isRef(dateStr) ? dateStr : ref(dateStr)
    return computed(() => computeRelativeTime(resolved.value))
  }
  return { relativeTime: computeRelativeTime, formatRelative: computeRelativeTime }
}
