import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { defineComponent, ref } from 'vue'
import { useChangeRequest } from './useChangeRequest'
import type { ChangeRequest } from '~/types/shift'

/**
 * CMP-261010-1432 FE-a: シフト変更依頼 composable の AC。
 *   AC-D1 成功文言が動作ラベルの流用でなく専用文言である
 *   AC-D2 失敗時は handleApiError を呼び、「取得に失敗しました」を出さず、素の Error を再 throw しない
 *   AC-I1 同じ依頼への取り下げ・審査は処理中に重ねて送らない
 *   AC-I3 409（SHIFT_031 / COMMON_003）のとき一覧を再取得する
 */
const mocks = vi.hoisted(() => ({
  listChangeRequests: vi.fn(),
  createChangeRequest: vi.fn(),
  reviewChangeRequest: vi.fn(),
  withdrawChangeRequest: vi.fn(),
  success: vi.fn(),
  error: vi.fn(),
  handleApiError: vi.fn(),
}))

mockNuxtImport('useShiftApi', () => () => ({
  listChangeRequests: mocks.listChangeRequests,
  createChangeRequest: mocks.createChangeRequest,
  reviewChangeRequest: mocks.reviewChangeRequest,
  withdrawChangeRequest: mocks.withdrawChangeRequest,
}))
mockNuxtImport('useNotification', () => () => ({
  success: mocks.success,
  error: mocks.error,
}))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError: mocks.handleApiError }))

function row(id: number, status: ChangeRequest['reviewInfo']['status'] = 'OPEN'): ChangeRequest {
  return {
    id,
    scheduleId: 10,
    slotId: null,
    requestInfo: { requestType: 'PRE_CONFIRM_EDIT', reason: null, requestedBy: 7 },
    reviewInfo: { status, reviewerId: null, reviewComment: null, reviewedAt: null },
    timing: { expiresAt: null, createdAt: '2026-10-01T00:00:00' },
  }
}

async function useIt() {
  let api!: ReturnType<typeof useChangeRequest>
  await mountSuspended(
    defineComponent({
      setup() {
        api = useChangeRequest(ref(10))
        return () => null
      },
    }),
  )
  return api
}

beforeEach(() => {
  Object.values(mocks).forEach(m => m.mockReset())
})

describe('useChangeRequest 成功文言（AC-D1）', () => {
  it('AC-D1: 作成・承認・却下・取り下げの成功トーストはボタンラベルと一致しない専用文言', async () => {
    const api = await useIt()
    mocks.createChangeRequest.mockResolvedValue(row(1))
    mocks.reviewChangeRequest.mockImplementation(async (id: number, p: { decision: 'ACCEPTED' | 'REJECTED' }) => row(id, p.decision))
    mocks.withdrawChangeRequest.mockResolvedValue(undefined)
    api.requests.value = [row(2), row(3), row(4)]

    await api.createRequest({ scheduleId: 10, requestType: 'PRE_CONFIRM_EDIT' })
    await api.review(2, 'ACCEPTED')
    await api.review(3, 'REJECTED')
    await api.withdraw(4)

    const messages = mocks.success.mock.calls.map(c => String(c[0]))
    expect(messages).toHaveLength(4)
    // ボタンラベル（submit/approve/reject/withdraw）そのものは使わない
    for (const label of ['Request Change', 'Approve', 'Reject', 'Withdraw']) {
      expect(messages).not.toContain(label)
    }
    expect(new Set(messages).size).toBe(4)
    expect(messages[0]).toBe('Change request submitted')
    expect(messages[1]).toBe('Change request approved')
    expect(messages[2]).toBe('Change request rejected')
    expect(messages[3]).toBe('Change request withdrawn')
  })
})

describe('useChangeRequest 失敗（AC-D2）', () => {
  it('AC-D2: 作成・審査・取り下げが失敗しても handleApiError を呼び、fetchError を出さず、reject しない', async () => {
    const api = await useIt()
    const failure = { statusCode: 500 }
    mocks.createChangeRequest.mockRejectedValue(failure)
    mocks.reviewChangeRequest.mockRejectedValue(failure)
    mocks.withdrawChangeRequest.mockRejectedValue(failure)
    mocks.listChangeRequests.mockResolvedValue([])
    api.requests.value = [row(2), row(4)]

    await expect(api.createRequest({ scheduleId: 10, requestType: 'PRE_CONFIRM_EDIT' })).resolves.toBe(false)
    await expect(api.review(2, 'ACCEPTED')).resolves.toBe(false)
    await expect(api.withdraw(4)).resolves.toBe(false)

    expect(mocks.handleApiError).toHaveBeenCalledTimes(3)
    expect(mocks.handleApiError).toHaveBeenCalledWith(failure, expect.any(String))
    expect(mocks.error).not.toHaveBeenCalled()
    expect(mocks.success).not.toHaveBeenCalled()
  })

  it('AC-D2: 審査コメントを review API に渡す（AC-A3 の composable 側）', async () => {
    const api = await useIt()
    mocks.reviewChangeRequest.mockResolvedValue(row(2, 'REJECTED'))
    api.requests.value = [row(2)]

    await api.review(2, 'REJECTED', '人手が足りません')

    expect(mocks.reviewChangeRequest).toHaveBeenCalledWith(
      2,
      expect.objectContaining({ decision: 'REJECTED', reviewComment: '人手が足りません' }),
    )
  })
})

describe('useChangeRequest 再入ガードと 409（AC-I1 / AC-I3）', () => {
  it('AC-I1: 取り下げを素早く2回呼んでも DELETE は1回だけで、処理中は processingIds に入る', async () => {
    const api = await useIt()
    let release!: () => void
    mocks.withdrawChangeRequest.mockImplementation(
      () => new Promise<void>((resolve) => { release = resolve }),
    )
    api.requests.value = [row(4)]

    const first = api.withdraw(4)
    const second = api.withdraw(4)
    expect(api.processingIds.value).toContain(4)
    release()
    await Promise.all([first, second])

    expect(mocks.withdrawChangeRequest).toHaveBeenCalledTimes(1)
    expect(api.processingIds.value).not.toContain(4)
  })

  it('AC-I1: 審査を素早く2回呼んでも PATCH は1回だけ', async () => {
    const api = await useIt()
    let release!: (v: ChangeRequest) => void
    mocks.reviewChangeRequest.mockImplementation(
      () => new Promise<ChangeRequest>((resolve) => { release = resolve }),
    )
    api.requests.value = [row(2)]

    const first = api.review(2, 'ACCEPTED')
    const second = api.review(2, 'ACCEPTED')
    release(row(2, 'ACCEPTED'))
    await Promise.all([first, second])

    expect(mocks.reviewChangeRequest).toHaveBeenCalledTimes(1)
  })

  it.each([
    ['SHIFT_031', 409],
    ['COMMON_003', 409],
  ])('AC-I3: 取り下げが %s の %i のとき一覧を再取得し、reject しない', async (code, statusCode) => {
    const api = await useIt()
    mocks.withdrawChangeRequest.mockRejectedValue({ statusCode, data: { error: { code } } })
    mocks.listChangeRequests.mockResolvedValue([row(4, 'ACCEPTED')])
    api.requests.value = [row(4)]

    await expect(api.withdraw(4)).resolves.toBe(false)

    expect(mocks.listChangeRequests).toHaveBeenCalledTimes(1)
    expect(api.requests.value[0]?.reviewInfo.status).toBe('ACCEPTED')
    expect(mocks.handleApiError).toHaveBeenCalledTimes(1)
  })

  it('AC-I3: 審査が COMMON_003 の 409 のときも一覧を再取得する', async () => {
    const api = await useIt()
    mocks.reviewChangeRequest.mockRejectedValue({ statusCode: 409, data: { error: { code: 'COMMON_003' } } })
    mocks.listChangeRequests.mockResolvedValue([row(2, 'WITHDRAWN')])
    api.requests.value = [row(2)]

    await api.review(2, 'ACCEPTED')

    expect(mocks.listChangeRequests).toHaveBeenCalledTimes(1)
  })
})

describe('useChangeRequest 取得（AC-E）', () => {
  it('取得失敗は通知ではなく loadError に積み、一覧を空にしたまま成功扱いにしない', async () => {
    const api = await useIt()
    const failure = { statusCode: 403 }
    mocks.listChangeRequests.mockRejectedValue(failure)

    const result = await api.fetchRequests()

    expect(result).toEqual(failure)
    expect(api.loadError.value).toEqual(failure)
    expect(mocks.error).not.toHaveBeenCalled()
  })
})
