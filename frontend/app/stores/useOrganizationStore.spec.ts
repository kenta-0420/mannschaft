// @vitest-environment node
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useOrganizationStore } from './useOrganizationStore'

const api = vi.fn()
vi.mock('~/composables/useApi', () => ({ useApi: () => api }))

function organization(id: number) {
  return { id, slug: `org-${id}`, name: `組織${id}`, nickname1: null, iconUrl: null,
    role: 'MEMBER', orgType: 'COMMUNITY', memberCount: 1 }
}

function pending() {
  let resolve!: (value: { data: ReturnType<typeof organization>[] }) => void
  let reject!: (reason: Error) => void
  const promise = new Promise<{ data: ReturnType<typeof organization>[] }>((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}

beforeEach(() => {
  setActivePinia(createPinia())
  api.mockReset()
  vi.stubGlobal('useApi', () => api)
})
afterEach(() => vi.unstubAllGlobals())

describe('組織一覧の同時取得', () => {
  it('同一storeの要求を共有し、成功後は空一覧でも再取得できる', async () => {
    const request = pending()
    api.mockReturnValueOnce(request.promise).mockResolvedValueOnce({ data: [organization(1)] })
    const store = useOrganizationStore()
    const first = store.fetchMyOrganizations()
    const second = store.fetchMyOrganizations()
    expect(api).toHaveBeenCalledTimes(1)
    expect(store.loading).toBe(true)
    request.resolve({ data: [] })
    await Promise.all([first, second])
    expect(store.loading).toBe(false)
    await store.fetchMyOrganizations()
    expect(api).toHaveBeenCalledTimes(2)
    expect(store.myOrganizations).toEqual([organization(1)])
  })

  it('共有要求が失敗しても次の取得を妨げない', async () => {
    const request = pending()
    api.mockReturnValueOnce(request.promise).mockResolvedValueOnce({ data: [organization(2)] })
    const store = useOrganizationStore()
    const first = store.fetchMyOrganizations()
    const second = store.fetchMyOrganizations()
    expect(api).toHaveBeenCalledTimes(1)
    request.reject(new Error('offline'))
    await Promise.all([first, second])
    expect(store.loading).toBe(false)
    await store.fetchMyOrganizations()
    expect(store.myOrganizations).toEqual([organization(2)])
  })

  it.each(['success', 'failure'] as const)('clear後の旧要求%sは新しい要求の状態を変更しない', async (outcome) => {
    const old = pending()
    const current = pending()
    api.mockReturnValueOnce(old.promise).mockReturnValueOnce(current.promise)
    const store = useOrganizationStore()
    const first = store.fetchMyOrganizations()
    store.clear()
    expect(store.myOrganizations).toEqual([])
    expect(store.loading).toBe(false)
    const second = store.fetchMyOrganizations()
    if (outcome === 'success') old.resolve({ data: [organization(1)] })
    else old.reject(new Error('old failure'))
    await first
    expect(store.myOrganizations).toEqual([])
    expect(store.loading).toBe(true)
    const third = store.fetchMyOrganizations()
    expect(api).toHaveBeenCalledTimes(2)
    current.resolve({ data: [organization(2)] })
    await Promise.all([second, third])
    expect(store.myOrganizations).toEqual([organization(2)])
    expect(store.loading).toBe(false)
  })

  it('別Piniaインスタンスへ取得結果を共有しない', async () => {
    const firstRequest = pending()
    const secondRequest = pending()
    api.mockReturnValueOnce(firstRequest.promise).mockReturnValueOnce(secondRequest.promise)
    const firstStore = useOrganizationStore(createPinia())
    const secondStore = useOrganizationStore(createPinia())
    const first = firstStore.fetchMyOrganizations()
    const second = secondStore.fetchMyOrganizations()
    expect(api).toHaveBeenCalledTimes(2)
    firstRequest.resolve({ data: [organization(1)] })
    secondRequest.resolve({ data: [organization(2)] })
    await Promise.all([first, second])
    expect(firstStore.myOrganizations).toEqual([organization(1)])
    expect(secondStore.myOrganizations).toEqual([organization(2)])
  })
})
