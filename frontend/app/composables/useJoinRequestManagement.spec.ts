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
})
