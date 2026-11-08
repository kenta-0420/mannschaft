// @vitest-environment node
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useTeamStore } from './useTeamStore'

const api = vi.fn()
vi.mock('~/composables/useApi', () => ({ useApi: () => api }))

function team(id: number) {
  return { id, slug: `team-${id}`, name: `チーム${id}`, nickname1: null, iconUrl: null,
    role: 'MEMBER', template: 'SPORTS', memberCount: 1 }
}

function pending() {
  let resolve!: (value: { data: ReturnType<typeof team>[] }) => void
  let reject!: (reason: Error) => void
  const promise = new Promise<{ data: ReturnType<typeof team>[] }>((yes, no) => {
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

describe('チーム一覧の同時取得', () => {
  it('無signalの閲覧要求を共有し、0件後も再取得できる', async () => {
    const request = pending()
    api.mockReturnValueOnce(request.promise).mockResolvedValueOnce({ data: [team(2)] })
    const store = useTeamStore()
    const first = store.fetchMyTeams()
    const second = store.fetchMyTeams()
    const third = store.fetchMyTeams()
    expect(api).toHaveBeenCalledTimes(1)
    request.resolve({ data: [] })
    await Promise.all([first, second])
    await third
    expect(store.myTeams).toEqual([])
    expect(store.loading).toBe(false)
    await store.fetchMyTeams()
    expect(api).toHaveBeenCalledTimes(2)
    expect(store.myTeams).toEqual([team(2)])
  })

  it('共有した閲覧要求の失敗後に再取得できる', async () => {
    const request = pending()
    api.mockReturnValueOnce(request.promise).mockResolvedValueOnce({ data: [team(2)] })
    const store = useTeamStore()
    const first = store.fetchMyTeams()
    const second = store.fetchMyTeams()
    expect(api).toHaveBeenCalledTimes(1)
    request.reject(new Error('offline'))
    await Promise.all([first, second])
    expect(store.loading).toBe(false)
    await store.fetchMyTeams()
    expect(api).toHaveBeenCalledTimes(2)
    expect(store.myTeams).toEqual([team(2)])
  })

  it.each(['success', 'failure'] as const)('clear後の旧要求%sは新しい要求とflightを変更しない', async (outcome) => {
    const old = pending()
    const current = pending()
    api.mockReturnValueOnce(old.promise).mockReturnValueOnce(current.promise)
    const store = useTeamStore()
    const first = store.fetchMyTeams()
    store.clear()
    expect(store.myTeams).toEqual([])
    expect(store.loading).toBe(false)
    const second = store.fetchMyTeams()
    if (outcome === 'success') old.resolve({ data: [team(1)] })
    else old.reject(new Error('old failure'))
    await first
    expect(store.myTeams).toEqual([])
    expect(store.loading).toBe(true)
    const third = store.fetchMyTeams()
    expect(api).toHaveBeenCalledTimes(2)
    current.resolve({ data: [team(2)] })
    await Promise.all([second, third])
    expect(store.myTeams).toEqual([team(2)])
    expect(store.loading).toBe(false)
  })

  it('signal中断は独立要求とlast-winsを保ち、後続無signalを古いflightへ接続しない', async () => {
    const old = pending()
    const signaled = pending()
    const current = pending()
    api.mockReturnValueOnce(old.promise).mockReturnValueOnce(signaled.promise).mockReturnValueOnce(current.promise)
    const store = useTeamStore()
    const first = store.fetchMyTeams()
    const controller = new AbortController()
    const second = store.fetchMyTeamsWithResult({ signal: controller.signal })
    const third = store.fetchMyTeams()
    const fourth = store.fetchMyTeams()
    expect(api).toHaveBeenCalledTimes(3)
    expect(api.mock.calls[1]?.[1]?.signal).toBe(controller.signal)
    old.resolve({ data: [team(1)] })
    signaled.reject(new DOMException('aborted', 'AbortError'))
    await first
    expect(await second).toMatchObject({ ok: false, error: expect.objectContaining({ name: 'AbortError' }) })
    expect(store.myTeams).toEqual([])
    current.resolve({ data: [team(3)] })
    await Promise.all([third, fourth])
    expect(store.myTeams).toEqual([team(3)])
  })

  it('signal付き同士は共有せず、後から開始した取得の結果を採用する', async () => {
    const old = pending()
    const current = pending()
    api.mockReturnValueOnce(old.promise).mockReturnValueOnce(current.promise)
    const store = useTeamStore()
    const first = store.fetchMyTeamsWithResult({ signal: new AbortController().signal })
    const second = store.fetchMyTeamsWithResult({ signal: new AbortController().signal })
    expect(api).toHaveBeenCalledTimes(2)
    current.resolve({ data: [team(2)] })
    expect(await second).toEqual({ ok: true })
    old.resolve({ data: [team(1)] })
    expect(await first).toEqual({ ok: true })
    expect(store.myTeams).toEqual([team(2)])
  })

  it('更新後の直接取得は古い閲覧要求を共有せず、新しい一覧を採用する', async () => {
    const old = pending()
    const updated = pending()
    const next = pending()
    api.mockReturnValueOnce(old.promise).mockReturnValueOnce(updated.promise).mockReturnValueOnce(next.promise)
    const store = useTeamStore()
    const reading = store.fetchMyTeams()
    const refreshing = store.fetchMyTeamsWithResult()
    expect(api).toHaveBeenCalledTimes(2)
    updated.resolve({ data: [team(2)] })
    expect(await refreshing).toEqual({ ok: true })
    const following = store.fetchMyTeams()
    expect(api).toHaveBeenCalledTimes(3)
    old.resolve({ data: [team(1)] })
    await reading
    expect(store.myTeams).toEqual([team(2)])
    const shared = store.fetchMyTeams()
    expect(api).toHaveBeenCalledTimes(3)
    next.resolve({ data: [team(3)] })
    await Promise.all([following, shared])
    expect(store.myTeams).toEqual([team(3)])
  })

  it('別Piniaの要求を共有しない', async () => {
    const firstRequest = pending()
    const secondRequest = pending()
    api.mockReturnValueOnce(firstRequest.promise).mockReturnValueOnce(secondRequest.promise)
    const firstStore = useTeamStore(createPinia())
    const secondStore = useTeamStore(createPinia())
    const first = firstStore.fetchMyTeams()
    const second = secondStore.fetchMyTeams()
    expect(api).toHaveBeenCalledTimes(2)
    firstRequest.resolve({ data: [team(1)] })
    secondRequest.resolve({ data: [team(2)] })
    await Promise.all([first, second])
    expect(firstStore.myTeams).toEqual([team(1)])
    expect(secondStore.myTeams).toEqual([team(2)])
  })
})
