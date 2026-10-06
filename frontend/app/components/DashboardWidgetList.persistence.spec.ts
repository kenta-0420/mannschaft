import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import { flushPromises } from '@vue/test-utils'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import DashboardWidgetList from './DashboardWidgetList.vue'
import type { components } from '~/types/generated'

type Setting = components['schemas']['WidgetSettingResponse']
type Item = components['schemas']['WidgetSettingItem']
type LoadOptions = { server?: boolean; default: () => Setting[] }
const fixture = vi.hoisted(() => {
  const clientLoads: (() => Promise<void>)[] = []
  const rows: Setting[] = []
  return { api: vi.fn(), asyncData: vi.fn(), clientLoads, rows }
})
mockNuxtImport('useApi', () => () => fixture.api)
mockNuxtImport('useAsyncData', () => fixture.asyncData)

beforeEach(() => {
  fixture.api.mockReset()
  fixture.asyncData.mockReset()
  fixture.clientLoads = []
  fixture.rows = [{ widgetKey: 'PERSONAL_DINOSAUR_RANCH', visible: true, sortOrder: 0 }]
  fixture.api.mockImplementation(async (_path: string, options: { method?: string; body?: { widgets: Item[] } }) => {
    if (options.method === 'PUT') {
      fixture.rows = (options.body?.widgets ?? []).map(item => ({ widgetKey: item.widgetKey, visible: item.isVisible, sortOrder: item.sortOrder }))
    }
    return { data: fixture.rows }
  })
  // full-page SSRにはclient auth pluginの本人tokenが無い。SSR payloadの既定値を
  // hydrationで再利用するとclient GETが発行されない状態を再現する。
  fixture.asyncData.mockImplementation((_key: string, handler: () => Promise<Setting[]>, options: LoadOptions) => {
    const data = ref<Setting[]>(options.default())
    const status = ref<'idle' | 'success'>('idle')
    if (options.server === false) {
      fixture.clientLoads.push(async () => { data.value = await handler(); status.value = 'success' })
    } else {
      status.value = 'success'
    }
    return { data, status }
  })
})
async function settleClient() {
  for (const load of fixture.clientLoads.splice(0)) await load()
  await flushPromises()
}
async function mountList(scopeType: 'personal' | 'team' | 'organization' = 'personal') {
  return mountSuspended(DashboardWidgetList, { props: { scopeType, ...(scopeType === 'personal' ? {} : { scopeId: '42' }) } })
}

describe('本人widget設定のfull-page reload', () => {
  it('本人GET確定前は既定のONスイッチを操作させない', async () => {
    const wrapper = await mountList()
    expect(wrapper.findAll('[draggable="true"]')).toHaveLength(0)
    expect(wrapper.findAll('[role="switch"]')).toHaveLength(0)
    expect(fixture.api).not.toHaveBeenCalled()
    await settleClient()
    expect(wrapper.findAll('[role="switch"]').length).toBeGreaterThan(0)
  })
  it('通常UIで非表示を保存後fresh mountでも同じ本人のOFFを再取得する', async () => {
    const first = await mountList()
    await settleClient()
    const row = first.findAll('[draggable="true"]').find(item => item.text().includes('恐竜の部屋'))
    expect(row).toBeDefined()
    if (!row) throw new Error('恐竜widget行が必要')
    await row.get('input[role="switch"]').setValue(false)
    await flushPromises()
    expect(fixture.rows.find(item => item.widgetKey === 'PERSONAL_DINOSAUR_RANCH')?.visible).toBe(false)
    first.unmount()
    fixture.api.mockClear()
    const reloaded = await mountList()
    expect(reloaded.findAll('[role="switch"]')).toHaveLength(0)
    await settleClient()
    const restored = reloaded.findAll('[draggable="true"]').find(item => item.text().includes('恐竜の部屋'))
    expect(restored).toBeDefined()
    if (!restored) throw new Error('再読込後の恐竜widget行が必要')
    const input = restored.get('input[role="switch"]').element
    expect(input).toBeInstanceOf(HTMLInputElement)
    if (!(input instanceof HTMLInputElement)) throw new Error('checkboxの実DOMが必要')
    expect(input.checked).toBe(false)
    expect(fixture.api).toHaveBeenCalledTimes(1)
    expect(fixture.api).toHaveBeenCalledWith('/api/v1/dashboard/widgets', { query: { scopeType: 'personal', scopeId: undefined } })
  })
  it('PUT失敗時は楽観的OFFを保存済みONへ戻す', async () => {
    const wrapper = await mountList()
    await settleClient()
    fixture.api.mockRejectedValueOnce(new Error('synthetic save rejection'))
    const row = wrapper.findAll('[draggable="true"]').find(item => item.text().includes('恐竜の部屋'))
    if (!row) throw new Error('恐竜widget行が必要')
    const toggle = row.get('input[role="switch"]')
    await toggle.setValue(false)
    await flushPromises()
    if (!(toggle.element instanceof HTMLInputElement)) throw new Error('checkboxの実DOMが必要')
    expect(toggle.element.checked).toBe(true)
    expect(fixture.rows.find(item => item.widgetKey === 'PERSONAL_DINOSAUR_RANCH')?.visible).toBe(true)
    expect(fixture.api).toHaveBeenLastCalledWith('/api/v1/dashboard/widgets', expect.objectContaining({
      method: 'PUT', body: expect.objectContaining({ scopeType: 'personal', widgets: expect.arrayContaining([
        expect.objectContaining({ widgetKey: 'PERSONAL_DINOSAUR_RANCH', isVisible: false }),
      ]) }),
    }))
  })
  const sharedScopes: ('team' | 'organization')[] = ['team', 'organization']
  it.each(sharedScopes)('%sも認証後に保存順とOFFを反映する', async scope => {
    const prefix = scope === 'team' ? 'TEAM' : 'ORG'
    fixture.rows = [{ widgetKey: prefix + '_NOTICES', visible: true, sortOrder: 1 }, { widgetKey: prefix + '_BLOG', visible: false, sortOrder: 0 }]
    const wrapper = await mountList(scope)
    expect(wrapper.findAll('[draggable="true"]')).toHaveLength(0)
    await settleClient()
    const rows = wrapper.findAll('[draggable="true"]')
    const first = rows[0]?.get('input[role="switch"]').element
    const second = rows[1]?.get('input[role="switch"]').element
    if (!(first instanceof HTMLInputElement) || !(second instanceof HTMLInputElement)) throw new Error('保存順の実checkboxが必要')
    expect(first.checked).toBe(false)
    expect(second.checked).toBe(true)
    expect(fixture.api).toHaveBeenCalledWith('/api/v1/dashboard/widgets', { query: { scopeType: scope, scopeId: '42' } })
  })

})
