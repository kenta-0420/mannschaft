import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'

/**
 * CMP-260930-1532: 保護者連絡の提出。
 *   - 失敗時は BE のエラー内容が出るよう共通エラーハンドラ（handleApiError）へ渡す
 *   - 提出中の再呼び出し（二重送信）は API を叩かない
 *   - 例外後に submitting が false へ戻る
 */
const mockSubmitNotice = vi.fn()
const handleApiError = vi.fn()
const notifyError = vi.fn()

vi.mock('~/composables/useFamilyAttendanceNoticeApi', () => ({
  useFamilyAttendanceNoticeApi: () => ({
    submitNotice: mockSubmitNotice,
    getMyNotices: vi.fn(),
  }),
}))
mockNuxtImport('useNotification', () => () => ({
  success: vi.fn(),
  error: notifyError,
  info: vi.fn(),
  warn: vi.fn(),
}))
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError, handleError: handleApiError }))

const { useFamilyAttendanceNoticeForm } = await import('~/composables/useFamilyAttendanceNotice')

const body = {
  teamId: 't1',
  studentUserId: 1,
  attendanceDate: '2026-10-01',
  noticeType: 'ABSENCE' as const,
  reason: 'SICK' as const,
}

beforeEach(() => {
  mockSubmitNotice.mockReset()
  handleApiError.mockReset()
  notifyError.mockReset()
})

describe('useFamilyAttendanceNoticeForm.submitNotice', () => {
  it('403 などの失敗は共通エラーハンドラへ例外ごと渡し、false を返す', async () => {
    const err = { statusCode: 403, data: { error: { code: 'COMMON_003', message: '権限がありません' } } }
    mockSubmitNotice.mockRejectedValue(err)
    const { submitNotice } = useFamilyAttendanceNoticeForm()
    expect(await submitNotice(body)).toBe(false)
    expect(handleApiError).toHaveBeenCalledTimes(1)
    expect(handleApiError.mock.calls[0]![0]).toBe(err)
    expect(notifyError).not.toHaveBeenCalled()
  })

  it('提出中の二重呼び出しは API を1回しか叩かない', async () => {
    let resolveFn: (v: unknown) => void = () => {}
    mockSubmitNotice.mockReturnValue(new Promise((r) => (resolveFn = r)))
    const { submitNotice, submitting } = useFamilyAttendanceNoticeForm()
    const p1 = submitNotice(body)
    const p2 = submitNotice(body)
    expect(submitting.value).toBe(true)
    resolveFn({})
    await Promise.all([p1, p2])
    expect(mockSubmitNotice).toHaveBeenCalledTimes(1)
  })

  it('例外後に submitting が false へ戻る（再提出できる）', async () => {
    mockSubmitNotice.mockRejectedValueOnce(new Error('boom'))
    mockSubmitNotice.mockResolvedValueOnce({})
    const { submitNotice, submitting } = useFamilyAttendanceNoticeForm()
    await submitNotice(body)
    expect(submitting.value).toBe(false)
    expect(await submitNotice(body)).toBe(true)
    expect(mockSubmitNotice).toHaveBeenCalledTimes(2)
  })
})
