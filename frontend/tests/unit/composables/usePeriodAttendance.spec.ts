import { describe, it, expect, beforeEach, vi } from 'vitest'
import { ref } from 'vue'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'

/**
 * CMP-260930-1532: 時限点呼の提出。
 *   - 失敗時は握りつぶさず共通エラーハンドラ（handleApiError）へ渡す
 *   - 提出中の再呼び出し（二重送信）は API を叩かない
 *   - 例外後に submitting が false へ戻る
 */
const mockSubmit = vi.fn()
const mockGetCandidates = vi.fn()
const handleApiError = vi.fn()

vi.mock('~/composables/usePeriodAttendanceApi', () => ({
  usePeriodAttendanceApi: () => ({
    getPeriodCandidates: mockGetCandidates,
    submitPeriodAttendance: mockSubmit,
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

const { usePeriodAttendance } = await import('~/composables/usePeriodAttendance')

const entries = [{ studentUserId: 1, status: 'ABSENT' as const, absenceReason: 'SICK' as const }]

beforeEach(() => {
  mockSubmit.mockReset()
  mockGetCandidates.mockReset()
  handleApiError.mockReset()
})

describe('usePeriodAttendance', () => {
  it('提出失敗時に共通エラーハンドラへ例外を渡し、null を返す', async () => {
    const err = { statusCode: 400, data: { error: { code: 'COMMON_001', message: 'bad' } } }
    mockSubmit.mockRejectedValue(err)
    const { submitPeriodAttendance } = usePeriodAttendance(ref('t1'))
    const result = await submitPeriodAttendance(1, '2026-09-30', entries)
    expect(result).toBeNull()
    expect(handleApiError).toHaveBeenCalledTimes(1)
    expect(handleApiError.mock.calls[0]![0]).toBe(err)
  })

  it('取得失敗時も共通エラーハンドラへ渡す', async () => {
    const err = new Error('load failed')
    mockGetCandidates.mockRejectedValue(err)
    const { loadCandidates, loading } = usePeriodAttendance(ref('t1'))
    await loadCandidates(1, '2026-09-30')
    expect(handleApiError.mock.calls[0]![0]).toBe(err)
    expect(loading.value).toBe(false)
  })

  it('提出中の二重呼び出しは API を1回しか叩かない', async () => {
    let resolveFn: (v: unknown) => void = () => {}
    mockSubmit.mockReturnValue(new Promise((r) => (resolveFn = r)))
    const { submitPeriodAttendance, submitting } = usePeriodAttendance(ref('t1'))
    const p1 = submitPeriodAttendance(1, '2026-09-30', entries)
    const p2 = submitPeriodAttendance(1, '2026-09-30', entries)
    expect(submitting.value).toBe(true)
    resolveFn({ total: 1 })
    await Promise.all([p1, p2])
    expect(mockSubmit).toHaveBeenCalledTimes(1)
  })

  it('例外後に submitting が false へ戻る（再提出できる）', async () => {
    mockSubmit.mockRejectedValueOnce(new Error('boom'))
    mockSubmit.mockResolvedValueOnce({ total: 1 })
    const { submitPeriodAttendance, submitting } = usePeriodAttendance(ref('t1'))
    await submitPeriodAttendance(1, '2026-09-30', entries)
    expect(submitting.value).toBe(false)
    const again = await submitPeriodAttendance(1, '2026-09-30', entries)
    expect(again).not.toBeNull()
    expect(mockSubmit).toHaveBeenCalledTimes(2)
  })
})
