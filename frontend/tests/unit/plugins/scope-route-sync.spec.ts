import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import { flushPromises } from '@vue/test-utils'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { useNuxtApp } from '#app'
import { useScopeStore } from '~/stores/useScopeStore'
import { useTeamStore } from '~/stores/useTeamStore'
import { useOrganizationStore } from '~/stores/useOrganizationStore'
import scopePlugin from '~/plugins/scope.client'

// router と API は外部入力。plugin、scope resolver、Pinia store、保存処理は本物を動かす。
let afterEach: (to: { path: string }) => void = () => {}
const router = {
  currentRoute: ref({ path: '/teams/alpha/admin/settings' }),
  afterEach: vi.fn((callback: typeof afterEach) => {
    afterEach = callback
    return () => {}
  }),
}
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
mockNuxtImport('useRouter', () => () => router)
mockNuxtImport('useApi', () => () => api)

beforeEach(() => {
  pendingTeams = null
  router.currentRoute.value = { path: '/teams/alpha/admin/settings' }
  router.afterEach.mockClear()
  api.mockClear()
  useScopeStore().clear()
  useTeamStore().clear()
  useOrganizationStore().clear()
})

function navigate(path: string) {
  router.currentRoute.value = { path }
  afterEach({ path })
}

describe('scope.client の遅延同期と横断設定', () => {
  it('AC3/4: 初期A取得待ちからBへ移った後、A応答が戻ってもBの団体を上書きしない', async () => {
    let finish: () => void = () => {}
    pendingTeams = new Promise<void>((resolve) => { finish = resolve })
    scopePlugin(useNuxtApp())
    navigate('/organizations/beta/admin/settings')
    await flushPromises()
    expect(useScopeStore().current).toMatchObject({ type: 'organization', id: '7' })

    finish()
    await flushPromises()
    expect(useScopeStore().current).toMatchObject({ type: 'organization', id: '7' })
    expect(JSON.parse(localStorage.getItem('currentScope') ?? '{}')).toMatchObject({ type: 'organization', id: '7' })
  })

  it('AC3: 新scopeが確定後に横断設定へ移っても、旧Aの遅延応答は到着後の団体を変えない', async () => {
    let finish: () => void = () => {}
    pendingTeams = new Promise<void>((resolve) => { finish = resolve })
    scopePlugin(useNuxtApp())
    navigate('/organizations/beta/admin/settings')
    await flushPromises()
    navigate('/admin/receipt-settings')
    finish()
    await flushPromises()

    expect(useScopeStore().current).toMatchObject({ type: 'organization', id: '7' })
  })

  it('AC3: 未確定Aからscope外へ移った場合は後からAを登録しない', async () => {
    let finish: () => void = () => {}
    pendingTeams = new Promise<void>((resolve) => { finish = resolve })
    scopePlugin(useNuxtApp())
    navigate('/admin/line-settings')
    finish()
    await flushPromises()

    expect(useScopeStore().current).toMatchObject({ type: 'personal', id: null })
    expect(localStorage.getItem('currentScope')).toBeNull()
  })

  it('AC3: 同じ団体内の画面変更では通常同期を維持し、確定済scopeを横断設定で保持する', async () => {
    let finish: () => void = () => {}
    pendingTeams = new Promise<void>((resolve) => { finish = resolve })
    scopePlugin(useNuxtApp())
    navigate('/teams/alpha/info')
    finish()
    await flushPromises()
    expect(useScopeStore().current).toMatchObject({ type: 'team', id: '12' })
    navigate('/admin/line-settings')
    await flushPromises()
    expect(useScopeStore().current).toMatchObject({ type: 'team', id: '12' })
  })
})
