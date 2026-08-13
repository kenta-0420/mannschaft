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
 * さらに検分の指摘（二重トースト）を受け、通知は「useApi の共通ハンドラが
 * トーストを出さない失敗」に限る契約とした。共通ハンドラ（onResponseError）は
 * 実コード上、429（warn・即時）と 5xx（error・500ms 集約）でトーストを出す。
 *
 * 検証観点:
 *   MCD-001: TODO 取得が失敗してもカレンダー本体（個人予定・共有予定）は描画される
 *   MCD-002: 4xx（403）は共通ハンドラへ委譲してユーザー提示され todosFailed が真になる
 *   MCD-003: TODO 取得が成功していれば todosFailed は偽・提示も報告も無い
 *   MCD-004: 5xx は共通ハンドラが既にトーストを出すため追加提示しない（静かに報告のみ）
 *   MCD-005: 429 も同様に追加提示しない（静かに報告のみ）
 *   MCD-006: 応答なし（ネットワーク断）は共通ハンドラが何も出さないためユーザー提示する
 *   MCD-007: shouldNotifyTodoLoadFailure の判定表（共通ハンドラの担当 status を固定）
 */

const mockListPersonalSchedules = vi.fn()
const mockGetCalendarRange = vi.fn()
const mockGetPersonalGanttTodos = vi.fn()
const mockHandleApiError = vi.fn()
const mockCaptureQuiet = vi.fn()
// この層が（共通ハンドラを迂回して）直接トーストを出していないことを見張るための番人。
// 二重トーストは「共通ハンドラが出す status で、この層も出す」ことで起きるため、
// handleApiError だけでなく直接通知の経路も塞いだうえで固定する。
const mockNotifyError = vi.fn()

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
vi.mock('~/composables/useErrorHandler', () => ({
  useErrorHandler: () => ({
    handleApiError: mockHandleApiError,
    handleError: mockHandleApiError,
    resolveMessage: (code: string) => code,
    getFieldErrors: () => ({}),
  }),
}))
vi.mock('~/composables/useNotification', () => ({
  useNotification: () => ({ error: mockNotifyError, warn: vi.fn(), success: vi.fn(), info: vi.fn() }),
}))
vi.mock('~/composables/useErrorReport', () => ({
  useErrorReport: () => ({ captureQuiet: mockCaptureQuiet, capture: vi.fn() }),
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
import { useMyCalendarData, shouldNotifyTodoLoadFailure, extractHttpStatus } from '~/composables/useMyCalendarData'

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

/** ofetch の FetchError を模したエラー（statusCode と response.status の双方を持つ） */
function fetchError(status: number): Error & { statusCode: number; response: { status: number } } {
  const err = new Error(`HTTP ${status}`) as Error & { statusCode: number; response: { status: number } }
  err.statusCode = status
  err.response = { status }
  return err
}

describe('useMyCalendarData', () => {
  beforeEach(() => {
    capturedFetcher = null
    mockListPersonalSchedules.mockReset()
    mockGetCalendarRange.mockReset()
    mockGetPersonalGanttTodos.mockReset()
    mockHandleApiError.mockReset()
    mockCaptureQuiet.mockReset()
    mockNotifyError.mockReset()
    mockListPersonalSchedules.mockResolvedValue({ data: [PERSONAL_ROW] })
    mockGetCalendarRange.mockResolvedValue({ data: [SHARED_ROW] })
  })

  it('MCD-001/002: 403 でもカレンダー本体は描画され、共通エラーハンドラへ委譲して todosFailed が立つ', async () => {
    const boom = fetchError(403)
    mockGetPersonalGanttTodos.mockRejectedValue(boom)

    const cal = useMyCalendarData()
    expect(capturedFetcher).not.toBeNull()
    const merged = await capturedFetcher!('2026-08-01', '2026-08-31')

    // カレンダー本体は継続描画される（部分失敗）
    expect(merged.map((e) => e.uniqueKey)).toEqual(['personal:1', 'shared:2'])
    // 共通エラーハンドラ（errorReport.captureQuiet + 通知）へ委譲されている
    expect(mockHandleApiError).toHaveBeenCalledTimes(1)
    expect(mockHandleApiError.mock.calls[0]?.[0]).toBe(boom)
    expect(String(mockHandleApiError.mock.calls[0]?.[1])).toContain('getPersonalGanttTodos')
    // 利用側が扱える失敗状態
    expect(cal.todosFailed.value).toBe(true)
  })

  it('MCD-003: TODO取得が成功すれば todosFailed は偽で提示も報告も無い', async () => {
    mockGetPersonalGanttTodos.mockResolvedValue({
      data: [{ id: 7, title: 'TODO', dueDate: '2026-08-10', startDate: null, status: 'OPEN', priority: 'HIGH' }],
    })

    const cal = useMyCalendarData()
    const merged = await capturedFetcher!('2026-08-01', '2026-08-31')

    expect(merged.map((e) => e.uniqueKey)).toContain('todo:7')
    expect(cal.todosFailed.value).toBe(false)
    expect(mockHandleApiError).not.toHaveBeenCalled()
    expect(mockCaptureQuiet).not.toHaveBeenCalled()
  })

  it('MCD-004: 5xx は useApi の共通ハンドラが既にトーストを出すため追加提示しない（報告のみ）', async () => {
    mockGetPersonalGanttTodos.mockRejectedValue(fetchError(500))

    const cal = useMyCalendarData()
    const merged = await capturedFetcher!('2026-08-01', '2026-08-31')

    expect(merged.map((e) => e.uniqueKey)).toEqual(['personal:1', 'shared:2'])
    expect(mockHandleApiError).not.toHaveBeenCalled() // 二重トースト防止
    expect(mockNotifyError).not.toHaveBeenCalled() // 直接トーストも出さない
    expect(mockCaptureQuiet).toHaveBeenCalledTimes(1) // ただし握りつぶさない
    expect(cal.todosFailed.value).toBe(true) // 常設注記は出る
  })

  it('MCD-005: 429 も追加提示しない（共通ハンドラがレート制限トーストを出す）', async () => {
    mockGetPersonalGanttTodos.mockRejectedValue(fetchError(429))

    const cal = useMyCalendarData()
    await capturedFetcher!('2026-08-01', '2026-08-31')

    expect(mockHandleApiError).not.toHaveBeenCalled()
    expect(mockNotifyError).not.toHaveBeenCalled()
    expect(mockCaptureQuiet).toHaveBeenCalledTimes(1)
    expect(cal.todosFailed.value).toBe(true)
  })

  it('MCD-006: 応答なし（ネットワーク断）は共通ハンドラが何も出さないためユーザーへ提示する', async () => {
    mockGetPersonalGanttTodos.mockRejectedValue(new TypeError('Failed to fetch'))

    const cal = useMyCalendarData()
    await capturedFetcher!('2026-08-01', '2026-08-31')

    expect(mockHandleApiError).toHaveBeenCalledTimes(1)
    expect(cal.todosFailed.value).toBe(true)
  })

  it('MCD-007: shouldNotifyTodoLoadFailure — 共通ハンドラの担当 status（429/5xx）では出さない', () => {
    // useApi.onResponseError がトーストを出す status
    expect(shouldNotifyTodoLoadFailure(429)).toBe(false)
    expect(shouldNotifyTodoLoadFailure(500)).toBe(false)
    expect(shouldNotifyTodoLoadFailure(502)).toBe(false)
    expect(shouldNotifyTodoLoadFailure(503)).toBe(false)
    // 共通ハンドラがトーストを出さない status（提示はこの層の責務）
    expect(shouldNotifyTodoLoadFailure(400)).toBe(true)
    expect(shouldNotifyTodoLoadFailure(403)).toBe(true)
    expect(shouldNotifyTodoLoadFailure(404)).toBe(true)
    expect(shouldNotifyTodoLoadFailure(undefined)).toBe(true)
  })

  it('MCD-007b: extractHttpStatus — statusCode / response.status / 応答なしを解決する', () => {
    expect(extractHttpStatus(fetchError(403))).toBe(403)
    expect(extractHttpStatus({ response: { status: 500 } })).toBe(500)
    expect(extractHttpStatus(new Error('boom'))).toBeUndefined()
    expect(extractHttpStatus(null)).toBeUndefined()
  })
})
