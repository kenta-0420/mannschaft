import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { defineComponent, h } from 'vue'

/**
 * 色変更・自動色リセットが「チップだけでなく表示中の予定にも」即時反映されることを検証する
 * （F03.19 §10 のキャッシュ方針: 色変更直後に反映されない不整合を害と明記）。
 */

const updateMyCalendarLayer = vi.fn()
const deleteMyCalendarLayer = vi.fn()
const getMyCalendarLayers = vi.fn()
const getCalendarRange = vi.fn()
const listPersonalSchedules = vi.fn()
const getMyCalendarTodos = vi.fn()

vi.mock('~/composables/useScheduleApi', () => ({
  useScheduleApi: () => ({
    updateMyCalendarLayer,
    deleteMyCalendarLayer,
    getMyCalendarLayers,
    getCalendarRange,
    listPersonalSchedules,
  }),
}))
vi.mock('~/composables/useTodoGantt', () => ({
  useTodoGantt: () => ({ getMyCalendarTodos }),
}))
vi.mock('~/composables/useErrorHandler', () => ({
  useErrorHandler: () => ({ handleApiError: vi.fn() }),
}))
vi.mock('~/composables/useErrorReport', () => ({
  useErrorReport: () => ({ captureQuiet: vi.fn() }),
}))
vi.mock('~/composables/useNotification', () => ({
  useNotification: () => ({ error: vi.fn(), success: vi.fn() }),
}))
vi.mock('~/stores/useAuthStore', () => ({
  useAuthStore: () => ({ currentUser: { id: 1 } }),
}))
vi.mock('~/stores/useTeamStore', () => ({
  useTeamStore: () => ({ myTeams: [{ id: 42, slug: 'aoba' }], fetchMyTeams: vi.fn() }),
}))
vi.mock('~/stores/useOrganizationStore', () => ({
  useOrganizationStore: () => ({ myOrganizations: [], fetchMyOrganizations: vi.fn() }),
}))

const { useMyCalendarData } = await import('./useMyCalendarData')

// 各テストが mountSuspended で Nuxt 環境を組み立てるため、既定の 5 秒では
// 他ファイルと同時に走らせたときに最初の1件が環境構築だけで超過する
// （単体実行では 4.5 秒、同時実行では超える）。テスト内容の遅さではないので
// 待ち時間だけを広げる。
vi.setConfig({ testTimeout: 60000 })

/** 表示月の中日。events の期間フィルタに確実に入る日時を作る。 */
function midMonth(): string {
  const now = new Date()
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-15T10:00:00`
}

// 期待値が既定値・パレット既定と偶然一致して偽の緑になるのを避けるため、
// 変更前後で明確に異なり、かつ FE 側のどのフォールバック定数とも重ならない色を選ぶ。
const OLD_COLOR = '#123456'
const NEW_COLOR = '#CA8A04'
const RESET_COLOR = '#0D9488'
const NEUTRAL = '#94A3B8'

function sharedEntry(color: string, colorSource: string) {
  const at = midMonth()
  return {
    id: 501,
    scheduleId: 501,
    content: { title: '練習', eventType: 'PRACTICE', status: 'SCHEDULED', color, colorSource },
    time: { startAt: at, endAt: at, allDay: false },
    scope: { scopeType: 'TEAM', scopeId: 42, scopeName: '青葉FC', scopeIconUrl: null, scopeSlug: 'aoba' },
    myAttendanceStatus: 'ATTEND',
  }
}

function teamLayer(color: string, colorSource: string) {
  return {
    scopeType: 'TEAM',
    scopeId: 42,
    scopeName: '青葉FC',
    scopeNameKey: null,
    scopeIconUrl: null,
    color,
    colorSource,
    hidden: false,
  }
}

function personalLayer(color: string) {
  return {
    scopeType: 'PERSONAL',
    scopeId: 0,
    scopeName: 'PERSONAL',
    scopeNameKey: 'schedule.calendar.layer.personal',
    scopeIconUrl: null,
    color,
    colorSource: 'LAYER_AUTO',
    hidden: false,
  }
}

/**
 * `useMyCalendarData` は `useI18n` を使うため setup コンテキストが要る。
 * ダミーコンポーネントの setup で呼び出し、その戻り値をテストへ渡す。
 */
async function boot() {
  let api: ReturnType<typeof useMyCalendarData> | null = null
  const Host = defineComponent({
    setup() {
      api = useMyCalendarData()
      return () => h('div')
    },
  })
  await mountSuspended(Host)
  const cal = api as unknown as ReturnType<typeof useMyCalendarData>
  await cal.initStorage()
  await cal.loadEvents()
  return cal
}

async function setup(entryColorSource = 'CATEGORY') {
  localStorage.clear()
  getMyCalendarLayers.mockResolvedValue({ data: [personalLayer('#059669'), teamLayer(OLD_COLOR, 'LAYER_AUTO')] })
  getCalendarRange.mockResolvedValue({ data: [sharedEntry(OLD_COLOR, entryColorSource)] })
  listPersonalSchedules.mockResolvedValue({ data: [] })
  getMyCalendarTodos.mockResolvedValue({ data: [] })
  return boot()
}

describe('レイヤー色変更の即時反映（§10・P2）', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('色を変えると表示中の予定（filteredEvents）の色も同時に変わる', async () => {
    const cal = await setup()
    expect(cal.filteredEvents.value.map(e => e.color)).toEqual([OLD_COLOR])

    updateMyCalendarLayer.mockResolvedValue({ data: teamLayer(NEW_COLOR, 'LAYER_USER') })
    const ok = await cal.setLayerColor('TEAM', 42, NEW_COLOR)

    expect(ok).toBe(true)
    // チップ側
    expect(cal.allScopeOptions.value.find(o => o.value === 'TEAM:42')?.color).toBe(NEW_COLOR)
    // 表示中の予定側（再取得・月移動・リロードを一切していない）
    expect(cal.filteredEvents.value.map(e => e.color)).toEqual([NEW_COLOR])
    expect(getCalendarRange).toHaveBeenCalledTimes(1)
  })

  it('自動色へ戻すと、BE が再解決した色で表示中の予定も塗り替わる', async () => {
    const cal = await setup()
    updateMyCalendarLayer.mockResolvedValue({ data: teamLayer(NEW_COLOR, 'LAYER_USER') })
    await cal.setLayerColor('TEAM', 42, NEW_COLOR)
    expect(cal.filteredEvents.value.map(e => e.color)).toEqual([NEW_COLOR])

    // リセット後の色は §3.4 の優先2〜4 で BE が決める。FE には作れないので取り直す。
    deleteMyCalendarLayer.mockResolvedValue(undefined)
    getMyCalendarLayers.mockResolvedValue({ data: [personalLayer('#059669'), teamLayer(RESET_COLOR, 'LAYER_AUTO')] })
    getCalendarRange.mockResolvedValue({ data: [sharedEntry(RESET_COLOR, 'LAYER_AUTO')] })

    const ok = await cal.resetLayerColor('TEAM', 42)

    expect(ok).toBe(true)
    expect(cal.filteredEvents.value.map(e => e.color)).toEqual([RESET_COLOR])
    expect(cal.allScopeOptions.value.find(o => o.value === 'TEAM:42')?.color).toBe(RESET_COLOR)
  })

  it('レイヤー色の対象外（reflection）は塗り替えない（§3.4.1）', async () => {
    localStorage.clear()
    const at = midMonth()
    getMyCalendarLayers.mockResolvedValue({ data: [personalLayer('#059669')] })
    getCalendarRange.mockResolvedValue({
      data: [{
        id: null,
        scheduleId: null,
        content: {
          title: '振り返り', eventType: 'REFLECTION_RECALL', status: null,
          referenceUuid: 'u-1', referenceKind: 'REFLECTION_RECALL',
        },
        time: { startAt: at, endAt: at, allDay: true },
        scope: { scopeType: 'PERSONAL', scopeId: 0, scopeName: null, scopeIconUrl: null },
        myAttendanceStatus: 'NONE',
      }],
    })
    listPersonalSchedules.mockResolvedValue({ data: [] })
    getMyCalendarTodos.mockResolvedValue({ data: [] })
    const cal = await boot()
    expect(cal.filteredEvents.value.map(e => e.color)).toEqual(['#f59e0b'])

    updateMyCalendarLayer.mockResolvedValue({ data: { ...personalLayer(NEW_COLOR), colorSource: 'LAYER_USER' } })
    await cal.setLayerColor('PERSONAL', 0, NEW_COLOR)

    // 想起予定の橙は「種別」の意味を担うため、個人レイヤーの色では塗り替えない
    expect(cal.filteredEvents.value.map(e => e.color)).toEqual(['#f59e0b'])
  })
})

describe('フォールバックチップの色（§5.2.1 と §3.3 の板挟み）', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  async function bootWithFallbackEntry(scopeId: number, color: string, colorSource: string) {
    localStorage.clear()
    const at = midMonth()
    getMyCalendarLayers.mockResolvedValue({ data: [personalLayer('#059669')] })
    getCalendarRange.mockResolvedValue({
      data: [{
        id: 900 + scopeId,
        scheduleId: 900 + scopeId,
        content: { title: '外部', eventType: 'PRACTICE', status: 'SCHEDULED', color, colorSource },
        time: { startAt: at, endAt: at, allDay: false },
        scope: { scopeType: 'TEAM', scopeId, scopeName: 'レイヤー外', scopeIconUrl: null },
        myAttendanceStatus: 'ATTEND',
      }],
    })
    listPersonalSchedules.mockResolvedValue({ data: [] })
    getMyCalendarTodos.mockResolvedValue({ data: [] })
    return boot()
  }

  it('BE が自動色を載せている（colorSource=LAYER_AUTO）予定があればその色を採る', async () => {
    const cal = await bootWithFallbackEntry(777, '#7C3AED', 'LAYER_AUTO')

    const chip = cal.allScopeOptions.value.find(o => o.value === 'TEAM:777')
    expect(chip?.isFallback).toBe(true)
    expect(chip?.color).toBe('#7C3AED')
  })

  it('予定色・カテゴリ色しか無いスコープでは、その色を借りず中立色にする（嘘の色を出さない）', async () => {
    const cal = await bootWithFallbackEntry(778, OLD_COLOR, 'CATEGORY')

    const chip = cal.allScopeOptions.value.find(o => o.value === 'TEAM:778')
    expect(chip?.color).toBe(NEUTRAL)
    expect(chip?.color).not.toBe(OLD_COLOR)
  })
})
