import { describe, it, expect, beforeEach, vi } from 'vitest'
import { nextTick, ref } from 'vue'
import type { Ref } from 'vue'
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
 *
 * 検分修繕（Codex検分 P1/P2、2026-08-25）:
 *   P1修繕: レイヤーキーに slug が混入し、チーム予定が一切絞り込めない欠陥の再発防止。
 *     モックはレイヤー側（数値 scopeId）とイベント側（scopeId=数値文字列 + 別途 scopeSlug）の
 *     形をわざと非対称にする＝実データの形そのもの。両側を同じ形で揃えたモックは偽の安心である。
 *   P2修繕a: 明示的に解除したフォールバックチップが復活しない
 *   P2修繕b: レイヤー取得の失敗がカレンダー全体（予定取得）を巻き込まない
 */

const mockListPersonalSchedules = vi.fn()
const mockGetCalendarRange = vi.fn()
const mockGetMyCalendarLayers = vi.fn()
const mockGetPersonalGanttTodos = vi.fn()
const mockHandleApiError = vi.fn()

let capturedFetcher: ((from: string, to: string) => Promise<CalendarEventItem[]>) | null = null
/**
 * 実装の useCalendarEvents は fetcher の戻り値を events へ反映するが、このモックは
 * 反映しない（fetcher の単体呼び出しを直接検証する既存テストの都合）。
 * filteredEvents / トグルの実挙動を検証するテストだけは、このrefへ直接書き込んで
 * 「予定取得後」の状態を模擬する。
 */
let capturedEventsRef: Ref<CalendarEventItem[]> | null = null

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
    handleApiError: mockHandleApiError,
    handleError: mockHandleApiError,
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
    const events = ref<CalendarEventItem[]>([]) as Ref<CalendarEventItem[]>
    capturedEventsRef = events
    return {
      currentYear: ref(2026),
      currentMonth: ref(8),
      events,
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
    capturedEventsRef = null
    mockListPersonalSchedules.mockReset()
    mockGetCalendarRange.mockReset()
    mockGetMyCalendarLayers.mockReset()
    mockGetPersonalGanttTodos.mockReset()
    mockHandleApiError.mockReset()
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

  it('P1修繕: レイヤー側は数値scopeId・イベント側はscopeId(数値文字列)+別途scopeSlugという非対称な実データでも、本来のレイヤーチップに紐づき重複しない', async () => {
    // 実データの形を再現する: BE の /me/calendar-layers は数値 scopeId のみ返す（LAYERS_FIXTURE）。
    // 一方 /my/calendar の scope はレイヤーと同じ数値 scopeId に加え、詳細API・URL用の
    // scopeSlug を別途持つ。両者を同じ形で揃えたモックは、この非対称性由来の不一致を
    // 検出できない偽の安心である。
    mockGetCalendarRange.mockResolvedValue({
      data: [{
        id: 10,
        scheduleId: 10,
        content: { title: '青葉FCの練習', eventType: 'PRACTICE', status: 'CONFIRMED' },
        time: { startAt: '2026-08-10T10:00:00+09:00', endAt: '2026-08-10T11:00:00+09:00', allDay: false },
        scope: { scopeType: 'TEAM', scopeId: '42', scopeSlug: 'aoba-fc', scopeName: '青葉FC', scopeIconUrl: null },
        myAttendanceStatus: 'PENDING',
      }],
    })

    const cal = useMyCalendarData()
    await cal.loadLayers()
    await cal.initStorage()
    const merged = await capturedFetcher!('2026-08-01', '2026-08-31')
    capturedEventsRef!.value = merged // 実装の useCalendarEvents が行う「取得結果を events へ反映」を模擬
    await nextTick()

    // 重複チップが出ない: allScopeOptions には TEAM:42 が1件だけで、slug 由来のキーは存在しない。
    const values = cal.allScopeOptions.value.map((o) => o.value)
    expect(values.filter((v) => v === 'TEAM:42')).toHaveLength(1)
    expect(values).not.toContain('TEAM:aoba-fc')
    expect(values.filter((v) => v.startsWith('TEAM:') && v !== 'TEAM:42' && v !== 'TEAM:99')).toEqual([])

    // 内部データの形を確認: scopeId は数値（レイヤーキー照合用）、scopeRouteId は slug（詳細API・URL用）。
    const ext = cal.extendedEvents.value.find((e) => e.uniqueKey === 'shared:10')
    expect(ext?.scopeId).toBe('42')
    expect(ext?.scopeRouteId).toBe('aoba-fc')

    // 本来のレイヤーチップ（TEAM:42）に正しく紐づく: 初期状態（hidden=false 全選択）で見える。
    expect(cal.selectedScopes.value).toContain('TEAM:42')
    expect(cal.filteredEvents.value.map((e) => e.uniqueKey)).toContain('shared:10')

    // チップを外すと消え、戻すと現れる（レイヤーキー照合が機能している証拠）。
    cal.toggleScope('TEAM:42')
    expect(cal.filteredEvents.value.map((e) => e.uniqueKey)).not.toContain('shared:10')
    cal.toggleScope('TEAM:42')
    expect(cal.filteredEvents.value.map((e) => e.uniqueKey)).toContain('shared:10')
  })

  it('P2修繕a: 明示的に解除したフォールバックチップは、月移動・再取得を経ても復活しない', async () => {
    const fallbackEvent = {
      id: 5,
      scheduleId: 5,
      content: { title: 'アーカイブ済みチームの予定', eventType: 'TEAM', status: 'CONFIRMED' },
      time: { startAt: '2026-08-05T10:00:00+09:00', endAt: '2026-08-05T11:00:00+09:00', allDay: false },
      scope: { scopeType: 'TEAM', scopeId: 'team-777', scopeName: '解散済みチーム', scopeIconUrl: null },
      myAttendanceStatus: 'PENDING',
    }
    mockGetCalendarRange.mockResolvedValue({ data: [fallbackEvent] })

    const cal = useMyCalendarData()
    await cal.loadLayers()
    await cal.initStorage()
    await capturedFetcher!('2026-08-01', '2026-08-31')
    await nextTick()

    const fallbackValue = 'TEAM:team-777'
    expect(cal.selectedScopes.value).toContain(fallbackValue) // 初回は既定で選択状態（AC-23）

    // ユーザーが明示的に外す。
    cal.toggleScope(fallbackValue)
    expect(cal.selectedScopes.value).not.toContain(fallbackValue)

    // 月移動（再取得）を模して同じフォールバックイベントを再度返す。
    // fallbackScopeOptions は毎回同じ値で再計算されるが、既に knownFallbackKeys に
    // 記録済みなので、外した選択が勝手に戻ってはならない。
    await capturedFetcher!('2026-09-01', '2026-09-30')
    await nextTick()
    expect(cal.selectedScopes.value).not.toContain(fallbackValue)

    // リロードを模す: 永続化された knownFallbackKeys を新しいインスタンスへ引き継ぐ。
    const persisted = JSON.parse(localStorage.getItem('mannschaft:calendar:layerState')!)
    expect(persisted.knownFallbackKeys).toContain(fallbackValue)
    expect(persisted.selected).not.toContain(fallbackValue)

    const reloaded = useMyCalendarData()
    await reloaded.loadLayers()
    await reloaded.initStorage()
    expect(reloaded.selectedScopes.value).not.toContain(fallbackValue)
    await capturedFetcher!('2026-08-01', '2026-08-31')
    await nextTick()
    // リロード後も、外した選択が復活しない。
    expect(reloaded.selectedScopes.value).not.toContain(fallbackValue)
  })

  it('P2修繕b: レイヤー取得が失敗しても layersFailed が立つだけで、予定取得（fetcher呼び出し）は独立して継続できる', async () => {
    const boom = new Error('layers 500')
    mockGetMyCalendarLayers.mockRejectedValue(boom)

    const cal = useMyCalendarData()

    // initStorage がレイヤー取得失敗を吸収し、reject しないこと（P2修繕の核心）。
    await expect(cal.initStorage()).resolves.toBeUndefined()

    expect(cal.layersFailed.value).toBe(true)
    expect(cal.layersLoaded.value).toBe(false)
    expect(mockHandleApiError).toHaveBeenCalledTimes(1)
    expect(String(mockHandleApiError.mock.calls[0]?.[1])).toContain('getMyCalendarLayers')

    // レイヤーが読めなくても、予定取得（fetcher）は握りつぶされず正常に完了する。
    mockGetCalendarRange.mockResolvedValue({
      data: [{
        id: 20,
        scheduleId: 20,
        content: { title: '予定は取れる', eventType: 'PRACTICE', status: 'CONFIRMED' },
        time: { startAt: '2026-08-20T10:00:00+09:00', endAt: '2026-08-20T11:00:00+09:00', allDay: false },
        scope: { scopeType: 'TEAM', scopeId: '42', scopeName: '青葉FC', scopeIconUrl: null },
        myAttendanceStatus: 'PENDING',
      }],
    })
    const merged = await capturedFetcher!('2026-08-01', '2026-08-31')
    expect(merged.map((e) => e.uniqueKey)).toContain('shared:20')
  })
})
