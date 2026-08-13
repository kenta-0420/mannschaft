import { describe, it, expect, beforeEach, vi } from 'vitest'
import { ref } from 'vue'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import type { CalendarEventItem } from '~/composables/useCalendarEvents'

/**
 * useMyCalendarData ユニットテスト（Issue #2637 の回帰ガード）。
 *
 * 旧実装は TODO 取得を `.catch(() => ({ data: [] }))` で握りつぶしており、
 * BE の 500・認可拒否・タイムアウトのいずれでも画面にもコンソールにも痕跡が残らず、
 * 「TODO を登録したのにカレンダーに出ない」を再現も原因特定もできなかった。
 *
 * 検証観点:
 *   MCD-001: TODO 取得が失敗してもカレンダー本体（個人予定・共有予定）は描画される
 *   MCD-002: TODO 取得失敗時に console.error と通知（トースト）の双方に痕跡が残り todosFailed が真になる
 *   MCD-003: TODO 取得が成功していれば todosFailed は偽・通知は出ない
 */

const mockListPersonalSchedules = vi.fn()
const mockGetCalendarRange = vi.fn()
const mockGetPersonalGanttTodos = vi.fn()
const mockError = vi.fn()

// useCalendarEvents は fetcher を受け取るだけのスタブにし、テストから fetcher を直接叩く。
let capturedFetcher: ((from: string, to: string) => Promise<CalendarEventItem[]>) | null = null

vi.mock('~/composables/useScheduleApi', () => ({
  useScheduleApi: () => ({
    listPersonalSchedules: mockListPersonalSchedules,
    getCalendarRange: mockGetCalendarRange,
  }),
}))
vi.mock('~/composables/useTodoGantt', () => ({
  useTodoGantt: () => ({ getPersonalGanttTodos: mockGetPersonalGanttTodos }),
}))
vi.mock('~/composables/useNotification', () => ({
  useNotification: () => ({ error: mockError, warn: vi.fn(), success: vi.fn(), info: vi.fn() }),
}))
vi.mock('~/composables/useCalendarEvents', () => ({
  useCalendarEvents: (fetcher: (from: string, to: string) => Promise<CalendarEventItem[]>) => {
    capturedFetcher = fetcher
    return {
      currentYear: ref(2026),
      currentMonth: ref(8),
      events: ref([]),
      loading: ref(false),
      calendarLoading: ref(false),
      loadEvents: vi.fn(),
      refresh: vi.fn(),
      onPrevMonth: vi.fn(),
      onNextMonth: vi.fn(),
    }
  },
}))
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))
mockNuxtImport('useDatetime', () => () => ({
  buildDayStartStr: (d: string) => `${d}T00:00:00+09:00`,
  buildDayEndStr: (d: string) => `${d}T23:59:59+09:00`,
}))

// eslint-disable-next-line import/first
import { useMyCalendarData } from '~/composables/useMyCalendarData'

const PERSONAL_ROW = {
  id: 1,
  content: { title: '個人予定', eventType: 'PERSONAL', color: '#22c55e' },
  time: { startAt: '2026-08-01T10:00:00+09:00', endAt: '2026-08-01T11:00:00+09:00', allDay: false },
}
const SHARED_ROW = {
  id: 2,
  content: { title: 'チーム予定', eventType: 'TEAM', status: 'CONFIRMED' },
  time: { startAt: '2026-08-02T10:00:00+09:00', endAt: '2026-08-02T11:00:00+09:00', allDay: false },
  scope: { scopeType: 'TEAM', scopeId: 'team-1', scopeName: 'チームA', scopeIconUrl: null },
  myAttendanceStatus: 'PENDING',
}

describe('useMyCalendarData', () => {
  beforeEach(() => {
    capturedFetcher = null
    mockListPersonalSchedules.mockReset()
    mockGetCalendarRange.mockReset()
    mockGetPersonalGanttTodos.mockReset()
    mockError.mockReset()
    mockListPersonalSchedules.mockResolvedValue({ data: [PERSONAL_ROW] })
    mockGetCalendarRange.mockResolvedValue({ data: [SHARED_ROW] })
  })

  it('MCD-001/002: TODO取得が失敗しても他2本は描画され、console.error・通知・todosFailedで表面化する', async () => {
    const consoleSpy = vi.spyOn(console, 'error').mockImplementation(() => {})
    const boom = new Error('500 Internal Server Error')
    mockGetPersonalGanttTodos.mockRejectedValue(boom)

    const cal = useMyCalendarData()
    expect(capturedFetcher).not.toBeNull()
    const merged = await capturedFetcher!('2026-08-01', '2026-08-31')

    // カレンダー本体は継続描画される（部分失敗）
    expect(merged.map((e) => e.uniqueKey)).toEqual(['personal:1', 'shared:2'])
    // 失敗は握りつぶされず、実際のエラーオブジェクトごと console に残る
    expect(consoleSpy).toHaveBeenCalledTimes(1)
    expect(JSON.stringify(consoleSpy.mock.calls[0])).toContain('TODO')
    expect(consoleSpy.mock.calls[0]?.[1]).toMatchObject({ error: boom })
    // ユーザーにもトーストで見える
    expect(mockError).toHaveBeenCalledTimes(1)
    // 利用側が扱える失敗状態
    expect(cal.todosFailed.value).toBe(true)

    consoleSpy.mockRestore()
  })

  it('MCD-003: TODO取得が成功すれば todosFailed は偽で通知も出ない', async () => {
    mockGetPersonalGanttTodos.mockResolvedValue({
      data: [{ id: 7, title: 'TODO', dueDate: '2026-08-10', startDate: null, status: 'OPEN', priority: 'HIGH' }],
    })

    const cal = useMyCalendarData()
    const merged = await capturedFetcher!('2026-08-01', '2026-08-31')

    expect(merged.map((e) => e.uniqueKey)).toContain('todo:7')
    expect(cal.todosFailed.value).toBe(false)
    expect(mockError).not.toHaveBeenCalled()
  })
})
