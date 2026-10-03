import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { ref } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { FollowStatusApi } from './useFollowSelfStatus'

const handleApiErrorMock = vi.fn()
const notificationSuccessMock = vi.fn()
const notificationErrorMock = vi.fn()
const apiMock = vi.fn()

vi.mock('~/composables/useErrorHandler', () => ({
  useErrorHandler: () => ({ handleApiError: handleApiErrorMock, getFieldErrors: () => ({}) }),
}))
vi.mock('~/composables/useNotification', () => ({
  useNotification: () => ({ success: notificationSuccessMock, error: notificationErrorMock }),
}))
vi.mock('~/composables/useApi', () => ({ useApi: () => apiMock }))
vi.mock('#app', async (importOriginal) => {
  const actual = await importOriginal<Record<string, unknown>>()
  return { ...actual, useNuxtApp: () => ({ $i18n: { t: (key: string) => key } }) }
})

const { useScopeFollowWiring } = await import('./useScopeFollowWiring')
const { useRoleAccess } = await import('./useRoleAccess')

function deferred<T = unknown>() {
  let resolve: (value: T) => void = () => {}
  let reject: (reason?: unknown) => void = () => {}
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

function perms(roleName: string | null) {
  return { data: { roleName, permissions: roleName ? [`perm.${roleName}`] : [] } }
}

function makeFollowApi(overrides: Partial<FollowStatusApi> = {}): FollowStatusApi {
  return {
    follow: vi.fn().mockResolvedValue({}),
    unfollow: vi.fn().mockResolvedValue({}),
    getStatus: vi.fn().mockResolvedValue({ data: { status: 'APPROVED' } }),
    ...overrides,
  } as FollowStatusApi
}

/**
 * 実物の `useRoleAccess`（HTTP だけモック）と結線した状態で、シェルページが使う
 * フォロー結線ラッパーを検証する（CMP-261001-0835 検分修繕2）。
 */
function setup(followApi: FollowStatusApi) {
  const slug = ref('team-a')
  const roleAccess = useRoleAccess('team', slug)
  const loadPermissionsSpy = vi.fn(roleAccess.loadPermissions)
  const wiring = useScopeFollowWiring({
    scopeSlug: slug,
    api: followApi,
    roleAccess: { roleName: roleAccess.roleName, loadPermissions: loadPermissionsSpy },
  })
  return { slug, roleAccess, loadPermissionsSpy, wiring }
}

describe('useScopeFollowWiring（シェルページのフォロー結線）', () => {
  beforeEach(() => {
    handleApiErrorMock.mockReset()
    notificationSuccessMock.mockReset()
    notificationErrorMock.mockReset()
    apiMock.mockReset()
  })

  it('AC-5: roleName が SUPPORTER でもフォロー状態 GET が呼ばれ、APPROVED が反映される', async () => {
    apiMock.mockResolvedValue(perms('SUPPORTER'))
    const followApi = makeFollowApi()
    const { roleAccess, wiring } = setup(followApi)
    await roleAccess.loadPermissions()
    expect(roleAccess.roleName.value).toBe('SUPPORTER')

    await wiring.fetchFollowStatus()

    expect(followApi.getStatus).toHaveBeenCalledWith('team-a')
    expect(wiring.followStatus.value).toBe('APPROVED')
  })

  it('AC-7: 解除成功後に loadPermissions が呼ばれ、その結果 roleName が更新されダイアログが閉じる', async () => {
    apiMock.mockResolvedValueOnce(perms('SUPPORTER')).mockResolvedValueOnce(perms(null))
    const followApi = makeFollowApi()
    const { roleAccess, loadPermissionsSpy, wiring } = setup(followApi)
    await roleAccess.loadPermissions()
    await wiring.fetchFollowStatus()
    wiring.showCancelSupporterConfirm.value = true

    await wiring.cancelSupporter()

    expect(followApi.unfollow).toHaveBeenCalledWith('team-a')
    expect(loadPermissionsSpy).toHaveBeenCalledTimes(1)
    expect(roleAccess.roleName.value).toBeNull()
    expect(wiring.followStatus.value).toBe('NONE')
    expect(wiring.followPermissionSyncError.value).toBe(false)
    expect(wiring.showCancelSupporterConfirm.value).toBe(false)
  })

  it('AC-8: 解除 API が失敗したらダイアログを開いたままにし、権限再取得も呼ばない', async () => {
    const followApi = makeFollowApi({ unfollow: vi.fn().mockRejectedValue(new Error('boom')) })
    const { loadPermissionsSpy, wiring } = setup(followApi)
    await wiring.fetchFollowStatus()
    wiring.showCancelSupporterConfirm.value = true

    await wiring.cancelSupporter()

    expect(wiring.showCancelSupporterConfirm.value).toBe(true)
    expect(wiring.followStatus.value).toBe('APPROVED')
    expect(loadPermissionsSpy).not.toHaveBeenCalled()
  })

  it('AC-9: 解除後の権限同期が失敗したら同期失敗を立て、再試行は権限だけを取り直して解消する', async () => {
    apiMock
      .mockResolvedValueOnce(perms('SUPPORTER'))
      .mockRejectedValueOnce(new Error('sync failed'))
      .mockResolvedValueOnce(perms(null))
    const followApi = makeFollowApi()
    const { roleAccess, loadPermissionsSpy, wiring } = setup(followApi)
    await roleAccess.loadPermissions()
    await wiring.fetchFollowStatus()

    await wiring.cancelSupporter()
    expect(wiring.followPermissionSyncError.value).toBe(true)
    expect(notificationErrorMock).toHaveBeenCalledTimes(1)

    await wiring.retryFollowPermissionSync()

    expect(wiring.followPermissionSyncError.value).toBe(false)
    expect(loadPermissionsSpy).toHaveBeenCalledTimes(2)
    expect(followApi.unfollow).toHaveBeenCalledTimes(1)
    expect(followApi.getStatus).toHaveBeenCalledTimes(1)
    expect(roleAccess.roleName.value).toBeNull()
  })

  it('AC-6: retryFollowStatus はフォロー状態を取り直す', async () => {
    const followApi = makeFollowApi({
      getStatus: vi.fn()
        .mockRejectedValueOnce(new Error('boom'))
        .mockResolvedValueOnce({ data: { status: 'PENDING' } }),
    })
    const { wiring } = setup(followApi)
    await wiring.fetchFollowStatus()
    expect(wiring.followStatus.value).toBe('ERROR')

    await wiring.retryFollowStatus()

    expect(wiring.followStatus.value).toBe('PENDING')
  })

  it('検分修繕2: slug が変わった瞬間に同期的に状態を初期化し、A の遅延した解除応答を B に反映しない', async () => {
    const unfollow = deferred()
    const followApi = makeFollowApi({ unfollow: vi.fn().mockReturnValue(unfollow.promise) })
    apiMock.mockReturnValue(new Promise(() => {}))
    const { slug, loadPermissionsSpy, wiring } = setup(followApi)
    await wiring.fetchFollowStatus()
    wiring.showCancelSupporterConfirm.value = true

    const cancelling = wiring.cancelSupporter()
    slug.value = 'team-b'
    // ページの slug watch が詳細取得を await する前の時点で、既に初期化されている。
    expect(wiring.followStatus.value).toBe('UNKNOWN')
    expect(wiring.followLoading.value).toBe(false)
    expect(wiring.showCancelSupporterConfirm.value).toBe(false)

    unfollow.resolve({})
    await cancelling

    expect(wiring.followStatus.value).toBe('UNKNOWN')
    expect(loadPermissionsSpy).not.toHaveBeenCalled()
    expect(notificationSuccessMock).not.toHaveBeenCalled()
  })

  it('検分修繕2: 解除後の権限再取得の開始後に遷移しても、A の権限応答で B の roleName を上書きしない', async () => {
    const permsA = deferred<ReturnType<typeof perms>>()
    const permsB = deferred<ReturnType<typeof perms>>()
    apiMock.mockImplementation((url: string) =>
      url.includes('/team-a/') ? permsA.promise : permsB.promise)
    const followApi = makeFollowApi()
    const { slug, roleAccess, loadPermissionsSpy, wiring } = setup(followApi)
    await wiring.fetchFollowStatus()

    const cancelling = wiring.cancelSupporter()
    await vi.waitFor(() => expect(loadPermissionsSpy).toHaveBeenCalledTimes(1))
    slug.value = 'team-b'
    await vi.waitFor(() => expect(apiMock).toHaveBeenCalledWith('/api/v1/teams/team-b/me/permissions'))

    permsB.resolve(perms('MEMBER'))
    await vi.waitFor(() => expect(roleAccess.roleName.value).toBe('MEMBER'))
    permsA.reject(new Error('A failed late'))
    await cancelling

    expect(roleAccess.roleName.value).toBe('MEMBER')
    expect(wiring.followPermissionSyncError.value).toBe(false)
    expect(notificationErrorMock).not.toHaveBeenCalled()
  })

  it('検分修繕3: A の解除成功→権限再取得待機中に B へ遷移→B の解除確認を開いても、A の権限応答到着で B のダイアログを閉じない', async () => {
    const permsA = deferred<ReturnType<typeof perms>>()
    apiMock.mockImplementation((url: string) =>
      url.includes('/team-a/') ? permsA.promise : Promise.resolve(perms('MEMBER')))
    const followApi = makeFollowApi()
    const { slug, wiring } = setup(followApi)
    await wiring.fetchFollowStatus()

    // A の解除は成功し、権限再取得（A 向け）が未解決のまま B へ遷移する。
    const cancelling = wiring.cancelSupporter()
    await vi.waitFor(() => expect(followApi.unfollow).toHaveBeenCalledTimes(1))
    slug.value = 'team-b'
    // B で解除確認ダイアログを開く（A の権限応答とは無関係に B の UI 操作として開く）。
    wiring.showCancelSupporterConfirm.value = true

    // A の権限応答が遅れて到着しても、B のダイアログは閉じたままにしてはならない。
    permsA.resolve(perms(null))
    await cancelling

    expect(wiring.showCancelSupporterConfirm.value).toBe(true)
  })
})

/**
 * 両シェルページが本 composable を実物の loadPermissions と結線し、ヘッダ・確認ダイアログへ
 * そのラッパーを渡していることの最小限の固定（ラッパー内部の挙動は上の単体テストで検証）。
 */
describe.each([
  ['app/pages/organizations/[slug].vue', 'orgSlug', 'OrgPageHeader'],
  ['app/pages/teams/[slug].vue', 'teamSlug', 'TeamPageHeader'],
])('%s のフォロー結線', (file, slugName, header) => {
  const source = readFileSync(resolve(process.cwd(), file), 'utf8').replace(/\r\n/g, '\n')

  it('useScopeFollowWiring に slug と実物の roleName/loadPermissions を渡している', () => {
    expect(source).toMatch(new RegExp(`useScopeFollowWiring\\(\\{\\s*scopeSlug: ${slugName},`))
    expect(source).toContain('roleAccess: { roleName, loadPermissions },')
  })

  it('ヘッダと確認ダイアログが composable のラッパーを直接呼ぶ', () => {
    const headerBlock = source.match(new RegExp(`<${header}[\\s\\S]*?/>`))?.[0] ?? ''
    expect(headerBlock).toContain(':follow-status="followStatus"')
    expect(headerBlock).toContain(':follow-permission-sync-error="followPermissionSyncError"')
    expect(headerBlock).toContain('@cancel-supporter="cancelSupporter"')
    expect(headerBlock).toContain('@retry-follow-status="retryFollowStatus"')
    expect(headerBlock).toContain('@retry-follow-permission-sync="retryFollowPermissionSync"')
    expect(source).toMatch(/v-model:visible="showCancelSupporterConfirm"[\s\S]*?@click="cancelSupporter"/)
  })
})
