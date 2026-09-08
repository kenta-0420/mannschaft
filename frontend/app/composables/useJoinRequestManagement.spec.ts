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
})
