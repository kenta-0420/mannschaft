/**
 * 相対時間表示 composable。
 * dayjs の relativeTime プラグイン（日本語）を使用して日時文字列を相対表示に変換する。
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
  return d.fromNow()
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
