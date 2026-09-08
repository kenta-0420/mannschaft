import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'

const mockApi = vi.fn()
const notificationSuccessMock = vi.fn()
const handleApiErrorMock = vi.fn()

vi.mock('~/composables/useApi', () => ({
  useApi: () => mockApi,
}))
vi.mock('~/composables/useNotification', () => ({
  useNotification: () => ({ success: notificationSuccessMock, error: vi.fn() }),
}))
vi.mock('~/composables/useErrorHandler', () => ({
  useErrorHandler: () => ({ handleApiError: handleApiErrorMock, getFieldErrors: () => ({}) }),
}))
vi.mock('#app', async (importOriginal) => {
  const actual = await importOriginal<Record<string, unknown>>()
  return { ...actual, useNuxtApp: () => ({ $i18n: { t: (key: string) => key } }) }
})

const { useJoinRequestManagement } = await import('./useJoinRequestManagement')

describe('useJoinRequestManagement', () => {
  beforeEach(() => {
    mockApi.mockReset()
    notificationSuccessMock.mockReset()
    handleApiErrorMock.mockReset()
  })

  it('init は PENDING 絞り込みで審査一覧を取得する', async () => {
    mockApi.mockResolvedValueOnce({
      data: { content: [{ id: 'req-1', status: 'PENDING', requesterUserId: 1, message: null, createdAt: '2026-09-01T00:00:00Z', scopeType: 'TEAM', scopeId: 12, reviewerUserId: null, reviewedAt: null, reviewComment: null }], totalElements: 1, totalPages: 1, number: 0, size: 20 },
    })

    const mgmt = useJoinRequestManagement(ref('team'), ref(12))
    await mgmt.init()

    expect(mockApi).toHaveBeenCalledWith('/api/v1/teams/12/join-requests?status=PENDING&page=0&size=20')
    expect(mgmt.requests.value).toHaveLength(1)
    expect(mgmt.requestsLoading.value).toBe(false)
  })

  it('approve は承認 API を呼び再取得する', async () => {
    mockApi.mockResolvedValueOnce({ data: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 } }) // init
    const mgmt = useJoinRequestManagement(ref('organization'), ref(7))
    await mgmt.init()

    mockApi.mockResolvedValueOnce({ data: { id: 'req-1' } }) // approve
    mockApi.mockResolvedValueOnce({ data: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 } }) // refetch
    await mgmt.approve('req-1')

    expect(mockApi).toHaveBeenCalledWith('/api/v1/organizations/7/join-requests/req-1/approve', {
      method: 'POST',
      body: undefined,
    })
    expect(notificationSuccessMock).toHaveBeenCalled()
  })

  it('reject 失敗時は handleApiError を呼ぶ', async () => {
    mockApi.mockResolvedValueOnce({ data: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 } }) // init
    const mgmt = useJoinRequestManagement(ref('team'), ref(12))
    await mgmt.init()

    mockApi.mockRejectedValueOnce(new Error('boom'))
    await mgmt.reject('req-2')

    expect(handleApiErrorMock).toHaveBeenCalled()
    expect(mgmt.processingIds.value).not.toContain('req-2')
  })

  // Codex 検分 CMP-260901-1538 第1巡 P1-2 是正: 21件以上のページング
  function makeRequest(id: string) {
    return { id, status: 'PENDING' as const, requesterUserId: 1, message: null, createdAt: '2026-09-01T00:00:00Z', scopeType: 'TEAM' as const, scopeId: 12, reviewerUserId: null, reviewedAt: null, reviewComment: null }
  }

  it('0件のとき totalElements=0・hasMore=false', async () => {
    mockApi.mockResolvedValueOnce({ data: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 } })
    const mgmt = useJoinRequestManagement(ref('team'), ref(12))
    await mgmt.init()

    expect(mgmt.requests.value).toHaveLength(0)
    expect(mgmt.totalElements.value).toBe(0)
    expect(mgmt.hasMore.value).toBe(false)
  })

  it('20件ちょうどのとき hasMore=false（次ページなし）', async () => {
    mockApi.mockResolvedValueOnce({
      data: { content: Array.from({ length: 20 }, (_, i) => makeRequest(`req-${i}`)), totalElements: 20, totalPages: 1, number: 0, size: 20 },
    })
    const mgmt = useJoinRequestManagement(ref('team'), ref(12))
    await mgmt.init()

    expect(mgmt.requests.value).toHaveLength(20)
    expect(mgmt.totalElements.value).toBe(20)
    expect(mgmt.hasMore.value).toBe(false)
  })

  it('21件（複数ページ）のとき hasMore=true になり、loadMore で全件取得できる', async () => {
    mockApi.mockResolvedValueOnce({
      data: { content: Array.from({ length: 20 }, (_, i) => makeRequest(`req-${i}`)), totalElements: 21, totalPages: 2, number: 0, size: 20 },
    })
    const mgmt = useJoinRequestManagement(ref('team'), ref(12))
    await mgmt.init()

    expect(mgmt.requests.value).toHaveLength(20)
    expect(mgmt.totalElements.value).toBe(21)
    expect(mgmt.hasMore.value).toBe(true)

    mockApi.mockResolvedValueOnce({
      data: { content: [makeRequest('req-20')], totalElements: 21, totalPages: 2, number: 1, size: 20 },
    })
    await mgmt.loadMore()

    expect(mockApi).toHaveBeenLastCalledWith('/api/v1/teams/12/join-requests?status=PENDING&page=1&size=20')
    expect(mgmt.requests.value).toHaveLength(21)
    expect(mgmt.hasMore.value).toBe(false)
  })

  it('バッジ用の totalElements は現在ページの件数ではなく全件数を使う', async () => {
    mockApi.mockResolvedValueOnce({
      data: { content: Array.from({ length: 20 }, (_, i) => makeRequest(`req-${i}`)), totalElements: 45, totalPages: 3, number: 0, size: 20 },
    })
    const mgmt = useJoinRequestManagement(ref('organization'), ref(7))
    await mgmt.init()

    expect(mgmt.requests.value).toHaveLength(20)
    expect(mgmt.totalElements.value).toBe(45)
  })

  // Codex 検分 CMP-260901-1538 第1巡 P1-3 是正: 取得失敗時は空状態と区別する
  it('取得失敗時は requestsError=true になり、空一覧と区別される', async () => {
    mockApi.mockRejectedValueOnce(new Error('network error'))
    const mgmt = useJoinRequestManagement(ref('team'), ref(12))
    await mgmt.init()

    expect(mgmt.requestsError.value).toBe(true)
    expect(handleApiErrorMock).toHaveBeenCalled()
  })

  it('取得成功後は requestsError=false に戻る', async () => {
    mockApi.mockResolvedValueOnce({ data: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 } })
    const mgmt = useJoinRequestManagement(ref('team'), ref(12))
    await mgmt.init()

    expect(mgmt.requestsError.value).toBe(false)
  })

  // Codex 検分 CMP-260901-1538 第2巡 P1-3 是正: 追加取得と審査後再取得の競合
  it('page1 追加取得中に承認→先頭ページ再取得が先に完了しても、後着した古い page1 応答は破棄される', async () => {
    mockApi.mockResolvedValueOnce({
      data: { content: Array.from({ length: 20 }, (_, i) => makeRequest(`req-${i}`)), totalElements: 21, totalPages: 2, number: 0, size: 20 },
    })
    const mgmt = useJoinRequestManagement(ref('team'), ref(12))
    await mgmt.init()

    // loadMore（page1）は未解決のまま保留する
    let resolvePage1: (value: unknown) => void = () => {}
    mockApi.mockReturnValueOnce(new Promise((resolve) => { resolvePage1 = resolve }))
    const loadMorePromise = mgmt.loadMore()

    // 承認 API・先頭ページ再取得は即時解決する
    mockApi.mockResolvedValueOnce({ data: { id: 'req-0' } })
    const freshHead = Array.from({ length: 19 }, (_, i) => makeRequest(`req-${i + 1}`))
    mockApi.mockResolvedValueOnce({ data: { content: freshHead, totalElements: 20, totalPages: 1, number: 0, size: 20 } })
    await mgmt.approve('req-0')

    expect(mgmt.requests.value).toHaveLength(19)
    expect(mgmt.totalElements.value).toBe(20)

    // 古い page1 応答が今ごろ後着する
    resolvePage1({ data: { content: [makeRequest('req-20')], totalElements: 21, totalPages: 2, number: 1, size: 20 } })
    await loadMorePromise

    // 破棄され、承認後の状態のまま（重複・古い行の復活・件数不整合が起きない）
    expect(mgmt.requests.value).toHaveLength(19)
    expect(mgmt.totalElements.value).toBe(20)
    expect(mgmt.requests.value.some(r => r.id === 'req-20')).toBe(false)
    expect(mgmt.requests.value.some(r => r.id === 'req-0')).toBe(false)
  })

  it('追加取得の応答に既存行と重複する ID が含まれていても二重に追加しない', async () => {
    mockApi.mockResolvedValueOnce({
      data: { content: [makeRequest('req-0')], totalElements: 2, totalPages: 2, number: 0, size: 20 },
    })
    const mgmt = useJoinRequestManagement(ref('team'), ref(12))
    await mgmt.init()

    mockApi.mockResolvedValueOnce({
      data: { content: [makeRequest('req-0'), makeRequest('req-1')], totalElements: 2, totalPages: 2, number: 1, size: 20 },
    })
    await mgmt.loadMore()

    const ids = mgmt.requests.value.map(r => r.id)
    expect(ids.filter(id => id === 'req-0')).toHaveLength(1)
    expect(ids).toContain('req-1')
  })

  it('古い世代（page1 追加取得）が後から失敗しても requestsError を立てない', async () => {
    mockApi.mockResolvedValueOnce({
      data: { content: Array.from({ length: 20 }, (_, i) => makeRequest(`req-${i}`)), totalElements: 21, totalPages: 2, number: 0, size: 20 },
    })
    const mgmt = useJoinRequestManagement(ref('team'), ref(12))
    await mgmt.init()

    // loadMore（page1）は未解決のまま保留する
    let rejectPage1: (reason: unknown) => void = () => {}
    mockApi.mockReturnValueOnce(new Promise((_resolve, reject) => { rejectPage1 = reject }))
    const loadMorePromise = mgmt.loadMore()

    // 承認により先頭ページ再取得が先に成功する（世代が進む）
    mockApi.mockResolvedValueOnce({ data: { id: 'req-0' } })
    mockApi.mockResolvedValueOnce({
      data: { content: Array.from({ length: 19 }, (_, i) => makeRequest(`req-${i + 1}`)), totalElements: 20, totalPages: 1, number: 0, size: 20 },
    })
    await mgmt.approve('req-0')
    expect(mgmt.requestsError.value).toBe(false)

    // 古い世代の page1 取得が後から失敗する
    rejectPage1(new Error('stale page1 failed'))
    // fetchRequests は例外を内部で捕捉して re-throw しないため、
    // このawaitが拒否されることはない。
    await loadMorePromise

    // 古い世代の失敗は現在の状態（requestsError=false）に影響しない
    expect(mgmt.requestsError.value).toBe(false)
    expect(mgmt.requests.value).toHaveLength(19)
  })
})
