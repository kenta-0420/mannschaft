import { beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, nextTick, reactive, type Ref } from 'vue'
import { flushPromises } from '@vue/test-utils'
import { mockComponent, mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { clearNuxtData } from '#app'
import Dashboard from '~/pages/dashboard.vue'
import { useDashboardWidgets } from '~/composables/useDashboardWidgets'
import type { components } from '~/types/generated'

type Setting = components['schemas']['WidgetSettingResponse']
const fixture = vi.hoisted(() => ({ api: vi.fn(), auth: { user: null, loadFromStorage: vi.fn() } as { user: { id: number } | null; loadFromStorage: () => void }, scope: { activePanel: 'PERSONAL', loadFromStorage: vi.fn() }, showPanel: null as Ref<boolean> | null, panel: null as ReturnType<typeof useDashboardWidgets> | null }))
mockNuxtImport('useApi', () => () => fixture.api)
mockNuxtImport('useAuthStore', () => () => fixture.auth)
mockNuxtImport('useScopeDashboardStore', () => () => fixture.scope)
mockNuxtImport('useTeamStore', () => () => ({ myTeams: [{}], fetchMyTeams: vi.fn() }))
mockNuxtImport('useOrganizationStore', () => () => ({ myOrganizations: [{}], fetchMyOrganizations: vi.fn() }))
mockComponent('DashboardScopeCarousel', async () => {
  const { defineComponent, h, ref } = await import('vue')
  const { useDashboardWidgets } = await import('~/composables/useDashboardWidgets')
  fixture.showPanel = ref(false)
  const Consumer = defineComponent({ setup() { fixture.panel = useDashboardWidgets('personal'); return () => h('div') } })
  return defineComponent({ setup() { return () => fixture.showPanel?.value ? h(Consumer) : h('div') } })
})
const Consumer = defineComponent({ setup() { fixture.panel = useDashboardWidgets('personal'); return () => h('div') } })
let resolveRead!: (rows: Setting[]) => void
beforeEach(() => {
  clearNuxtData()
  fixture.auth = reactive({ user: { id: 101 } as { id: number } | null, loadFromStorage: vi.fn() })
  fixture.scope.activePanel = 'PERSONAL'
  fixture.showPanel!.value = false
  fixture.api.mockReset().mockImplementation(() => new Promise<{ data: Setting[] }>(resolve => { resolveRead = rows => resolve({ data: rows }) }))
})
async function shell() { return mountSuspended(Dashboard) }
async function reveal() { fixture.showPanel!.value = true; await nextTick(); await flushPromises() }
describe('dashboard setupからの個人設定取得', () => {
  it('個人panelのmount前にGETを開始し、mount後も同じ進行中handleを使う', async () => {
    await shell(); await flushPromises()
    expect(fixture.api).toHaveBeenCalledTimes(1)
    await reveal(); expect(fixture.api).toHaveBeenCalledTimes(1)
    expect(fixture.panel!.ready.value).toBe(false)
    resolveRead([{ widgetKey: 'PERSONAL_WEATHER', visible: false, sortOrder: 0 }]); await flushPromises()
    expect(fixture.panel!.ready.value).toBe(true); expect(fixture.panel!.isVisible('weather')).toBe(false)
  })
  it('成功済みhandleも再GETせず保存順と非表示を反映する', async () => {
    await shell(); await flushPromises(); expect(fixture.api).toHaveBeenCalledTimes(1)
    resolveRead([{ widgetKey: 'PERSONAL_WEATHER', visible: false, sortOrder: 1 }, { widgetKey: 'PERSONAL_CALENDAR', visible: true, sortOrder: 0 }])
    await flushPromises(); await reveal()
    expect(fixture.api).toHaveBeenCalledTimes(1)
    expect(fixture.panel!.orderedKeys.value.slice(0, 2)).toEqual(['my-calendar', 'weather']); expect(fixture.panel!.isVisible('weather')).toBe(false)
  })
  it.each(['TEAM', 'ORGANIZATION'])('初期%sでは個人GETせず後の個人mountで取得する', async active => {
    fixture.scope.activePanel = active; await shell(); await flushPromises()
    expect(fixture.api).not.toHaveBeenCalled(); await reveal(); expect(fixture.api).toHaveBeenCalledTimes(1)
  })
  it('単独panelは本人設定を取得し空の設定もreadyになる', async () => {
    await mountSuspended(Consumer); await flushPromises(); expect(fixture.api).toHaveBeenCalledTimes(1)
    resolveRead([]); await flushPromises(); expect(fixture.panel!.ready.value).toBe(true); expect(fixture.panel!.orderedKeys.value).toEqual([])
  })
  it('旧page破棄後の同じ本人の新sessionは再取得し旧GETの遅延成功で上書きされない', async () => {
    const oldPage = await shell(); await flushPromises()
    expect(fixture.api).toHaveBeenCalledTimes(1)
    const resolveOld = resolveRead
    oldPage.unmount(); await nextTick(); await flushPromises()
    fixture.auth.user = null
    fixture.auth.user = { id: 101 }
    await shell(); await flushPromises()
    expect(fixture.api).toHaveBeenCalledTimes(2)
    const resolveNew = resolveRead
    resolveNew([{ widgetKey: 'PERSONAL_WEATHER', visible: false, sortOrder: 0 }])
    await flushPromises(); await reveal()
    const newPanel = fixture.panel!
    expect(newPanel.ready.value).toBe(true)
    expect(newPanel.orderedKeys.value).toEqual(['weather'])
    expect(newPanel.isVisible('weather')).toBe(false)
    // transport mockはsignalを無視して成功する。旧世代の成功を新handleへ入れない。
    resolveOld([{ widgetKey: 'PERSONAL_CALENDAR', visible: false, sortOrder: 0 }])
    await flushPromises()
    expect(newPanel.ready.value).toBe(true)
    expect(newPanel.orderedKeys.value).toEqual(['weather'])
    expect(newPanel.isVisible('weather')).toBe(false)
    expect(newPanel.isVisible('my-calendar')).toBe(true)
    expect(fixture.api).toHaveBeenCalledTimes(2)
  })
  it('providerなしの成功した空設定はmountをまたいで固定せず次のGETで変更を読む', async () => {
    const first = await mountSuspended(Consumer); await flushPromises()
    expect(fixture.api).toHaveBeenCalledTimes(1)
    const resolveFirst = resolveRead
    resolveFirst([]); await flushPromises()
    expect(fixture.panel!.ready.value).toBe(true)
    expect(fixture.panel!.isVisible('weather')).toBe(true)
    first.unmount(); await nextTick(); await flushPromises()
    await mountSuspended(Consumer); await flushPromises()
    expect(fixture.api).toHaveBeenCalledTimes(2)
    const resolveSecond = resolveRead
    expect(fixture.panel!.ready.value).toBe(false)
    resolveSecond([{ widgetKey: 'PERSONAL_WEATHER', visible: false, sortOrder: 0 }])
    await flushPromises()
    expect(fixture.panel!.ready.value).toBe(true)
    expect(fixture.panel!.orderedKeys.value).toEqual(['weather'])
    expect(fixture.panel!.isVisible('weather')).toBe(false)
  })
  it('本人未復元時はGETせず復元後に一度取得する', async () => {
    fixture.auth.user = null; await mountSuspended(Consumer); await flushPromises(); expect(fixture.api).not.toHaveBeenCalled()
    fixture.auth.user = { id: 101 }; await flushPromises(); expect(fixture.api).toHaveBeenCalledTimes(1)
  })
  it('GET失敗はready契約を保ちpanel mountで再取得しない', async () => {
    fixture.api.mockRejectedValue(new Error('SYNTHETIC_READ_FAILURE'))
    await shell(); await flushPromises(); await reveal(); expect(fixture.api).toHaveBeenCalledTimes(1); expect(fixture.panel!.ready.value).toBe(true)
  })
  it.each(['logout', 'same-account-session', 'other-account'])('%s後の旧GET応答を反映しない', async boundary => {
    await shell(); await flushPromises(); expect(fixture.api).toHaveBeenCalledTimes(1)
    const oldResponse = resolveRead; await reveal(); fixture.auth.user = null
    if (boundary !== 'logout') fixture.auth.user = { id: boundary === 'other-account' ? 202 : 101 }
    oldResponse([{ widgetKey: 'PERSONAL_WEATHER', visible: false, sortOrder: 0 }]); await flushPromises()
    expect(fixture.panel!.isVisible('weather')).toBe(true); expect(fixture.panel!.orderedKeys.value).toEqual([])
  })
})
