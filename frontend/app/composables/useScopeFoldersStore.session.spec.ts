// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { useAuthStore } from '~/stores/useAuthStore'
import { useScopeFoldersStore } from './useScopeFoldersStore'
import type { ScopeFolder } from '~/types/scopeFolder'

const { api } = vi.hoisted(() => ({ api: vi.fn<(...args: unknown[]) => Promise<unknown>>() }))
mockNuxtImport('useApi', () => () => api)
mockNuxtImport('useChatTabsStore', () => () => ({ clearAll: vi.fn() }))
mockNuxtImport('disarmProactiveRefresh', () => vi.fn())
mockNuxtImport('navigateTo', () => vi.fn())
vi.mock('~/stores/useTeamStore', () => ({ useTeamStore: () => ({ clear: vi.fn() }) }))
vi.mock('~/stores/useOrganizationStore', () => ({ useOrganizationStore: () => ({ clear: vi.fn() }) }))

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: Error) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

function folder(id = 1, name = '合成フォルダ'): ScopeFolder {
  return { id, name, color: null, isDefault: false, sortOrder: 0, itemScopeIds: ['scope-test'] }
}

function user(id: number) {
  return { id, email: '', fullName: '合成ユーザー', profileImageUrl: null }
}

type Store = ReturnType<typeof useScopeFoldersStore>

function seed(store: Store, name = '現在のフォルダ') {
  store.myTeamFolders = [folder(1, name), folder(2, name)]
  store.myOrgFolders = [folder(3, name)]
  store.defaultTeamFolderId = 1
  store.defaultOrgFolderId = 3
  store.notificationSummaryByFolder = { 1: 7, 3: 9 }
}

function expectEmpty(store: Store) {
  expect(store.myTeamFolders).toEqual([])
  expect(store.myOrgFolders).toEqual([])
  expect(store.defaultTeamFolderId).toBeNull()
  expect(store.defaultOrgFolderId).toBeNull()
  expect(store.notificationSummaryByFolder).toEqual({})
  expect(store.loading).toBe(false)
}

describe('scopeFoldersの認証セッション分離', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    api.mockReset()
    api.mockResolvedValue({ data: [] })
    vi.spyOn(useAuthStore(), 'clearUserCaches').mockResolvedValue()
  })

  it('logoutは非同期cache破棄を待たずにfolderとsummaryとloadingを消す', async () => {
    const auth = useAuthStore()
    auth.user = user(1)
    const store = useScopeFoldersStore()
    seed(store)
    store.loading = true
    const cleanup = deferred<undefined>()
    vi.mocked(auth.clearUserCaches).mockReturnValue(cleanup.promise)

    const pending = auth.logout()
    expectEmpty(store)
    cleanup.resolve(undefined)
    await pending
  })

  it.each([null, 1])('setUserは旧user=%sから別userへ変わると同期的にclearする', async (previous) => {
    const auth = useAuthStore()
    auth.user = previous === null ? null : user(previous)
    const store = useScopeFoldersStore()
    seed(store)

    const pending = auth.setUser(user(2))
    expectEmpty(store)
    await pending
  })

  it('同IDのprofile更新は取得済みfolderと進行中応答を維持する', async () => {
    const auth = useAuthStore()
    auth.user = user(1)
    const store = useScopeFoldersStore()
    seed(store)
    const response = deferred<unknown>()
    api.mockReturnValueOnce(response.promise)
    const pending = store.fetchAll('TEAM')

    await auth.setUser({ ...user(1), fullName: '更新済み' })
    expect(store.myTeamFolders[0]?.name).toBe('現在のフォルダ')
    response.resolve({ data: [folder(4, '更新された一覧')] })
    await pending
    expect(store.myTeamFolders[0]?.name).toBe('更新された一覧')
  })

  it('同じSPAのloadFromStorageでも別tabのユーザー切替に応じてfolderを同期破棄する', () => {
    const auth = useAuthStore()
    auth.user = user(1)
    const store = useScopeFoldersStore()
    seed(store)
    localStorage.setItem('currentUser', JSON.stringify(user(2)))

    auth.loadFromStorage()

    expect(auth.user?.id).toBe(2)
    expectEmpty(store)
  })

  it.each(['same-id', 'absent', 'invalid-json'] as const)('loadFromStorageの%sは既存folderを保持する', (saved) => {
    const auth = useAuthStore()
    auth.user = user(1)
    const store = useScopeFoldersStore()
    seed(store)
    const expected = JSON.parse(JSON.stringify(store.$state)) as typeof store.$state
    if (saved === 'same-id') localStorage.setItem('currentUser', JSON.stringify(user(1)))
    else if (saved === 'invalid-json') localStorage.setItem('currentUser', '{broken')

    auth.loadFromStorage()

    expect(auth.user?.id).toBe(1)
    expect(store.$state).toEqual(expected)
  })

  it('Aの取得済みfolder/summaryと後着応答はlogout→Bの空結果へ残らない', async () => {
    const auth = useAuthStore()
    await auth.setUser(user(1))
    const store = useScopeFoldersStore()
    api.mockResolvedValueOnce({ data: [folder(1, '旧ユーザー')] })
    api.mockResolvedValueOnce({ data: [{ folderId: 1, unreadCount: 8 }] })
    await store.fetchAll('TEAM')
    await store.refreshNotificationSummary('TEAM')
    const late = deferred<unknown>()
    api.mockReturnValueOnce(late.promise)
    const pending = store.refreshNotificationSummary('TEAM')

    await auth.logout()
    await auth.setUser(user(2))
    api.mockResolvedValueOnce({ data: [] })
    api.mockResolvedValueOnce({ data: [] })
    await store.fetchAll('TEAM')
    await store.refreshNotificationSummary('TEAM')
    late.resolve({ data: [{ folderId: 1, unreadCount: 8 }] })
    await pending

    expectEmpty(store)
  })

  const operations: { name: string, start: (store: Store) => Promise<unknown>, reply: unknown }[] = [
    { name: 'fetchAll', start: store => store.fetchAll('TEAM'), reply: { data: [folder(9, '旧応答')] } },
    { name: 'fetchDefault', start: store => store.fetchDefault('TEAM'), reply: { data: { ...folder(9), isDefault: true } } },
    { name: 'create', start: store => store.create('TEAM', { name: '作成' }), reply: { data: folder(9) } },
    { name: 'update', start: store => store.update('TEAM', 1, { name: '更新' }), reply: { data: folder(1, '旧応答') } },
    { name: 'delete', start: store => store.delete('TEAM', 1), reply: undefined },
    { name: 'addItem', start: store => store.addItem('TEAM', 2, 'scope-test'), reply: { data: folder(2, '旧応答') } },
    { name: 'removeItem', start: store => store.removeItem('TEAM', 1, 'scope-test'), reply: undefined },
    { name: 'bulkAssign', start: store => store.bulkAssign(1, ['scope-test'], 'TEAM'), reply: { data: { assignedCount: 1, skippedCount: 0, errors: [] } } },
    { name: 'refreshNotificationSummary', start: store => store.refreshNotificationSummary('TEAM'), reply: { data: [{ folderId: 1, unreadCount: 99 }] } },
  ]

  it.each(operations)('$nameはclear前の応答で新世代stateを変更しない', async ({ start, reply }) => {
    const store = useScopeFoldersStore()
    seed(store, '旧ユーザー')
    const response = deferred<unknown>()
    api.mockReturnValueOnce(response.promise)
    const pending = start(store)

    store.clear()
    seed(store)
    const expected = JSON.parse(JSON.stringify(store.$state)) as typeof store.$state
    response.resolve(reply)
    await pending

    expect(store.$state).toEqual(expected)
    expect(api).toHaveBeenCalledOnce()
  })

  it.each(['resolve', 'reject'] as const)('旧fetchAllの%s/finallyは新loadingを解除しない', async (outcome) => {
    const store = useScopeFoldersStore()
    const oldResponse = deferred<unknown>()
    const currentResponse = deferred<unknown>()
    api.mockReturnValueOnce(oldResponse.promise).mockReturnValueOnce(currentResponse.promise)
    const failure = new Error('合成通信失敗')
    const old = store.fetchAll('TEAM')
    const oldResult = old.catch(error => error as Error)
    store.clear()
    const current = store.fetchAll('ORGANIZATION')

    if (outcome === 'resolve') oldResponse.resolve({ data: [folder(9)] })
    else oldResponse.reject(failure)
    const settled = await oldResult
    if (outcome === 'reject') expect(settled).toBe(failure)
    expect(store.loading).toBe(true)
    expect(store.myTeamFolders).toEqual([])
    currentResponse.resolve({ data: [folder(3)] })
    await current
    expect(store.loading).toBe(false)
    expect(store.myOrgFolders).toEqual([folder(3)])
  })
})
