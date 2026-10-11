import { nextTick, ref } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const apiMock = vi.fn()
vi.mock('~/composables/useApi', () => ({ useApi: () => apiMock }))

const { useRoleAccess } = await import('./useRoleAccess')

function deferred<T = unknown>() {
  let resolve: (value: T) => void = () => {}
  let reject: (reason?: unknown) => void = () => {}
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

function perms(roleName: string) {
  return { data: { roleName, permissions: [`perm.${roleName}`] } }
}

/**
 * `useRoleAccess.loadPermissions` の「最後の要求が勝つ」検証（CMP-261001-0835 検分修繕2）。
 * 古い応答が後から返っても roleName / 権限を上書きしないこと、戻り値 `{ ok }` の意味
 * （取得成否）を保つことを確認する。
 */
describe('useRoleAccess.loadPermissions（最後の要求が勝つ）', () => {
  beforeEach(() => {
    apiMock.mockReset()
  })

  it('成功すると roleName / permissions が反映され ok: true を返す', async () => {
    apiMock.mockResolvedValue(perms('ADMIN'))
    const { roleName, permissions, loadPermissions } = useRoleAccess('team', 'team-a')

    await expect(loadPermissions()).resolves.toEqual({ ok: true })

    expect(roleName.value).toBe('ADMIN')
    expect(permissions.value).toEqual(['perm.ADMIN'])
    expect(apiMock).toHaveBeenCalledWith('/api/v1/teams/team-a/me/permissions')
  })

  it('失敗すると roleName=null・ok: false を返す（後方互換）', async () => {
    apiMock.mockRejectedValue(new Error('boom'))
    const { roleName, loadPermissions } = useRoleAccess('organization', 'org-a')

    const result = await loadPermissions()

    expect(result.ok).toBe(false)
    expect(roleName.value).toBeNull()
  })

  it('同一スコープで古い応答が新しい応答の後に返っても、新しい結果が残る', async () => {
    const first = deferred<ReturnType<typeof perms>>()
    const second = deferred<ReturnType<typeof perms>>()
    apiMock.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise)
    const { roleName, permissions, loading, loadPermissions } = useRoleAccess('team', 'team-a')

    const p1 = loadPermissions()
    const p2 = loadPermissions()
    second.resolve(perms('GUEST'))
    await p2
    expect(roleName.value).toBe('GUEST')

    first.resolve(perms('SUPPORTER'))
    await p1

    expect(roleName.value).toBe('GUEST')
    expect(permissions.value).toEqual(['perm.GUEST'])
    expect(loading.value).toBe(false)
  })

  it('古い要求の失敗が後から返っても新しい結果を消さず、古い要求の呼び出し元にも最新要求の成否を返す', async () => {
    const first = deferred<ReturnType<typeof perms>>()
    apiMock.mockReturnValueOnce(first.promise).mockResolvedValueOnce(perms('MEMBER'))
    const { roleName, loadPermissions } = useRoleAccess('team', 'team-a')

    const p1 = loadPermissions()
    await loadPermissions()
    first.reject(new Error('stale failure'))

    await expect(p1).resolves.toEqual({ ok: true })
    expect(roleName.value).toBe('MEMBER')
  })

  it('スコープ遷移後に旧スコープの応答が返っても roleName を上書きしない', async () => {
    const oldScope = deferred<ReturnType<typeof perms>>()
    const newScope = deferred<ReturnType<typeof perms>>()
    apiMock.mockImplementation((url: string) =>
      url.includes('/team-a/') ? oldScope.promise : newScope.promise)
    const slug = ref('team-a')
    const { roleName, loadPermissions } = useRoleAccess('team', slug)

    const pOld = loadPermissions()
    slug.value = 'team-b'
    await nextTick()
    // useRoleAccess は scopeId の watch で新スコープの取得を自動で始める。
    expect(apiMock).toHaveBeenCalledWith('/api/v1/teams/team-b/me/permissions')

    newScope.resolve(perms('MEMBER'))
    await vi.waitFor(() => expect(roleName.value).toBe('MEMBER'))

    oldScope.resolve(perms('ADMIN'))
    await pOld

    expect(roleName.value).toBe('MEMBER')
  })

  it('スコープ遷移後、新スコープの応答より先に旧スコープの応答が返っても反映せず、旧要求の呼び出し元には新スコープ要求の成否を返す', async () => {
    const oldScope = deferred<ReturnType<typeof perms>>()
    const newScope = deferred<ReturnType<typeof perms>>()
    apiMock.mockImplementation((url: string) =>
      url.includes('/team-a/') ? oldScope.promise : newScope.promise)
    const slug = ref('team-a')
    const { roleName, loadPermissions } = useRoleAccess('team', slug)

    const pOld = loadPermissions()
    slug.value = 'team-b'
    oldScope.resolve(perms('ADMIN'))
    // 旧要求の継続処理（マイクロタスク）をすべて流し切る。
    await new Promise(resolve => setTimeout(resolve, 0))

    expect(roleName.value).toBeNull()

    newScope.reject(new Error('new scope failed'))
    const result = await pOld

    expect(result.ok).toBe(false)
    expect(roleName.value).toBeNull()
  })
})
