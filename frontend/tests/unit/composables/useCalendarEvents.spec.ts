import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useCalendarEvents } from '~/composables/useCalendarEvents'
import { useAuthStore } from '~/stores/useAuthStore'

/**
 * useCalendarEvents の範囲組み立てユニットテスト（Issue #2508 Phase 1）。
 *
 * 背景:
 *   buildMonthRange / buildGridRange は `2026-08-01T00:00:00` のようなオフセット無しの
 *   ナイーブ文字列を手組みしており、BE 側でサーバー既定TZ（Asia/Tokyo）の壁時計として
 *   解釈されていた。ユーザーTZが JST 以外の場合、取得範囲が丸ごとずれる。
 *   BE（PR #2596）がオフセット付きを受理できるようになったため、FE は users.timezone 基準の
 *   オフセット付き文字列を送る。
 *
 * 検証観点:
 *   CAL-001: 月範囲（cacheHalfMonths>0）がユーザーTZのオフセット付きで、月初00:00〜月末23:59:59になる
 *   CAL-002: 非JSTユーザーではそのTZのオフセットが付く（+09:00 固定ではない）
 *   CAL-003: グリッド範囲（cacheHalfMonths=0）もオフセット付きになる
 */

function setUserTimezone(timezone: string) {
  const authStore = useAuthStore()
  authStore.user = {
    id: 1,
    email: 'user@example.com',
    fullName: 'Test User',
    profileImageUrl: null,
    timezone,
  }
}

beforeEach(() => {
  setActivePinia(createPinia())
  vi.useFakeTimers()
  // 2026-08-15 12:00 UTC（どのTZでも 2026-08 の範囲になる中日）
  vi.setSystemTime(new Date('2026-08-15T12:00:00Z'))
})

afterEach(() => {
  vi.useRealTimers()
})

describe('useCalendarEvents の範囲組み立て', () => {
  it('CAL-001: 月範囲はユーザーTZ（JST）の月初00:00:00〜月末23:59:59をオフセット付きで送る', async () => {
    setUserTimezone('Asia/Tokyo')
    const fetcher = vi.fn().mockResolvedValue([])
    const { loadEvents } = useCalendarEvents(fetcher, { cacheHalfMonths: 1 })

    await loadEvents()

    // 中心 2026-08 の ±1 ヶ月 → 2026-07-01 〜 2026-09-30
    expect(fetcher).toHaveBeenCalledWith('2026-07-01T00:00:00+09:00', '2026-09-30T23:59:59+09:00')
  })

  it('CAL-002: 非JSTユーザー（America/Los_Angeles）ではそのTZのオフセットが付く', async () => {
    setUserTimezone('America/Los_Angeles')
    const fetcher = vi.fn().mockResolvedValue([])
    const { loadEvents } = useCalendarEvents(fetcher, { cacheHalfMonths: 1 })

    await loadEvents()

    // 8月は PDT（-07:00）、9月末も PDT
    expect(fetcher).toHaveBeenCalledWith('2026-07-01T00:00:00-07:00', '2026-09-30T23:59:59-07:00')
  })

  it('CAL-003: グリッド範囲（cacheHalfMonths=0）もオフセット付きで送る', async () => {
    setUserTimezone('Asia/Tokyo')
    const fetcher = vi.fn().mockResolvedValue([])
    const { loadEvents } = useCalendarEvents(fetcher, { cacheHalfMonths: 0 })

    await loadEvents()

    const [from, to] = fetcher.mock.calls[0] as [string, string]
    expect(from).toMatch(/^\d{4}-\d{2}-\d{2}T00:00:00\+09:00$/)
    expect(to).toMatch(/^\d{4}-\d{2}-\d{2}T23:59:59\+09:00$/)
  })
})
