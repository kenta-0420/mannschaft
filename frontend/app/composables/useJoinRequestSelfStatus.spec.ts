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

  it('申請送信に成功すると PENDING になる（対象スコープが表示中の場合）', async () => {
    mockApi.mockResolvedValueOnce({ data: [] }) // 事前の fetchJoinRequestStatus(12) で表示中スコープを確定
    mockApi.mockResolvedValueOnce({ data: makeRequest() })
    const { joinRequestStatus, joinRequestLoading, fetchJoinRequestStatus, applyJoinRequest } = useJoinRequestSelfStatus('team')

    await fetchJoinRequestStatus(12)
    const promise = applyJoinRequest(12)
    expect(joinRequestLoading.value).toBe(true)
    await promise

    expect(joinRequestStatus.value).toBe('PENDING')
    expect(joinRequestLoading.value).toBe(false)
  })

  it('申請送信に失敗しても PENDING にはならない', async () => {
    mockApi.mockResolvedValueOnce({ data: [] }) // 事前の fetchJoinRequestStatus(12)
    mockApi.mockRejectedValueOnce(new Error('boom'))
    const { joinRequestStatus, fetchJoinRequestStatus, applyJoinRequest } = useJoinRequestSelfStatus('team')

    await fetchJoinRequestStatus(12)
    await applyJoinRequest(12)

    expect(joinRequestStatus.value).not.toBe('PENDING')
    expect(handleApiErrorMock).toHaveBeenCalled()
  })

  // Codex 検分第3巡 P1 是正: 申請成功時、送信先スコープが表示中スコープと異なるなら状態を書き換えない
  it('スコープA申請中にスコープBへ遷移→A成功が後着しても、Bの状態を上書きしない', async () => {
    // A(7) の状態取得（表示中スコープを確定）
    mockApi.mockResolvedValueOnce({ data: [] })
    const { joinRequestStatus, fetchJoinRequestStatus, applyJoinRequest } = useJoinRequestSelfStatus('team')
    await fetchJoinRequestStatus(7)
    expect(joinRequestStatus.value).toBe('NONE')

    // Aへ申請を送信するが、応答は保留する
    let resolveApplyA: (value: unknown) => void = () => {}
    mockApi.mockReturnValueOnce(new Promise((resolve) => { resolveApplyA = resolve }))
    const applyAPromise = applyJoinRequest(7)

    // その間に B(12) へ遷移し、Bの状態取得が完了する
    mockApi.mockResolvedValueOnce({ data: [] })
    await fetchJoinRequestStatus(12)
    expect(joinRequestStatus.value).toBe('NONE')

    // Aの申請が今ごろ成功する
    resolveApplyA(makeRequest())
    await applyAPromise

    // Bの状態（NONE）を PENDING で上書きしてはならない
    expect(joinRequestStatus.value).toBe('NONE')
  })

  it('スコープA申請中にスコープBへ遷移→A成功が後着しても、Bの後続取得（世代検証）は生きたまま', async () => {
    mockApi.mockResolvedValueOnce({ data: [] }) // A(7) 初回取得
    const { joinRequestStatus, fetchJoinRequestStatus, applyJoinRequest } = useJoinRequestSelfStatus('team')
    await fetchJoinRequestStatus(7)

    let resolveApplyA: (value: unknown) => void = () => {}
    mockApi.mockReturnValueOnce(new Promise((resolve) => { resolveApplyA = resolve }))
    const applyAPromise = applyJoinRequest(7)

    // B(12) へ遷移し、B の取得が PENDING で解決する
    mockApi.mockResolvedValueOnce({ data: [makeRequest({ status: 'PENDING' })] })
    await fetchJoinRequestStatus(12)
    expect(joinRequestStatus.value).toBe('PENDING')

    // Aの申請が今ごろ成功しても、Bの正当な取得結果（PENDING）は変わらない
    resolveApplyA(makeRequest())
    await applyAPromise

    expect(joinRequestStatus.value).toBe('PENDING')
  })

  // Codex 検分第2巡 P1-2 是正: 旧スコープの遅い応答が新スコープの状態を上書きしない
  it('旧スコープ（成功）の遅い応答が、新スコープの状態を上書きしない', async () => {
    let resolveOld: (value: unknown) => void = () => {}
    mockApi.mockReturnValueOnce(new Promise((resolve) => { resolveOld = resolve })) // scopeId=7（旧・遅延）
    mockApi.mockResolvedValueOnce({ data: [] }) // scopeId=12（新・即時 NONE）

    const { joinRequestStatus, fetchJoinRequestStatus } = useJoinRequestSelfStatus('team')

    const oldPromise = fetchJoinRequestStatus(7)
    await fetchJoinRequestStatus(12)
    expect(joinRequestStatus.value).toBe('NONE')

    // 旧スコープ（7）の応答が今ごろ後着し、PENDING を返したとしても
    // 新スコープ（12・NONE）の状態を上書きしてはならない。
    resolveOld({ data: [makeRequest({ status: 'PENDING' })] })
    await oldPromise

    expect(joinRequestStatus.value).toBe('NONE')
  })

  it('旧スコープ（失敗）の遅い応答が、新スコープの状態を ERROR に落とさない', async () => {
    let rejectOld: (reason: unknown) => void = () => {}
    mockApi.mockReturnValueOnce(new Promise((_resolve, reject) => { rejectOld = reject })) // scopeId=7（旧・遅延失敗）
    mockApi.mockResolvedValueOnce({ data: [] }) // scopeId=12（新・即時 NONE）

    const { joinRequestStatus, fetchJoinRequestStatus } = useJoinRequestSelfStatus('team')

    const oldPromise = fetchJoinRequestStatus(7)
    await fetchJoinRequestStatus(12)
    expect(joinRequestStatus.value).toBe('NONE')

    rejectOld(new Error('old scope failed'))
    // fetchJoinRequestStatus は例外を内部で捕捉して re-throw しないため、
    // このawaitが拒否されることはない。
    await oldPromise

    expect(joinRequestStatus.value).toBe('NONE')
    expect(handleApiErrorMock).not.toHaveBeenCalled()
  })

  it('同一スコープへの2回目の取得が完了する前に1回目の応答が来ても、2回目の結果で確定する', async () => {
    let resolveFirst: (value: unknown) => void = () => {}
    mockApi.mockReturnValueOnce(new Promise((resolve) => { resolveFirst = resolve }))
    mockApi.mockResolvedValueOnce({ data: [makeRequest({ status: 'PENDING' })] })

    const { joinRequestStatus, fetchJoinRequestStatus } = useJoinRequestSelfStatus('team')

    const firstPromise = fetchJoinRequestStatus(12)
    await fetchJoinRequestStatus(12)
    expect(joinRequestStatus.value).toBe('PENDING')

    resolveFirst({ data: [] })
    await firstPromise

    expect(joinRequestStatus.value).toBe('PENDING')
  })
})
