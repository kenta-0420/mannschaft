import { beforeEach, describe, expect, it, vi } from 'vitest'

const mockApi = vi.fn()

vi.mock('~/composables/useApi', () => ({
  useApi: () => mockApi,
}))

const { useJoinRequestApi } = await import('./useJoinRequestApi')

describe('useJoinRequestApi', () => {
  beforeEach(() => {
    mockApi.mockReset()
  })

  it('チームへの参加申請作成は teams パスへ POST する', async () => {
    await useJoinRequestApi().createJoinRequest('team', 12)

    expect(mockApi).toHaveBeenCalledWith('/api/v1/teams/12/join-requests', {
      method: 'POST',
      body: undefined,
    })
  })

  it('組織への参加申請作成は organizations パスへ message 付きで POST する', async () => {
    await useJoinRequestApi().createJoinRequest('organization', 7, '参加したいです')

    expect(mockApi).toHaveBeenCalledWith('/api/v1/organizations/7/join-requests', {
      method: 'POST',
      body: { message: '参加したいです' },
    })
  })

  it('自分の申請一覧は /me を叩く', async () => {
    await useJoinRequestApi().listMyJoinRequests('team', 12)

    expect(mockApi).toHaveBeenCalledWith('/api/v1/teams/12/join-requests/me')
  })

  it('審査一覧は status・page・size をクエリに載せる（既定 page=0, size=20）', async () => {
    await useJoinRequestApi().listJoinRequestsForReview('organization', 7)

    expect(mockApi).toHaveBeenCalledWith(
      '/api/v1/organizations/7/join-requests?page=0&size=20',
    )
  })

  it('審査一覧は status 指定時はクエリへ status を含める', async () => {
    await useJoinRequestApi().listJoinRequestsForReview('team', 12, { status: 'PENDING', page: 1, size: 10 })

    expect(mockApi).toHaveBeenCalledWith(
      '/api/v1/teams/12/join-requests?status=PENDING&page=1&size=10',
    )
  })

  it('承認は reviewComment 付きで POST する', async () => {
    await useJoinRequestApi().approveJoinRequest('team', 12, 'req-1', 'ようこそ')

    expect(mockApi).toHaveBeenCalledWith('/api/v1/teams/12/join-requests/req-1/approve', {
      method: 'POST',
      body: { reviewComment: 'ようこそ' },
    })
  })

  it('却下は reviewComment 省略時 body なしで POST する', async () => {
    await useJoinRequestApi().rejectJoinRequest('organization', 7, 'req-2')

    expect(mockApi).toHaveBeenCalledWith('/api/v1/organizations/7/join-requests/req-2/reject', {
      method: 'POST',
      body: undefined,
    })
  })
})
