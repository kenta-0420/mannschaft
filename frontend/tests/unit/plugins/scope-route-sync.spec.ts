import { afterEach as afterEachTest, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { setActivePinia } from 'pinia'
import type { Router } from 'vue-router'
import { flushPromises } from '@vue/test-utils'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { useNuxtApp, useRouter } from '#app'
import { useScopeStore } from '~/stores/useScopeStore'
import { useTeamStore } from '~/stores/useTeamStore'
import { useOrganizationStore } from '~/stores/useOrganizationStore'
import scopePlugin from '~/plugins/scope.client'

// Nuxt app/Pinia/router は本物。APIと当該pluginへ渡すルート変更の入力だけを制御する。
let afterEach: (to: { path: string }) => void = () => {}
let router: Router
let restoreAfterEach: () => void = () => {}
let pendingTeams: Promise<void> | null = null
const api = vi.fn(async (path: string) => {
  if (path === '/api/v1/me/teams') {
    if (pendingTeams) await pendingTeams
    return { data: [{ id: 12, slug: 'alpha', name: 'チームA', role: 'ADMIN' }] }
  }
  if (path === '/api/v1/me/organizations') {
    return { data: [{ id: 7, slug: 'beta', name: '組織B', role: 'ADMIN' }] }
  }
  throw new Error(`Unexpected API: ${path}`)
})
// useApi→auth→所属storeの循環を保ち、本物のAPI/helperが使う通信境界だけ差し替える。
vi.mock('ofetch', async (importOriginal) => {
  const actual = await importOriginal<typeof import('ofetch')>()
  return {
    ...actual,
    ofetch: new Proxy(actual.ofetch, {
      get(target, key, receiver) {
        return key === 'create' ? () => api : Reflect.get(target, key, receiver)
      },
    }),
  }
})

beforeAll(async () => {
  const warmup = await mountSuspended(defineComponent({ render: () => h('div') }))
  warmup.unmount()
  router = useRouter()
})

beforeEach(() => {
  setActivePinia(useNuxtApp().$pinia)
  pendingTeams = null
  router.currentRoute.value = { ...router.currentRoute.value, path: '/teams/alpha/admin/settings', fullPath: '/teams/alpha/admin/settings' }
  const spy = vi.spyOn(router, 'afterEach').mockImplementation((callback) => {
    afterEach = () => callback(router.currentRoute.value, router.currentRoute.value, undefined)
    return () => {}
  })
  restoreAfterEach = () => spy.mockRestore()
  api.mockClear()
  useScopeStore().clear()
  useTeamStore().clear()
  useOrganizationStore().clear()
})
afterEachTest(() => { restoreAfterEach() })

function navigate(path: string) {
  router.currentRoute.value = { ...router.currentRoute.value, path, fullPath: path }
  afterEach({ path })
}

describe('scope.client の遅延同期と横断設定', () => {
  it('AC3/4: 初期A取得待ちからBへ移った後、A応答が戻ってもBの団体を上書きしない', async () => {
    let finish: () => void = () => {}
    pendingTeams = new Promise<void>((resolve) => { finish = resolve })
    useNuxtApp().runWithContext(() => scopePlugin(useNuxtApp()))
    navigate('/organizations/beta/admin/settings')
    await flushPromises()
    expect(api.mock.calls.filter(([path]) => path === '/api/v1/me/organizations')).toHaveLength(1)
    expect(useScopeStore().current).toMatchObject({ type: 'organization', id: '7' })

    finish()
    await flushPromises()
    expect(useScopeStore().current).toMatchObject({ type: 'organization', id: '7' })
    expect(JSON.parse(localStorage.getItem('currentScope') ?? '{}')).toMatchObject({ type: 'organization', id: '7' })
  })

  it('AC3: 新scopeが確定後に横断設定へ移っても、旧Aの遅延応答は到着後の団体を変えない', async () => {
    let finish: () => void = () => {}
    pendingTeams = new Promise<void>((resolve) => { finish = resolve })
    useNuxtApp().runWithContext(() => scopePlugin(useNuxtApp()))
    navigate('/organizations/beta/admin/settings')
    await flushPromises()
    expect(api.mock.calls.filter(([path]) => path === '/api/v1/me/organizations')).toHaveLength(1)
    navigate('/admin/receipt-settings')
    finish()
    await flushPromises()

    expect(useScopeStore().current).toMatchObject({ type: 'organization', id: '7' })
  })

  it('AC3: 未確定Aからscope外へ移った場合は後からAを登録しない', async () => {
    let finish: () => void = () => {}
    pendingTeams = new Promise<void>((resolve) => { finish = resolve })
    useNuxtApp().runWithContext(() => scopePlugin(useNuxtApp()))
    navigate('/admin/line-settings')
    finish()
    await flushPromises()

    expect(useScopeStore().current).toMatchObject({ type: 'personal', id: null })
    expect(localStorage.getItem('currentScope')).toBeNull()
  })

  it('AC3: 同じ団体内の画面変更では通常同期を維持し、確定済scopeを横断設定で保持する', async () => {
    let finish: () => void = () => {}
    pendingTeams = new Promise<void>((resolve) => { finish = resolve })
    useNuxtApp().runWithContext(() => scopePlugin(useNuxtApp()))
    navigate('/teams/alpha/info')
    finish()
    await flushPromises()
    expect(useScopeStore().current).toMatchObject({ type: 'team', id: '12' })
    navigate('/admin/line-settings')
    await flushPromises()
    expect(useScopeStore().current).toMatchObject({ type: 'team', id: '12' })
  })
})
