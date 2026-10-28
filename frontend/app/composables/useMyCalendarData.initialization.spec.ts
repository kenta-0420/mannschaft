import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { flushPromises } from '@vue/test-utils'
import { mountSuspended } from '@nuxt/test-utils/runtime'

const api = {
  getMyCalendarLayers: vi.fn(), listPersonalSchedules: vi.fn(), getCalendarRange: vi.fn(),
}
const todos = vi.fn()
const handleApiError = vi.fn()
vi.mock('~/composables/useScheduleApi', () => ({ useScheduleApi: () => api }))
vi.mock('~/composables/useTodoGantt', () => ({ useTodoGantt: () => ({ getMyCalendarTodos: todos }) }))
vi.mock('~/composables/useErrorHandler', () => ({ useErrorHandler: () => ({ handleApiError }) }))
vi.mock('~/composables/useErrorReport', () => ({ useErrorReport: () => ({ captureQuiet: vi.fn() }) }))
vi.mock('~/composables/useNotification', () => ({ useNotification: () => ({ error: vi.fn() }) }))
vi.mock('~/stores/useAuthStore', () => ({ useAuthStore: () => ({ currentUser: { id: 1 } }) }))
vi.mock('~/stores/useTeamStore', () => ({ useTeamStore: () => ({ myTeams: [], fetchMyTeams: vi.fn() }) }))
vi.mock('~/stores/useOrganizationStore', () => ({ useOrganizationStore: () => ({ myOrganizations: [], fetchMyOrganizations: vi.fn() }) }))

const { useMyCalendarData, __resetCalendarLayerMigrationForTest } = await import('./useMyCalendarData')
// 同じディレクトリの既存specと同様、初回のNuxt環境構築時間を含む。
vi.setConfig({ testTimeout: 60000 })
const KEY = 'mannschaft:calendar:layerState'
const layers = [{ scopeType: 'PERSONAL', scopeId: 0, hidden: false, color: '#059669', colorSource: 'LAYER_AUTO' }]
const fallback = {
  id: 501, scheduleId: 501,
  content: { title: '予定', eventType: 'PRACTICE', status: 'SCHEDULED', color: '#123456', colorSource: 'LAYER_AUTO' },
  time: { startAt: '2026-08-15T10:00:00+09:00', endAt: '2026-08-15T11:00:00+09:00', allDay: false },
  scope: { scopeType: 'TEAM', scopeId: 42, scopeName: 'チーム', scopeSlug: 'test-team' },
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(done => { resolve = done })
  return { promise, resolve }
}

async function boot() {
  let cal!: ReturnType<typeof useMyCalendarData>
  const wrapper = await mountSuspended(defineComponent({
    setup() {
      cal = useMyCalendarData()
      return () => h('div')
    },
  }))
  return { cal, wrapper }
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date('2026-08-11T03:00:00Z'))
  localStorage.clear()
  __resetCalendarLayerMigrationForTest()
  api.getMyCalendarLayers.mockResolvedValue({ data: layers })
  api.listPersonalSchedules.mockResolvedValue({ data: [] })
  api.getCalendarRange.mockResolvedValue({ data: [fallback] })
  todos.mockResolvedValue({ data: [] })
})
afterEach(() => { vi.restoreAllMocks(); vi.useRealTimers() })

describe('カレンダー初期取得の並列化', () => {
  it.each(['events', 'layers'])('%s先着でも復元まで表示・保存せず、外した既知fallbackを戻さない', async (first) => {
    const saved = JSON.stringify({ version: 2, selected: [], view: 'week', knownFallbackKeys: ['TEAM:42'] })
    localStorage.setItem(KEY, saved)
    const layerResult = deferred<{ data: typeof layers }>()
    const eventResult = deferred<{ data: typeof fallback[] }>()
    api.getMyCalendarLayers.mockReturnValue(layerResult.promise)
    api.getCalendarRange.mockReturnValue(eventResult.promise)
    const { cal, wrapper } = await boot()

    const initialized = cal.initialize()
    expect(api.getMyCalendarLayers).toHaveBeenCalledOnce()
    expect(api.getCalendarRange).toHaveBeenCalledOnce()
    if (first === 'events') eventResult.resolve({ data: [fallback] })
    else layerResult.resolve({ data: layers })
    await flushPromises()
    expect(cal.loading.value).toBe(true)
    expect(cal.filteredEvents.value).toEqual([])
    expect(localStorage.getItem(KEY)).toBe(saved)

    layerResult.resolve({ data: layers })
    eventResult.resolve({ data: [fallback] })
    expect(await initialized).toBe(true)
    await flushPromises()
    expect(cal.loading.value).toBe(false)
    expect(cal.selectedScopes.value).toEqual([])
    expect(cal.view.value).toBe('week')
    expect(cal.filteredEvents.value).toEqual([])
    wrapper.unmount()
  })

  it('events先着でも初期化後に新しいfallbackだけを自動選択する', async () => {
    const layerResult = deferred<{ data: typeof layers }>()
    api.getMyCalendarLayers.mockReturnValue(layerResult.promise)
    const { cal, wrapper } = await boot()
    const initialized = cal.initialize()
    await flushPromises()
    expect(localStorage.getItem(KEY)).toBeNull()
    layerResult.resolve({ data: layers })
    await initialized
    await flushPromises()
    expect(cal.selectedScopes.value).toEqual(['PERSONAL:0', 'TEAM:42'])
    expect(cal.filteredEvents.value.map(event => event.id)).toEqual([501])
    wrapper.unmount()
  })

  it('layer部分失敗後の再試行で回復し、メモリ上の全解除も維持する', async () => {
    api.getMyCalendarLayers.mockRejectedValueOnce(new Error('layer unavailable'))
    const { cal, wrapper } = await boot()
    await cal.initialize()
    expect(cal.layersFailed.value).toBe(true)
    expect(cal.filteredEvents.value).toHaveLength(1)
    const recoveredLayers = deferred<{ data: typeof layers }>()
    api.getMyCalendarLayers.mockReturnValueOnce(recoveredLayers.promise)
    const retried = cal.initialize()
    await flushPromises()
    expect(cal.loading.value).toBe(true)
    recoveredLayers.resolve({ data: layers })
    await retried
    await flushPromises()
    expect(cal.layersFailed.value).toBe(false)
    cal.multiSelectScopes.value = []
    await flushPromises()
    const getItem = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('storage unavailable') })
    await cal.initialize()
    expect(cal.selectedScopes.value).toEqual([])
    expect(api.getMyCalendarLayers).toHaveBeenCalledTimes(2)
    getItem.mockRestore()
    wrapper.unmount()
  })

  it('取得中に離脱したら旧キー移行・保存・新しい再試行取得を始めない', async () => {
    localStorage.setItem('mannschaft:calendar:scopeFilter', JSON.stringify(['PERSONAL']))
    const layerResult = deferred<{ data: typeof layers }>()
    api.getMyCalendarLayers.mockReturnValue(layerResult.promise)
    const { cal, wrapper } = await boot()
    const initialized = cal.initialize()
    wrapper.unmount()
    layerResult.resolve({ data: layers })
    expect(await initialized).toBe(false)
    await flushPromises()
    expect(localStorage.getItem(KEY)).toBeNull()
    expect(localStorage.getItem('mannschaft:calendar:scopeFilter')).toBe(JSON.stringify(['PERSONAL']))
    expect(await cal.initialize()).toBe(false)
    expect(api.getCalendarRange).toHaveBeenCalledOnce()
  })

  it('core失敗とTODO部分失敗を区別し、同じ初期化経路の再試行で0件へ回復する', async () => {
    api.getCalendarRange.mockRejectedValueOnce(new Error('core unavailable'))
    todos.mockRejectedValueOnce(new Error('todo unavailable'))
    const { cal, wrapper } = await boot()
    await cal.initialize()
    expect(cal.loadError.value).toBe(true)
    expect(cal.todosFailed.value).toBe(true)
    expect(cal.layersFailed.value).toBe(false)
    api.getCalendarRange.mockResolvedValueOnce({ data: [] })
    await cal.initialize()
    expect(cal.loading.value).toBe(false)
    expect(cal.loadError.value).toBe(false)
    expect(cal.todosFailed.value).toBe(false)
    expect(cal.filteredEvents.value).toEqual([])
    expect(api.getMyCalendarLayers).toHaveBeenCalledOnce()
    wrapper.unmount()
  })

  it.each(['broken-json', 'unavailable'])('初回storageが%sでも準備を完了し、hiddenを除く既定選択へ戻る', async (storage) => {
    api.getMyCalendarLayers.mockResolvedValue({ data: [
      ...layers,
      { scopeType: 'TEAM', scopeId: 99, hidden: true, color: '#2563eb', colorSource: 'LAYER_AUTO' },
    ] })
    if (storage === 'broken-json') localStorage.setItem(KEY, '{broken')
    const { cal, wrapper } = await boot()
    let read: ReturnType<typeof vi.spyOn> | undefined
    let write: ReturnType<typeof vi.spyOn> | undefined
    if (storage === 'unavailable') {
      // 読み書き両方を拒否する。Nuxt環境の準備後に注入し、検査対象の初期化だけを観測する。
      read = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new DOMException('storage unavailable', 'SecurityError') })
      write = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new DOMException('storage unavailable', 'SecurityError') })
    }

    expect(await cal.initialize()).toBe(true)
    await flushPromises()
    expect(cal.loading.value).toBe(false)
    expect(cal.view.value).toBe('month')
    expect(cal.selectedScopes.value).toEqual(['PERSONAL:0', 'TEAM:42'])
    expect(cal.selectedScopes.value).not.toContain('TEAM:99')
    expect(cal.filteredEvents.value).toHaveLength(1)
    if (storage === 'unavailable') {
      expect(read).toHaveBeenCalled()
      expect(write).toHaveBeenCalled()
    }
    wrapper.unmount()
  })
})
