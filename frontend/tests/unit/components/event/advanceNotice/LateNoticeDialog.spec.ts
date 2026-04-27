import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { setActivePinia, createPinia } from 'pinia'
import LateNoticeDialog from '~/components/event/advanceNotice/LateNoticeDialog.vue'
import type { AdvanceNoticeResponse } from '~/types/care'

/**
 * F03.12 §15 LateNoticeDialog.vue のユニットテスト。
 *
 * - lateArrivalMinutes 範囲外（0 / 121）でバリデーションが効く
 * - 送信時に useAdvanceNotice().submitLate が正しいペイロードで呼ばれる
 * - 成功時に submitted を emit + update:open(false)
 *
 * <p>PrimeVue Dialog は {@code <Teleport to="body">} で body 直下に出るため
 * 取得は {@code document.body.querySelector('[data-testid=...]')} で行う。</p>
 */

const mockSubmitLate = vi.fn()
const mockSubmitAbsence = vi.fn()
const mockLoadAdvanceNotices = vi.fn()

vi.mock('~/composables/useAdvanceNotice', () => ({
  useAdvanceNotice: () => ({
    notices: { value: [] },
    loading: { value: false },
    error: { value: null },
    submitLate: mockSubmitLate,
    submitAbsence: mockSubmitAbsence,
    loadAdvanceNotices: mockLoadAdvanceNotices,
  }),
}))

const mockToastSuccess = vi.fn()
const mockToastInfo = vi.fn()
const mockToastError = vi.fn()
vi.mock('~/composables/useNotification', () => ({
  useNotification: () => ({
    success: mockToastSuccess,
    info: mockToastInfo,
    warn: vi.fn(),
    error: mockToastError,
  }),
}))

function findByTestId<T extends Element = HTMLElement>(testId: string): T | null {
  return document.body.querySelector<T>(`[data-testid="${testId}"]`)
}
function getByTestId<T extends Element = HTMLElement>(testId: string): T {
  const el = findByTestId<T>(testId)
  if (!el) throw new Error(`[data-testid="${testId}"] が見つかりません`)
  return el
}

beforeEach(() => {
  setActivePinia(createPinia())
  mockSubmitLate.mockReset()
  mockSubmitAbsence.mockReset()
  mockLoadAdvanceNotices.mockReset()
  mockToastSuccess.mockReset()
  mockToastInfo.mockReset()
  mockToastError.mockReset()
})

afterEach(() => {
  const dialog = findByTestId('late-notice-dialog')
  dialog?.parentElement?.removeChild(dialog)
})

describe('LateNoticeDialog.vue', () => {
  it('open=false ではダイアログが表示されない', async () => {
    await mountSuspended(LateNoticeDialog, {
      props: { teamId: 7, eventId: 42, userId: 101, open: false },
    })
    expect(findByTestId('late-notice-dialog')).toBeNull()
  })

  it('open=true でダイアログが表示される', async () => {
    await mountSuspended(LateNoticeDialog, {
      props: { teamId: 7, eventId: 42, userId: 101, open: true },
    })
    expect(findByTestId('late-notice-dialog')).not.toBeNull()
    expect(findByTestId('late-notice-submit')).not.toBeNull()
  })

  it('範囲外（0 分）では送信ボタンが disabled になる', async () => {
    const wrapper = await mountSuspended(LateNoticeDialog, {
      props: { teamId: 7, eventId: 42, userId: 101, open: true },
    })
    // VM の内部 ref を直接操作してバリデーション分岐を検証
    const vm = wrapper.vm as unknown as { lateArrivalMinutes: number }
    vm.lateArrivalMinutes = 0
    await wrapper.vm.$nextTick()
    const submitBtn = getByTestId<HTMLButtonElement>('late-notice-submit')
    expect(submitBtn.disabled).toBe(true)
  })

  it('範囲外（121 分）では送信ボタンが disabled になる', async () => {
    const wrapper = await mountSuspended(LateNoticeDialog, {
      props: { teamId: 7, eventId: 42, userId: 101, open: true },
    })
    const vm = wrapper.vm as unknown as { lateArrivalMinutes: number }
    vm.lateArrivalMinutes = 121
    await wrapper.vm.$nextTick()
    const submitBtn = getByTestId<HTMLButtonElement>('late-notice-submit')
    expect(submitBtn.disabled).toBe(true)
  })

  it('送信成功時に submitLate が正しいペイロードで呼ばれ submitted/update:open(false) を emit', async () => {
    const expectedRes: AdvanceNoticeResponse = {
      userId: 101,
      displayName: '太郎',
      noticeType: 'LATE',
      expectedArrivalMinutesLate: 25,
      absenceReason: null,
      comment: '電車遅延',
      createdAt: '2026-04-27T09:00:00.000Z',
    }
    mockSubmitLate.mockResolvedValueOnce(expectedRes)

    const wrapper = await mountSuspended(LateNoticeDialog, {
      props: { teamId: 7, eventId: 42, userId: 101, open: true },
    })
    const vm = wrapper.vm as unknown as { lateArrivalMinutes: number; comment: string }
    vm.lateArrivalMinutes = 25
    vm.comment = '電車遅延'
    await wrapper.vm.$nextTick()

    const submitBtn = getByTestId<HTMLButtonElement>('late-notice-submit')
    submitBtn.click()
    await new Promise((resolve) => setTimeout(resolve, 0))
    await new Promise((resolve) => setTimeout(resolve, 0))
    await wrapper.vm.$nextTick()

    expect(mockSubmitLate).toHaveBeenCalledTimes(1)
    expect(mockSubmitLate).toHaveBeenCalledWith({
      userId: 101,
      expectedArrivalMinutesLate: 25,
      comment: '電車遅延',
    })

    expect(wrapper.emitted('submitted')).toBeTruthy()
    const submittedPayload = wrapper.emitted('submitted')?.[0]
    expect(submittedPayload).toEqual([expectedRes])

    const closeEmits = wrapper.emitted('update:open')
    expect(closeEmits).toBeTruthy()
    expect(closeEmits?.[closeEmits.length - 1]).toEqual([false])
  })

  it('オフライン（null 戻り）時は info トーストが呼ばれる', async () => {
    mockSubmitLate.mockResolvedValueOnce(null)

    const wrapper = await mountSuspended(LateNoticeDialog, {
      props: { teamId: 7, eventId: 42, userId: 101, open: true },
    })
    const vm = wrapper.vm as unknown as { lateArrivalMinutes: number }
    vm.lateArrivalMinutes = 10
    await wrapper.vm.$nextTick()

    getByTestId<HTMLButtonElement>('late-notice-submit').click()
    await new Promise((resolve) => setTimeout(resolve, 0))
    await new Promise((resolve) => setTimeout(resolve, 0))
    await wrapper.vm.$nextTick()

    expect(mockToastInfo).toHaveBeenCalledTimes(1)
    expect(mockToastSuccess).not.toHaveBeenCalled()
  })

  it('キャンセルボタンで update:open(false) を emit', async () => {
    const wrapper = await mountSuspended(LateNoticeDialog, {
      props: { teamId: 7, eventId: 42, userId: 101, open: true },
    })
    getByTestId<HTMLButtonElement>('late-notice-cancel').click()
    await wrapper.vm.$nextTick()
    const closeEmits = wrapper.emitted('update:open')
    expect(closeEmits).toBeTruthy()
    expect(closeEmits?.[closeEmits.length - 1]).toEqual([false])
  })
})
