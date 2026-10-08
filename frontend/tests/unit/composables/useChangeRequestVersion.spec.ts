import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'

const { api, showError } = vi.hoisted(() => ({
  api: { listChangeRequests: vi.fn(), reviewChangeRequest: vi.fn() },
  showError: vi.fn(),
}))
mockNuxtImport('useShiftApi', () => () => api)
mockNuxtImport('useNotification', () => () => ({ success: vi.fn(), error: showError }))
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))

const { useChangeRequest } = await import('~/composables/useChangeRequest')

function response(version: unknown) {
  return {
    id: 7, scheduleId: 1, slotId: null, version,
    requestInfo: { requestType: 'PRE_CONFIRM_EDIT' as const, reason: '所有依頼', requestedBy: 4 },
    reviewInfo: { status: 'OPEN' as const, reviewerId: null, reviewComment: null, reviewedAt: null },
    timing: { expiresAt: null, createdAt: '2026-10-08T00:00:00' },
  }
}

beforeEach(() => vi.resetAllMocks())

describe('CMP-261008-1407 変更依頼の審査版契約', () => {
  it.each([0, 2_147_483_648])('取得した実版 %s を送信し、応答全体を保存する', async (version) => {
    const before = response(version)
    const updated = {
      ...before, version: version + 1,
      reviewInfo: { status: 'ACCEPTED', reviewerId: 3, reviewComment: '承認', reviewedAt: '2026-10-08T00:01:00' },
    }
    api.listChangeRequests.mockResolvedValue([before])
    api.reviewChangeRequest.mockResolvedValue(updated)
    const state = useChangeRequest(ref(1))
    await state.fetchRequests()
    await state.review(7, 'ACCEPTED')
    expect(api.reviewChangeRequest).toHaveBeenCalledTimes(1)
    expect(api.reviewChangeRequest).toHaveBeenCalledWith(7, {
      decision: 'ACCEPTED', reviewComment: undefined, version,
    })
    expect(state.requests.value[0]).toEqual(updated)
  })

  it.each([undefined, null, NaN, Infinity, -1, 0.5, Number.MAX_SAFE_INTEGER + 1])(
    '版 %s は送信せず通知する', async (version) => {
      const state = useChangeRequest(ref(1))
      state.requests.value = [response(version)]
      await expect(state.review(7, 'ACCEPTED')).rejects.toBeDefined()
      expect(api.reviewChangeRequest).not.toHaveBeenCalled()
      expect(showError).toHaveBeenCalledTimes(1)
      expect(showError).toHaveBeenCalledWith('shift.notification.errorUpdate')
    },
  )

  it('対象が未取得なら送信しない', async () => {
    const state = useChangeRequest(ref(1))
    await expect(state.review(7, 'ACCEPTED')).rejects.toBeDefined()
    expect(api.reviewChangeRequest).not.toHaveBeenCalled()
  })

  it('409では元例外と表示状態を保持し、競合通知は一度、再送はしない', async () => {
    const error = { statusCode: 409, data: { error: { code: 'SHIFT_018' } } }
    api.reviewChangeRequest.mockRejectedValue(error)
    const state = useChangeRequest(ref(1))
    const before = response(1)
    state.requests.value = [before]
    await expect(state.review(7, 'ACCEPTED')).rejects.toBe(error)
    expect(state.requests.value[0]).toEqual(before)
    expect(api.reviewChangeRequest).toHaveBeenCalledTimes(1)
    expect(showError).toHaveBeenCalledTimes(1)
    expect(showError).toHaveBeenCalledWith('error.COMMON_003')
  })
})
