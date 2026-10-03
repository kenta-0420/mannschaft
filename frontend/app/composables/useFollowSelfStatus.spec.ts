import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { FollowStatusApi } from './useFollowSelfStatus'

const handleApiErrorMock = vi.fn()
const notificationSuccessMock = vi.fn()
const notificationErrorMock = vi.fn()

vi.mock('~/composables/useErrorHandler', () => ({
  useErrorHandler: () => ({ handleApiError: handleApiErrorMock, getFieldErrors: () => ({}) }),
}))
vi.mock('~/composables/useNotification', () => ({
  useNotification: () => ({ success: notificationSuccessMock, error: notificationErrorMock }),
}))
vi.mock('#app', async (importOriginal) => {
  const actual = await importOriginal<Record<string, unknown>>()
  return { ...actual, useNuxtApp: () => ({ $i18n: { t: (key: string) => key } }) }
})

const { useFollowSelfStatus } = await import('./useFollowSelfStatus')

function makeApi(overrides: Partial<FollowStatusApi> = {}): FollowStatusApi {
  return {
    follow: vi.fn().mockResolvedValue({}),
    unfollow: vi.fn().mockResolvedValue({}),
    getStatus: vi.fn().mockResolvedValue({ data: { status: 'NONE' } }),
    ...overrides,
  } as FollowStatusApi
}

/**
 * `useFollowSelfStatus` の fail-close 検証（CMP-261001-0835）。
 *
 * 是正前の旧実装（useOrgDetail.ts / pages/teams/[slug].vue）は
 * - `if (roleName.value) return` で SUPPORTER ロール自身の状態取得をスキップしていた
 *   （AC-1/AC-5）
 * - 取得失敗を `NONE` に潰していた（AC-6）
 * という2つの fail-open を抱えていた。本 composable はそれを `useJoinRequestSelfStatus`
 * と同型の fail-close パターンで是正する。
 */
describe('useFollowSelfStatus', () => {
  beforeEach(() => {
    handleApiErrorMock.mockReset()
    notificationSuccessMock.mockReset()
    notificationErrorMock.mockReset()
  })

  it('初期値は UNKNOWN', () => {
    const { followStatus } = useFollowSelfStatus(makeApi())
    expect(followStatus.value).toBe('UNKNOWN')
  })

  // AC-5: ロールの有無に関係なく常に呼ばれる（呼び出し元は roleName でガードしない）。
  it('取得中は LOADING を経由し、成功すると BE の状態になる', async () => {
    let resolveFn: (value: unknown) => void = () => {}
    const api = makeApi({
      getStatus: vi.fn().mockReturnValue(new Promise((resolve) => { resolveFn = resolve })),
    })
    const { followStatus, fetchFollowStatus } = useFollowSelfStatus(api)

    const promise = fetchFollowStatus('org-a')
    expect(followStatus.value).toBe('LOADING')
    resolveFn({ data: { status: 'APPROVED' } })
    await promise

    expect(followStatus.value).toBe('APPROVED')
  })

  // AC-6: 取得失敗時は既知の NONE に潰さず ERROR にする（fail-close）。
  it('取得失敗時は NONE に潰さず ERROR にする', async () => {
    const api = makeApi({ getStatus: vi.fn().mockRejectedValue(new Error('boom')) })
    const { followStatus, fetchFollowStatus } = useFollowSelfStatus(api)

    await fetchFollowStatus('org-a')

    expect(followStatus.value).toBe('ERROR')
    expect(followStatus.value).not.toBe('NONE')
    expect(handleApiErrorMock).toHaveBeenCalled()
  })

  // AC-6: 既知の APPROVED/PENDING を失敗で NONE に変えない（同一スコープの再取得失敗）。
  it('既知の APPROVED を再取得失敗で NONE に変えない', async () => {
    const api = makeApi({
      getStatus: vi.fn()
        .mockResolvedValueOnce({ data: { status: 'APPROVED' } })
        .mockRejectedValueOnce(new Error('boom')),
    })
    const { followStatus, fetchFollowStatus } = useFollowSelfStatus(api)

    await fetchFollowStatus('org-a')
    expect(followStatus.value).toBe('APPROVED')

    await fetchFollowStatus('org-a')
    expect(followStatus.value).toBe('ERROR')
    expect(followStatus.value).not.toBe('NONE')
  })

  it('旧スコープの遅い応答が新スコープの状態を上書きしない', async () => {
    let resolveOld: (value: unknown) => void = () => {}
    const api = makeApi({
      getStatus: vi.fn()
        .mockReturnValueOnce(new Promise((resolve) => { resolveOld = resolve }))
        .mockResolvedValueOnce({ data: { status: 'NONE' } }),
    })
    const { followStatus, fetchFollowStatus } = useFollowSelfStatus(api)

    const oldPromise = fetchFollowStatus('org-a')
    await fetchFollowStatus('org-b')
    expect(followStatus.value).toBe('NONE')

    resolveOld({ data: { status: 'APPROVED' } })
    await oldPromise

    expect(followStatus.value).toBe('NONE')
  })

  it('サポーター申請に成功すると BE の状態が反映され、成功通知が出る', async () => {
    const api = makeApi({ getStatus: vi.fn().mockResolvedValue({ data: { status: 'APPROVED' } }) })
    const { followStatus, applySupporter } = useFollowSelfStatus(api)

    await applySupporter('org-a')

    expect(followStatus.value).toBe('APPROVED')
    expect(api.follow).toHaveBeenCalledWith('org-a')
    expect(notificationSuccessMock).toHaveBeenCalled()
  })

  it('サポーター申請に失敗してもエラー通知のみで状態は変わらない', async () => {
    const api = makeApi({ follow: vi.fn().mockRejectedValue(new Error('boom')) })
    const { followStatus, applySupporter } = useFollowSelfStatus(api)

    await applySupporter('org-a')

    expect(followStatus.value).toBe('UNKNOWN')
    expect(handleApiErrorMock).toHaveBeenCalled()
  })

  // AC-7: 解除成功後は followStatus=NONE にしてから reloadPermissions を呼ぶ。
  it('AC-7: 解除成功時は followStatus=NONE になり、権限再取得コールバックが呼ばれる', async () => {
    const api = makeApi()
    const reloadPermissions = vi.fn().mockResolvedValue({ ok: true })
    const { followStatus, followPermissionSyncError, cancelSupporter } = useFollowSelfStatus(api)

    await cancelSupporter('org-a', reloadPermissions)

    expect(api.unfollow).toHaveBeenCalledWith('org-a')
    expect(followStatus.value).toBe('NONE')
    expect(followPermissionSyncError.value).toBe(false)
    expect(reloadPermissions).toHaveBeenCalledTimes(1)
    expect(notificationSuccessMock).toHaveBeenCalled()
  })

  // AC-8: 解除 API 自体の失敗はエラー通知のみで、followStatus は変更しない。
  it('AC-8: 解除 API 失敗時はエラー通知のみで followStatus を変えない', async () => {
    const api = makeApi({
      getStatus: vi.fn().mockResolvedValue({ data: { status: 'APPROVED' } }),
      unfollow: vi.fn().mockRejectedValue(new Error('boom')),
    })
    const reloadPermissions = vi.fn().mockResolvedValue({ ok: true })
    const { followStatus, fetchFollowStatus, cancelSupporter } = useFollowSelfStatus(api)
    await fetchFollowStatus('org-a')
    expect(followStatus.value).toBe('APPROVED')

    await cancelSupporter('org-a', reloadPermissions)

    expect(followStatus.value).toBe('APPROVED')
    expect(handleApiErrorMock).toHaveBeenCalled()
    expect(reloadPermissions).not.toHaveBeenCalled()
  })

  // AC-9: 解除成功・権限再取得失敗時は、解除成功の通知とは別に同期失敗を通知し、
  // followPermissionSyncError を立てる（未所属確定として扱わない）。
  it('AC-9: 解除成功・権限再取得失敗時は同期失敗フラグを立て、別通知を出す', async () => {
    const api = makeApi()
    const reloadPermissions = vi.fn().mockResolvedValue({ ok: false, error: new Error('reload failed') })
    const { followStatus, followPermissionSyncError, cancelSupporter } = useFollowSelfStatus(api)

    await cancelSupporter('org-a', reloadPermissions)

    expect(followStatus.value).toBe('NONE')
    expect(followPermissionSyncError.value).toBe(true)
    expect(notificationSuccessMock).toHaveBeenCalled() // 解除成功通知
    expect(notificationErrorMock).toHaveBeenCalled() // 同期失敗通知（別通知）
  })

  // AC-9: 権限再取得のみの再試行導線。
  it('AC-9: retryFollowPermissionSync が成功すればフラグが下りる', async () => {
    const api = makeApi()
    const failOnce = vi.fn().mockResolvedValue({ ok: false, error: new Error('x') })
    const { followPermissionSyncError, cancelSupporter, retryFollowPermissionSync } = useFollowSelfStatus(api)
    await cancelSupporter('org-a', failOnce)
    expect(followPermissionSyncError.value).toBe(true)

    const succeed = vi.fn().mockResolvedValue({ ok: true })
    await retryFollowPermissionSync(succeed)

    expect(followPermissionSyncError.value).toBe(false)
  })

  it('AC-9: retryFollowPermissionSync が失敗すればフラグが立ったまま再度通知する', async () => {
    const api = makeApi()
    const failOnce = vi.fn().mockResolvedValue({ ok: false, error: new Error('x') })
    const { followPermissionSyncError, cancelSupporter, retryFollowPermissionSync } = useFollowSelfStatus(api)
    await cancelSupporter('org-a', failOnce)
    notificationErrorMock.mockReset()

    await retryFollowPermissionSync(failOnce)

    expect(followPermissionSyncError.value).toBe(true)
    expect(notificationErrorMock).toHaveBeenCalled()
  })

  // 検分修繕: 永続シェルでのスコープ遷移競合。申請（follow）実行中に別スコープへ遷移すると、
  // 遅延応答が新スコープの表示を上書きしてはならない。
  it('検分修繕: applySupporter 実行中にスコープが遷移すると、遅延応答で新スコープの状態を上書きしない', async () => {
    let resolveFollow: (value: unknown) => void = () => {}
    const api = makeApi({
      follow: vi.fn().mockReturnValue(new Promise((resolve) => { resolveFollow = resolve })),
      getStatus: vi.fn().mockResolvedValue({ data: { status: 'NONE' } }),
    })
    const { followStatus, applySupporter, fetchFollowStatus } = useFollowSelfStatus(api)

    const applyPromise = applySupporter('org-a')
    // 申請中に別スコープへ遷移（永続シェルで起こり得る）。
    await fetchFollowStatus('org-b')
    expect(followStatus.value).toBe('NONE')

    resolveFollow({})
    await applyPromise

    // org-a 側の遅延応答が org-b の表示を上書きしていない。
    expect(followStatus.value).toBe('NONE')
  })

  // 検分修繕: 解除（cancelSupporter）実行中にスコープが遷移した場合も同様に上書きしない。
  it('検分修繕: cancelSupporter 実行中にスコープが遷移すると、遅延応答で新スコープの状態・同期フラグを上書きしない', async () => {
    let resolveUnfollow: (value: unknown) => void = () => {}
    const api = makeApi({
      unfollow: vi.fn().mockReturnValue(new Promise((resolve) => { resolveUnfollow = resolve })),
    })
    const reloadPermissions = vi.fn().mockResolvedValue({ ok: false, error: new Error('x') })
    const { followStatus, followPermissionSyncError, cancelSupporter, fetchFollowStatus } = useFollowSelfStatus(api)

    const cancelPromise = cancelSupporter('org-a', reloadPermissions)
    await fetchFollowStatus('org-b')
    expect(followStatus.value).toBe('NONE')
    expect(followPermissionSyncError.value).toBe(false)

    resolveUnfollow({})
    await cancelPromise

    // org-a の解除が org-b の表示・同期失敗フラグへ波及していない。
    expect(followStatus.value).toBe('NONE')
    expect(followPermissionSyncError.value).toBe(false)
    expect(reloadPermissions).not.toHaveBeenCalled()
  })

  // 検分修繕: スコープ遷移時に旧スコープの followPermissionSyncError を引き継がない。
  it('検分修繕: スコープ変更時に followPermissionSyncError が初期化される', async () => {
    const api = makeApi()
    const failReload = vi.fn().mockResolvedValue({ ok: false, error: new Error('x') })
    const { followPermissionSyncError, cancelSupporter, fetchFollowStatus } = useFollowSelfStatus(api)

    await cancelSupporter('org-a', failReload)
    expect(followPermissionSyncError.value).toBe(true)

    await fetchFollowStatus('org-b')
    expect(followPermissionSyncError.value).toBe(false)
  })

  // ---------------------------------------------------------------------------
  // 検分修繕2: スコープ世代（scopeGen）と GET 要求番号（getSeq）の分離
  // ---------------------------------------------------------------------------

  function deferred<T = unknown>() {
    let resolve: (value: T) => void = () => {}
    let reject: (reason?: unknown) => void = () => {}
    const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej })
    return { promise, resolve, reject }
  }

  it('検分修繕2: 申請中に遷移すると、A の申請後状態（APPROVED）で B の状態（NONE）を上書きせず通知も出さない', async () => {
    const followA = deferred()
    const api = makeApi({
      follow: vi.fn().mockReturnValue(followA.promise),
      getStatus: vi.fn().mockImplementation(async (scopeId: string) =>
        ({ data: { status: scopeId === 'org-a' ? 'APPROVED' : 'NONE' } })),
    })
    const { followStatus, followLoading, applySupporter, resetForScope, fetchFollowStatus } = useFollowSelfStatus(api)

    const applyPromise = applySupporter('org-a')
    resetForScope('org-b')
    expect(followStatus.value).toBe('UNKNOWN')
    expect(followLoading.value).toBe(false)
    await fetchFollowStatus('org-b')
    expect(followStatus.value).toBe('NONE')

    followA.resolve({})
    await applyPromise

    expect(followStatus.value).toBe('NONE')
    expect(notificationSuccessMock).not.toHaveBeenCalled()
  })

  it('検分修繕2: 申請成功後の状態再取得中に遷移すると、A の再取得結果（APPROVED）で B の状態（NONE）を上書きせず通知も出さない', async () => {
    const getA = deferred<{ data: { status: 'APPROVED' } }>()
    const api = makeApi({
      getStatus: vi.fn().mockImplementation((scopeId: string) =>
        scopeId === 'org-a' ? getA.promise : Promise.resolve({ data: { status: 'NONE' } })),
    })
    const { followStatus, applySupporter, resetForScope, fetchFollowStatus } = useFollowSelfStatus(api)

    const applyPromise = applySupporter('org-a')
    await vi.waitFor(() => expect(api.getStatus).toHaveBeenCalledWith('org-a'))
    resetForScope('org-b')
    await fetchFollowStatus('org-b')
    getA.resolve({ data: { status: 'APPROVED' } })
    await applyPromise

    expect(followStatus.value).toBe('NONE')
    expect(notificationSuccessMock).not.toHaveBeenCalled()
  })

  it('検分修繕2: 同一スコープで解除中に始まった再取得が解除前の APPROVED を先に返しても、解除成功で NONE・通知・権限再取得まで完了する', async () => {
    const unfollow = deferred()
    const staleGet = deferred<{ data: { status: 'APPROVED' } }>()
    const api = makeApi({
      unfollow: vi.fn().mockReturnValue(unfollow.promise),
      getStatus: vi.fn()
        .mockResolvedValueOnce({ data: { status: 'APPROVED' } })
        .mockReturnValueOnce(staleGet.promise),
    })
    const reloadPermissions = vi.fn().mockResolvedValue({ ok: true })
    const { followStatus, cancelSupporter, fetchFollowStatus } = useFollowSelfStatus(api)
    await fetchFollowStatus('org-a')

    const cancelPromise = cancelSupporter('org-a', reloadPermissions)
    const refetch = fetchFollowStatus('org-a')
    staleGet.resolve({ data: { status: 'APPROVED' } })
    await refetch
    expect(followStatus.value).toBe('APPROVED')

    unfollow.resolve({})
    await expect(cancelPromise).resolves.toBe(true)

    expect(followStatus.value).toBe('NONE')
    expect(notificationSuccessMock).toHaveBeenCalledTimes(1)
    expect(reloadPermissions).toHaveBeenCalledTimes(1)
  })

  it('検分修繕2: 解除前に飛ばした GET（APPROVED）が解除成功の後に返っても NONE を巻き戻さない', async () => {
    const staleGet = deferred<{ data: { status: 'APPROVED' } }>()
    const api = makeApi({ getStatus: vi.fn().mockReturnValue(staleGet.promise) })
    const reloadPermissions = vi.fn().mockResolvedValue({ ok: true })
    const { followStatus, cancelSupporter, fetchFollowStatus } = useFollowSelfStatus(api)

    const fetching = fetchFollowStatus('org-a')
    await cancelSupporter('org-a', reloadPermissions)
    expect(followStatus.value).toBe('NONE')

    staleGet.resolve({ data: { status: 'APPROVED' } })
    await fetching

    expect(followStatus.value).toBe('NONE')
  })

  it('検分修繕2: 権限再試行中に同一スコープの再取得が走っても、再試行の成功は捨てられない', async () => {
    const api = makeApi({ getStatus: vi.fn().mockResolvedValue({ data: { status: 'NONE' } }) })
    const { followPermissionSyncError, cancelSupporter, retryFollowPermissionSync, fetchFollowStatus }
      = useFollowSelfStatus(api)
    await cancelSupporter('org-a', vi.fn().mockResolvedValue({ ok: false, error: new Error('x') }))
    expect(followPermissionSyncError.value).toBe(true)

    const reload = deferred<{ ok: true }>()
    const retrying = retryFollowPermissionSync(() => reload.promise)
    await fetchFollowStatus('org-a')
    reload.resolve({ ok: true })
    await retrying

    expect(followPermissionSyncError.value).toBe(false)
  })

  it('検分修繕2: resetForScope 直後に A の解除応答が来ても B には何も反映せず、通知も権限再取得も行わない', async () => {
    const unfollow = deferred()
    const api = makeApi({ unfollow: vi.fn().mockReturnValue(unfollow.promise) })
    const reloadPermissions = vi.fn().mockResolvedValue({ ok: false, error: new Error('x') })
    const { followStatus, followLoading, followPermissionSyncError, cancelSupporter, resetForScope }
      = useFollowSelfStatus(api)

    const cancelPromise = cancelSupporter('org-a', reloadPermissions)
    resetForScope('org-b')
    unfollow.resolve({})
    await expect(cancelPromise).resolves.toBe(false)

    expect(followStatus.value).toBe('UNKNOWN')
    expect(followLoading.value).toBe(false)
    expect(followPermissionSyncError.value).toBe(false)
    expect(reloadPermissions).not.toHaveBeenCalled()
    expect(notificationSuccessMock).not.toHaveBeenCalled()
    expect(notificationErrorMock).not.toHaveBeenCalled()
  })

  it('検分修繕2: 権限再取得の開始後に遷移すると、A の権限再取得失敗で B に同期失敗を立てない', async () => {
    const reload = deferred<{ ok: false, error: unknown }>()
    const api = makeApi()
    const { followPermissionSyncError, cancelSupporter, resetForScope } = useFollowSelfStatus(api)

    const reloadFn = vi.fn().mockReturnValue(reload.promise)
    const cancelPromise = cancelSupporter('org-a', reloadFn)
    await vi.waitFor(() => expect(reloadFn).toHaveBeenCalledTimes(1))
    resetForScope('org-b')
    reload.resolve({ ok: false, error: new Error('x') })
    await cancelPromise

    expect(followPermissionSyncError.value).toBe(false)
    expect(notificationErrorMock).not.toHaveBeenCalled()
  })
})
