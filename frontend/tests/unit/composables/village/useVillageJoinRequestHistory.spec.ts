import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { effectScope, nextTick, reactive } from 'vue'
import type { PagedResponse } from '~/types/api'
import type { MyJoinRequestResponse } from '~/types/village'

const mocks = vi.hoisted(() => ({ api: vi.fn(), captureQuiet: vi.fn() }))
const auth = reactive<{ user: { id: number } | null }>({ user: { id: 1 } })
vi.mock('~/composables/useApi', () => ({ useApi: () => mocks.api }))
vi.mock('~/composables/useErrorReport', () => ({ useErrorReport: () => mocks }))
vi.mock('~/stores/useAuthStore', () => ({ useAuthStore: () => auth }))

// eslint-disable-next-line import/first
import { useVillageJoinRequestHistory } from '~/composables/village/useVillageJoinRequestHistory'

function deferred() {
  let resolve!: (value: PagedResponse<MyJoinRequestResponse>) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<PagedResponse<MyJoinRequestResponse>>((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}

function response(id: string): PagedResponse<MyJoinRequestResponse> {
  return {
    data: [
      {
        id,
        villageId: 'village-id',
        villageName: '申請先の村',
        villageState: 'ACTIVE',
        subjectType: 'USER',
        subjectId: 1,
        message: '本人の申請',
        status: 'PENDING',
        reviewedBy: null,
        reviewedAt: null,
        reviewComment: null,
        createdAt: '2026-08-26T12:00:00',
      },
    ],
    meta: { total: 1, page: 0, size: 20, totalPages: 1 },
  }
}

describe('本人申請履歴の利用者切替', () => {
  let scope: ReturnType<typeof effectScope>
  beforeEach(() => {
    mocks.api.mockReset()
    mocks.captureQuiet.mockReset()
    auth.user = { id: 1 }
    scope = effectScope()
  })
  afterEach(() => scope.stop())

  it('利用者切替で旧行と件数を同期消去し、遅い旧応答を破棄する', async () => {
    const initial = deferred()
    const old = deferred()
    const current = deferred()
    mocks.api
      .mockReturnValueOnce(initial.promise)
      .mockReturnValueOnce(old.promise)
      .mockReturnValueOnce(current.promise)
    const state = scope.run(() => useVillageJoinRequestHistory())!
    initial.resolve(response('actor-1'))
    await nextTick()
    expect(state.requests.value[0]?.id).toBe('actor-1')
    void state.load()
    auth.user = { id: 2 }
    expect(state.requests.value).toEqual([])
    expect(state.meta.value.total).toBe(0)
    await nextTick()
    current.resolve(response('actor-2'))
    await nextTick()
    old.resolve(response('actor-1-late'))
    await nextTick()
    expect(state.requests.value[0]?.id).toBe('actor-2')
    expect(mocks.api).toHaveBeenLastCalledWith('/api/v1/village-join-requests/me?page=0&size=20')
  })

  it('同じtickのlogoutと同じIDのloginでも旧応答を捨てて再取得する', async () => {
    const old = deferred()
    const current = deferred()
    mocks.api.mockReturnValueOnce(old.promise).mockReturnValueOnce(current.promise)
    const state = scope.run(() => useVillageJoinRequestHistory())!
    auth.user = null
    auth.user = { id: 1 }
    await nextTick()
    expect(mocks.api).toHaveBeenCalledTimes(2)
    current.resolve(response('new-session'))
    await nextTick()
    old.resolve(response('old-session'))
    await nextTick()
    expect(state.requests.value[0]?.id).toBe('new-session')
  })

  it('切替前の失敗を現在の利用者へ表示・通知しない', async () => {
    const old = deferred()
    const current = deferred()
    mocks.api.mockReturnValueOnce(old.promise).mockReturnValueOnce(current.promise)
    const state = scope.run(() => useVillageJoinRequestHistory())!
    auth.user = { id: 2 }
    await nextTick()
    old.reject(new Error('旧利用者の失敗'))
    await nextTick()
    expect(state.error.value).toBeNull()
    expect(mocks.captureQuiet).not.toHaveBeenCalled()
    current.resolve(response('actor-2'))
    await nextTick()
    expect(state.loading.value).toBe(false)
  })

  it('頁が前後して完了しても最後に要求した頁だけを反映し、終了後応答も破棄する', async () => {
    const first = deferred()
    const second = deferred()
    mocks.api.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise)
    const state = scope.run(() => useVillageJoinRequestHistory())!
    state.page.value = 1
    await nextTick()
    second.resolve({ ...response('page-1'), meta: { total: 40, page: 1, size: 20, totalPages: 2 } })
    await nextTick()
    first.resolve(response('page-0-late'))
    await nextTick()
    expect(state.requests.value[0]?.id).toBe('page-1')
    expect(state.meta.value.page).toBe(1)
    const afterStop = deferred()
    mocks.api.mockReturnValueOnce(afterStop.promise)
    void state.load()
    scope.stop()
    afterStop.resolve(response('after-stop'))
    await nextTick()
    expect(state.requests.value[0]?.id).toBe('page-1')
  })
})
