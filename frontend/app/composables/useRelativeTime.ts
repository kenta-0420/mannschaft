function computeRelativeTime(dateStr: string): string {
  const now = Date.now()
  const target = new Date(dateStr).getTime()
  const diffMs = now - target
  const diffSec = Math.floor(diffMs / 1000)
  const diffMin = Math.floor(diffSec / 60)
  const diffHour = Math.floor(diffMin / 60)
  const diffDay = Math.floor(diffHour / 24)

  if (diffSec < 60) return 'たった今'
  if (diffMin < 60) return `${diffMin}分前`
  if (diffHour < 24) return `${diffHour}時間前`
  if (diffDay < 7) return `${diffDay}日前`
  return new Date(dateStr).toLocaleDateString('ja-JP')
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
