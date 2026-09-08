import { beforeEach, describe, expect, it, vi } from 'vitest'

const mockApi = vi.fn()
const handleApiErrorMock = vi.fn()
const notificationSuccessMock = vi.fn()

vi.mock('~/composables/useApi', () => ({
  useApi: () => mockApi,
}))
vi.mock('~/composables/useErrorHandler', () => ({
  useErrorHandler: () => ({ handleApiError: handleApiErrorMock, getFieldErrors: () => ({}) }),
}))
vi.mock('~/composables/useNotification', () => ({
  useNotification: () => ({ success: notificationSuccessMock, error: vi.fn() }),
}))
vi.mock('#app', async (importOriginal) => {
  const actual = await importOriginal<Record<string, unknown>>()
  return { ...actual, useNuxtApp: () => ({ $i18n: { t: (key: string) => key } }) }
})

const { useJoinRequestSelfStatus } = await import('./useJoinRequestSelfStatus')

function makeRequest(overrides: Partial<{ status: 'PENDING' | 'APPROVED' | 'REJECTED', createdAt: string, reviewedAt: string | null }> = {}) {
  return {
    id: 'req-1',
    scopeType: 'TEAM' as const,
    scopeId: 12,
    requesterUserId: 1,
    message: null,
    status: 'PENDING' as const,
    reviewerUserId: null,
    reviewedAt: null,
    createdAt: '2026-09-01T00:00:00Z',
    ...overrides,
  }
}

/**
 * `useJoinRequestSelfStatus` の fail-close 検証（Codex 検分 CMP-260901-1538 第1巡 P1-1 是正）。
 * 是正前は取得失敗を `NONE`（未申請）に潰しており、fail-open だった。
 */
describe('useJoinRequestSelfStatus', () => {
  beforeEach(() => {
    mockApi.mockReset()
    handleApiErrorMock.mockReset()
    notificationSuccessMock.mockReset()
  })

  it('初期値は UNKNOWN', () => {
    const { joinRequestStatus } = useJoinRequestSelfStatus('team')
    expect(joinRequestStatus.value).toBe('UNKNOWN')
  })

  it('取得中は LOADING を経由する', async () => {
    let resolveFn: (value: unknown) => void = () => {}
    mockApi.mockReturnValueOnce(new Promise((resolve) => { resolveFn = resolve }))
    const { joinRequestStatus, fetchJoinRequestStatus } = useJoinRequestSelfStatus('team')

    const promise = fetchJoinRequestStatus(12)
    expect(joinRequestStatus.value).toBe('LOADING')
    resolveFn({ data: [] })
    await promise
    expect(joinRequestStatus.value).toBe('NONE')
  })

  it('取得失敗時は NONE に潰さず ERROR にする（fail-close）', async () => {
    mockApi.mockRejectedValueOnce(new Error('boom'))
    const { joinRequestStatus, fetchJoinRequestStatus } = useJoinRequestSelfStatus('team')

    await fetchJoinRequestStatus(12)

    expect(joinRequestStatus.value).toBe('ERROR')
    expect(joinRequestStatus.value).not.toBe('NONE')
    expect(handleApiErrorMock).toHaveBeenCalled()
  })

  it('PENDING の申請が1件でもあれば PENDING を優先する', async () => {
    mockApi.mockResolvedValueOnce({ data: [makeRequest({ status: 'APPROVED' }), makeRequest({ status: 'PENDING' })] })
    const { joinRequestStatus, fetchJoinRequestStatus } = useJoinRequestSelfStatus('team')

    await fetchJoinRequestStatus(12)

    expect(joinRequestStatus.value).toBe('PENDING')
  })

  it('PENDING が無く直近の審査が APPROVED なら APPROVED', async () => {
    mockApi.mockResolvedValueOnce({
      data: [
        makeRequest({ status: 'REJECTED', createdAt: '2026-08-01T00:00:00Z', reviewedAt: '2026-08-02T00:00:00Z' }),
        makeRequest({ status: 'APPROVED', createdAt: '2026-09-01T00:00:00Z', reviewedAt: '2026-09-02T00:00:00Z' }),
      ],
    })
    const { joinRequestStatus, fetchJoinRequestStatus } = useJoinRequestSelfStatus('team')

    await fetchJoinRequestStatus(12)

    expect(joinRequestStatus.value).toBe('APPROVED')
  })

  it('直近の審査が REJECTED なら REJECTED', async () => {
    mockApi.mockResolvedValueOnce({ data: [makeRequest({ status: 'REJECTED', reviewedAt: '2026-09-01T00:00:00Z' })] })
    const { joinRequestStatus, fetchJoinRequestStatus } = useJoinRequestSelfStatus('team')

    await fetchJoinRequestStatus(12)

    expect(joinRequestStatus.value).toBe('REJECTED')
  })

  it('申請が1件も無ければ NONE', async () => {
    mockApi.mockResolvedValueOnce({ data: [] })
    const { joinRequestStatus, fetchJoinRequestStatus } = useJoinRequestSelfStatus('organization')

    await fetchJoinRequestStatus(7)

    expect(joinRequestStatus.value).toBe('NONE')
  })

  it('申請送信に成功すると PENDING になる', async () => {
    mockApi.mockResolvedValueOnce({ data: makeRequest() })
    const { joinRequestStatus, joinRequestLoading, applyJoinRequest } = useJoinRequestSelfStatus('team')

    const promise = applyJoinRequest(12)
    expect(joinRequestLoading.value).toBe(true)
    await promise

    expect(joinRequestStatus.value).toBe('PENDING')
    expect(joinRequestLoading.value).toBe(false)
  })

  it('申請送信に失敗しても PENDING にはならない', async () => {
    mockApi.mockRejectedValueOnce(new Error('boom'))
    const { joinRequestStatus, applyJoinRequest } = useJoinRequestSelfStatus('team')

    await applyJoinRequest(12)

    expect(joinRequestStatus.value).not.toBe('PENDING')
    expect(handleApiErrorMock).toHaveBeenCalled()
  })
})
