// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useScopeDashboardStore } from '~/stores/useScopeDashboardStore'
import type { ScopeTabPage } from '~/types/dashboard-scope'

const getScopeTabs = vi.fn()

vi.mock('~/composables/useScopeTabApi', () => ({
  useScopeTabApi: () => ({ getScopeTabs }),
}))

function page(pageNumber: number, slug: string): ScopeTabPage {
  return {
    items: [{
      scopeId: String(pageNumber + 1),
      slug,
      scopeType: 'TEAM',
      name: slug,
      avatarUrl: null,
      unreadCount: 0,
      sortOrder: 0,
    }],
    page: pageNumber,
    pageSize: 6,
    totalPages: 1,
    totalCount: 1,
    hasNext: false,
    hasPrev: false,
  }
}

describe('useScopeDashboardStore.loadTabs', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    getScopeTabs.mockReset()
  })

  it('同一scopeで未解決の取得を共有しAPIを1回だけ呼ぶ', async () => {
    let resolveRequest!: (value: ScopeTabPage) => void
    getScopeTabs.mockReturnValue(new Promise(resolve => { resolveRequest = resolve }))
    const store = useScopeDashboardStore()

    const first = store.loadTabs('TEAM', 0)
    const second = store.loadTabs('TEAM', 0)

    expect(getScopeTabs).toHaveBeenCalledTimes(1)
    resolveRequest(page(0, 'team-a'))
    await Promise.all([first, second])
  })

  it('別pageの取得は独立し、遅い旧応答で最新表示を上書きしない', async () => {
    let resolveOld!: (value: ScopeTabPage) => void
    let resolveLatest!: (value: ScopeTabPage) => void
    getScopeTabs
      .mockReturnValueOnce(new Promise(resolve => { resolveOld = resolve }))
      .mockReturnValueOnce(new Promise(resolve => { resolveLatest = resolve }))
    const store = useScopeDashboardStore()

    const oldRequest = store.loadTabs('TEAM', 0)
    const latestRequest = store.loadTabs('TEAM', 1)
    resolveLatest(page(1, 'team-latest'))
    await latestRequest
    resolveOld(page(0, 'team-old'))
    await oldRequest

    expect(getScopeTabs).toHaveBeenCalledTimes(2)
    expect(store.tabPages.TEAM?.page).toBe(1)
    expect(store.selectedTeamId).toBe('team-latest')
  })

  it('取得開始後の利用者選択を遅い応答で巻き戻さない', async () => {
    let resolveRequest!: (value: ScopeTabPage) => void
    getScopeTabs.mockReturnValue(new Promise(resolve => { resolveRequest = resolve }))
    const store = useScopeDashboardStore()
    store.selectedTeamId = 'team-before'

    const request = store.loadTabs('TEAM', 0)
    store.selectedTeamId = 'team-manual'
    resolveRequest(page(0, 'team-server'))
    await request

    expect(store.selectedTeamId).toBe('team-manual')
  })

  describe.each(['TEAM', 'ORGANIZATION'] as const)('%s の数値slug選択', scopeType => {
    const selectedKey = scopeType === 'TEAM' ? 'selectedTeamId' : 'selectedOrgId'

    it.each([false, true])('FEED-SCOPE-12: 別scopeの内部IDより公開slug一致を優先する(逆順=%s)', async reverse => {
      const target = { ...page(0, '7').items[0]!, scopeType, scopeId: '21', slug: '7' }
      const collision = { ...page(0, 'other').items[0]!, scopeType, scopeId: '7', slug: 'other' }
      getScopeTabs.mockResolvedValue({ ...page(0, '7'), items: reverse ? [target, collision] : [collision, target] })
      const store = useScopeDashboardStore()
      store[selectedKey] = '7'

      await store.loadTabs(scopeType)

      expect(store[selectedKey]).toBe('7')
    })

    it('FEED-SCOPE-13: 公開slug一致がない旧内部IDは従来どおりslugへ移行する', async () => {
      getScopeTabs.mockResolvedValue({ ...page(0, 'canonical'), items: [{ ...page(0, 'canonical').items[0]!, scopeType, scopeId: '21' }] })
      const store = useScopeDashboardStore()
      store[selectedKey] = '21'

      await store.loadTabs(scopeType)

      expect(store[selectedKey]).toBe('canonical')
    })

    it('FEED-SCOPE-14: 取得中に利用者が変更した選択を衝突候補へ戻さない', async () => {
      let resolveRequest!: (value: ScopeTabPage) => void
      getScopeTabs.mockReturnValue(new Promise(resolve => { resolveRequest = resolve }))
      const store = useScopeDashboardStore()
      store[selectedKey] = '7'
      const request = store.loadTabs(scopeType)
      store[selectedKey] = 'manual'

      resolveRequest({ ...page(0, 'other'), items: [{ ...page(0, 'other').items[0]!, scopeType, scopeId: '7' }] })
      await request

      expect(store[selectedKey]).toBe('manual')
    })
  })
})
