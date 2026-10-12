import { describe, it, expect, beforeEach, vi } from 'vitest'
import { ref } from 'vue'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'

/**
 * CMP-260930-1532: 朝の点呼の提出。
 *   - 失敗時は握りつぶさず共通エラーハンドラ（handleApiError）へ渡す
 *   - 提出中の再呼び出し（二重送信）は API を叩かない
 */
const mockSubmitRollCall = vi.fn()
const mockGetDailyAttendance = vi.fn()
const handleApiError = vi.fn()

vi.mock('~/composables/useDailyRollCallApi', () => ({
  useDailyRollCallApi: () => ({
    getDailyAttendance: mockGetDailyAttendance,
    submitRollCall: mockSubmitRollCall,
  }),
}))
mockNuxtImport('useNotification', () => () => ({
  success: vi.fn(),
  error: vi.fn(),
  info: vi.fn(),
  warn: vi.fn(),
}))
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError, handleError: handleApiError }))

const { useDailyRollCall } = await import('~/composables/useDailyRollCall')

const entries = [{ studentUserId: 1, status: 'ABSENT' as const, absenceReason: 'SICK' as const }]

beforeEach(() => {
  mockSubmitRollCall.mockReset()
  mockGetDailyAttendance.mockReset()
  handleApiError.mockReset()
})

describe('useDailyRollCall', () => {
  it('提出失敗時に共通エラーハンドラへ例外を渡し、null を返す', async () => {
    const err = { statusCode: 400, data: { error: { code: 'COMMON_001', message: 'bad' } } }
    mockSubmitRollCall.mockRejectedValue(err)
    const { submitRollCall } = useDailyRollCall(ref('t1'))
    const result = await submitRollCall('2026-09-30', entries)
    expect(result).toBeNull()
    expect(handleApiError).toHaveBeenCalledTimes(1)
    expect(handleApiError.mock.calls[0]![0]).toBe(err)
  })

  it('例外後に submitting が false へ戻る（再提出できる）', async () => {
    mockSubmitRollCall.mockRejectedValueOnce(new Error('boom'))
    mockSubmitRollCall.mockResolvedValueOnce({ total: 1 })
    const { submitRollCall, submitting } = useDailyRollCall(ref('t1'))
    await submitRollCall('2026-09-30', entries)
    expect(submitting.value).toBe(false)
    const again = await submitRollCall('2026-09-30', entries)
    expect(again).not.toBeNull()
    expect(mockSubmitRollCall).toHaveBeenCalledTimes(2)
  })

  it('提出中の二重呼び出しは API を1回しか叩かない', async () => {
    let resolveFn: (v: unknown) => void = () => {}
    mockSubmitRollCall.mockReturnValue(new Promise((r) => (resolveFn = r)))
    const { submitRollCall, submitting } = useDailyRollCall(ref('t1'))
    const p1 = submitRollCall('2026-09-30', entries)
    const p2 = submitRollCall('2026-09-30', entries)
    expect(submitting.value).toBe(true)
    resolveFn({ total: 1 })
    await Promise.all([p1, p2])
    expect(mockSubmitRollCall).toHaveBeenCalledTimes(1)
  })

  it('一覧取得が 403 のとき forbidden を立て、トーストで済ませず握りつぶさない（AC-18）', async () => {
    mockGetDailyAttendance.mockRejectedValue({ statusCode: 403, data: { error: { code: 'COMMON_002' } } })
    const { loadRecords, forbidden, loading } = useDailyRollCall(ref('t1'))
    await loadRecords('2026-09-30')
    expect(forbidden.value).toBe(true)
    expect(loading.value).toBe(false)
    expect(handleApiError).not.toHaveBeenCalled()
  })

  it('一覧取得が 403 以外で失敗したら共通エラーハンドラへ渡し forbidden は立てない', async () => {
    mockGetDailyAttendance.mockRejectedValue({ statusCode: 500 })
    const { loadRecords, forbidden } = useDailyRollCall(ref('t1'))
    await loadRecords('2026-09-30')
    expect(forbidden.value).toBe(false)
    expect(handleApiError).toHaveBeenCalledTimes(1)
  })
})
