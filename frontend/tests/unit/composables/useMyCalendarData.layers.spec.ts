import { describe, it, expect, beforeEach, vi } from 'vitest'
import { nextTick, ref } from 'vue'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import type { CalendarEventItem } from '~/composables/useCalendarEvents'

/**
 * F03.19 統合カレンダービュー Wave 2-a — レイヤー状態管理の FE ユニットテスト。
 *
 * 検証観点（docs/features/F03.19_unified_calendar_view.md §9）:
 *   AC-15b : 旧キー（/calendar 側）のみ → 新キーへ移行・旧キー削除・"PERSONAL"→"PERSONAL:0"
 *   AC-15b2: 旧キー2種が併存 → /calendar 側を採用し両方削除
 *   AC-15b3: 移行はセッション中1度だけ。2回目の初期化が選択状態を巻き戻さない
 *   AC-15c : localStorage が空 → hidden=false のレイヤーが全選択
 *   AC-02相当: 予定0件のレイヤーも選択肢（allScopeOptions）に出る
 *   AC-03相当: 月を往復してもレイヤーの個数・並び順が変わらない（layers は events と独立）
 */

const mockListPersonalSchedules = vi.fn()
const mockGetCalendarRange = vi.fn()
const mockGetMyCalendarLayers = vi.fn()
const mockGetPersonalGanttTodos = vi.fn()

let capturedFetcher: ((from: string, to: string) => Promise<CalendarEventItem[]>) | null = null

vi.mock('~/composables/useScheduleApi', () => ({
  useScheduleApi: () => ({
    listPersonalSchedules: mockListPersonalSchedules,
    getCalendarRange: mockGetCalendarRange,
    getMyCalendarLayers: mockGetMyCalendarLayers,
  }),
}))
vi.mock('~/composables/useTodoGantt', () => ({
  useTodoGantt: () => ({ getMyCalendarTodos: mockGetPersonalGanttTodos }),
}))
vi.mock('~/composables/useErrorHandler', () => ({
  useErrorHandler: () => ({
    handleApiError: vi.fn(),
    handleError: vi.fn(),
    resolveMessage: (code: string) => code,
    getFieldErrors: () => ({}),
  }),
}))
vi.mock('~/composables/useNotification', () => ({
  useNotification: () => ({ error: vi.fn(), warn: vi.fn(), success: vi.fn(), info: vi.fn() }),
}))
vi.mock('~/composables/useErrorReport', () => ({
  useErrorReport: () => ({ captureQuiet: vi.fn(), capture: vi.fn() }),
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
import {
  useMyCalendarData,
  PERSONAL_KEY,
  __resetCalendarLayerMigrationForTest,
} from '~/composables/useMyCalendarData'

const LAYER_STATE_KEY = 'mannschaft:calendar:layerState'
const LEGACY_CALENDAR_KEY = 'mannschaft:calendar:scopeFilter'
const LEGACY_WIDGET_KEY = 'mannschaft:widget:calendar:scopeFilter'

const LAYERS_FIXTURE = [
  { scopeType: 'PERSONAL', scopeId: 0, scopeName: '個人', scopeNameKey: 'schedule.calendar.layer.personal', scopeIconUrl: null, color: '#059669', colorSource: 'LAYER_AUTO', hidden: false },
  { scopeType: 'TEAM', scopeId: 42, scopeName: '青葉FC', scopeNameKey: null, scopeIconUrl: null, color: '#DC2626', colorSource: 'LAYER_USER', hidden: false },
  { scopeType: 'TEAM', scopeId: 99, scopeName: '幽霊チーム', scopeNameKey: null, scopeIconUrl: null, color: '#2563EB', colorSource: 'LAYER_AUTO', hidden: true },
]

describe('useMyCalendarData — F03.19 W2-a レイヤー状態管理', () => {
  beforeEach(() => {
    capturedFetcher = null
    mockListPersonalSchedules.mockReset()
    mockGetCalendarRange.mockReset()
    mockGetMyCalendarLayers.mockReset()
    mockGetPersonalGanttTodos.mockReset()
    mockListPersonalSchedules.mockResolvedValue({ data: [] })
    mockGetCalendarRange.mockResolvedValue({ data: [] })
    mockGetPersonalGanttTodos.mockResolvedValue({ data: [] })
    mockGetMyCalendarLayers.mockResolvedValue({ data: LAYERS_FIXTURE })
    localStorage.clear()
    __resetCalendarLayerMigrationForTest()
  })

  it('AC-15b: 旧キー（/calendar 側）のみ存在 → 新キーへ移行され旧キーが消え、PERSONAL が PERSONAL:0 になる', async () => {
    localStorage.setItem(LEGACY_CALENDAR_KEY, JSON.stringify(['PERSONAL']))

    const cal = useMyCalendarData()
    await cal.initStorage()

    expect(cal.selectedScopes.value).toEqual(['PERSONAL:0'])
    expect(PERSONAL_KEY).toBe('PERSONAL:0')
    expect(localStorage.getItem(LEGACY_CALENDAR_KEY)).toBeNull()
    expect(localStorage.getItem(LEGACY_WIDGET_KEY)).toBeNull()

    const saved = JSON.parse(localStorage.getItem(LAYER_STATE_KEY)!)
    expect(saved.version).toBe(2)
    expect(saved.selected).toEqual(['PERSONAL:0'])
  })

  it('AC-15b2: 両方の旧キーが併存 → /calendar 側が採用され（["PERSONAL:0"]）、両方の旧キーが削除される', async () => {
    localStorage.setItem(LEGACY_CALENDAR_KEY, JSON.stringify(['PERSONAL']))
    localStorage.setItem(LEGACY_WIDGET_KEY, JSON.stringify(['PERSONAL', 'TEAM:42']))

    const cal = useMyCalendarData()
    await cal.initStorage()

    expect(cal.selectedScopes.value).toEqual(['PERSONAL:0'])
    expect(localStorage.getItem(LEGACY_CALENDAR_KEY)).toBeNull()
    expect(localStorage.getItem(LEGACY_WIDGET_KEY)).toBeNull()
  })

  it('AC-15b3: 移行はセッション中1度だけ。2回目の初期化が選択状態を巻き戻さない', async () => {
    localStorage.setItem(LEGACY_CALENDAR_KEY, JSON.stringify(['PERSONAL']))

    // 1回目（例: /calendar）: 移行が走り、その後ユーザーがチームも選択したとする。
    const first = useMyCalendarData()
    await first.initStorage()
    expect(first.selectedScopes.value).toEqual(['PERSONAL:0'])
    first.selectedScopes.value = ['PERSONAL:0', 'TEAM:42']
    // 永続化は selectedScopes の watch 経由（非同期）のため、テストでは直接新キーへ反映して
    // 「1回目のセッションで選択が変わった」状態を作る。
    localStorage.setItem(LAYER_STATE_KEY, JSON.stringify({ version: 2, selected: ['PERSONAL:0', 'TEAM:42'], view: 'month' }))

    // 旧キーが（何らかの理由で）再び存在しても、移行はモジュールスコープのフラグにより再実行されない。
    localStorage.setItem(LEGACY_WIDGET_KEY, JSON.stringify(['PERSONAL']))

    // 2回目（例: ウィジェット）: 新キーが既にあるので、選択状態は巻き戻らない。
    const second = useMyCalendarData()
    await second.initStorage()

    expect(second.selectedScopes.value).toEqual(['PERSONAL:0', 'TEAM:42'])
  })

  it('AC-15c: localStorage が空（初回訪問） → hidden=false のレイヤーが全選択される', async () => {
    const cal = useMyCalendarData()
    await cal.initStorage()

    expect(cal.selectedScopes.value).toEqual(['PERSONAL:0', 'TEAM:42'])
    expect(cal.selectedScopes.value).not.toContain('TEAM:99') // hidden=true は初期選択から外れる
  })

  it('AC-02相当: 予定が1件も無いレイヤーも allScopeOptions に含まれる', async () => {
    // イベントは0件（beforeEach で空応答）。それでもレイヤー一覧 API 由来で選択肢が出る。
    const cal = useMyCalendarData()
    await cal.loadLayers()

    const values = cal.allScopeOptions.value.map((o) => o.value)
    expect(values).toEqual(['PERSONAL:0', 'TEAM:42', 'TEAM:99'])
  })

  it('AC-03相当: 月を往復しても layers は再取得されず、レイヤーの個数・並び順が変わらない', async () => {
    const cal = useMyCalendarData()
    await cal.loadLayers()
    const before = cal.allScopeOptions.value.map((o) => o.value)

    expect(capturedFetcher).not.toBeNull()
    // 月移動（翌月→翌月→前月→前月）を模して fetcher を複数回叩く。
    await capturedFetcher!('2026-09-01', '2026-09-30')
    await capturedFetcher!('2026-10-01', '2026-10-31')
    await capturedFetcher!('2026-09-01', '2026-09-30')
    await capturedFetcher!('2026-08-01', '2026-08-31')

    // getMyCalendarLayers は loadLayers の1回のみ呼ばれ、月移動では再取得しない。
    expect(mockGetMyCalendarLayers).toHaveBeenCalledTimes(1)
    const after = cal.allScopeOptions.value.map((o) => o.value)
    expect(after).toEqual(before)
  })

  it('AC-23相当: レイヤー一覧に無いスコープの予定にフォールバックチップが既定選択で出る', async () => {
    mockGetCalendarRange.mockResolvedValue({
      data: [{
        id: 5,
        scheduleId: 5,
        content: { title: 'アーカイブ済みチームの予定', eventType: 'TEAM', status: 'CONFIRMED' },
        time: { startAt: '2026-08-05T10:00:00+09:00', endAt: '2026-08-05T11:00:00+09:00', allDay: false },
        scope: { scopeType: 'TEAM', scopeId: 'team-777', scopeName: '解散済みチーム', scopeIconUrl: null },
        myAttendanceStatus: 'PENDING',
      }],
    })

    const cal = useMyCalendarData()
    await cal.loadLayers()
    await cal.initStorage()
    await capturedFetcher!('2026-08-01', '2026-08-31')
    await nextTick() // watch(fallbackScopeOptions) の反映を待つ

    const fallbackValue = 'TEAM:team-777'
    expect(cal.allScopeOptions.value.map((o) => o.value)).toContain(fallbackValue)
    expect(cal.selectedScopes.value).toContain(fallbackValue) // 既定で選択状態
  })
})
