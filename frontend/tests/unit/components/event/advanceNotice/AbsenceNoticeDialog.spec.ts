import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { setActivePinia, createPinia } from 'pinia'
import AbsenceNoticeDialog from '~/components/event/advanceNotice/AbsenceNoticeDialog.vue'
import type { AdvanceAbsenceReason, AdvanceNoticeResponse } from '~/types/care'

/**
 * F03.12 §15 AbsenceNoticeDialog.vue のユニットテスト。
 *
 * - 理由 ENUM (SICK / PERSONAL_REASON / OTHER) のラジオが表示される
 * - 選択した理由が submitAbsence のペイロードに反映される
 * - 成功時に submitted を emit + update:open(false)
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
  const dialog = findByTestId('absence-notice-dialog')
  dialog?.parentElement?.removeChild(dialog)
})

describe('AbsenceNoticeDialog.vue', () => {
  it('open=true で 3 種類のラジオ（SICK/PERSONAL_REASON/OTHER）が表示される', async () => {
    await mountSuspended(AbsenceNoticeDialog, {
      props: { teamId: 7, eventId: 42, userId: 101, open: true },
    })
    expect(findByTestId('absence-reason-SICK')).not.toBeNull()
    expect(findByTestId('absence-reason-PERSONAL_REASON')).not.toBeNull()
    expect(findByTestId('absence-reason-OTHER')).not.toBeNull()
  })

  it('初期値は SICK である', async () => {
    const wrapper = await mountSuspended(AbsenceNoticeDialog, {
      props: { teamId: 7, eventId: 42, userId: 101, open: true },
    })
    const vm = wrapper.vm as unknown as { absenceReason: AdvanceAbsenceReason }
    expect(vm.absenceReason).toBe('SICK')
  })

  it('PERSONAL_REASON を選択して送信すると正しいペイロードで submitAbsence が呼ばれる', async () => {
    const expectedRes: AdvanceNoticeResponse = {
      userId: 101,
      displayName: '太郎',
      noticeType: 'ABSENCE',
      expectedArrivalMinutesLate: null,
      absenceReason: 'PERSONAL_REASON',
      comment: '私用',
      createdAt: '2026-04-27T09:00:00.000Z',
    }
    mockSubmitAbsence.mockResolvedValueOnce(expectedRes)

    const wrapper = await mountSuspended(AbsenceNoticeDialog, {
      props: { teamId: 7, eventId: 42, userId: 101, open: true },
    })
    const vm = wrapper.vm as unknown as {
      absenceReason: AdvanceAbsenceReason
      comment: string
    }
    vm.absenceReason = 'PERSONAL_REASON'
    vm.comment = '私用'
    await wrapper.vm.$nextTick()

    getByTestId<HTMLButtonElement>('absence-notice-submit').click()
    await new Promise((resolve) => setTimeout(resolve, 0))
    await new Promise((resolve) => setTimeout(resolve, 0))
    await wrapper.vm.$nextTick()

    expect(mockSubmitAbsence).toHaveBeenCalledTimes(1)
    expect(mockSubmitAbsence).toHaveBeenCalledWith({
      userId: 101,
      absenceReason: 'PERSONAL_REASON',
      comment: '私用',
    })

    expect(wrapper.emitted('submitted')).toBeTruthy()
    const closeEmits = wrapper.emitted('update:open')
    expect(closeEmits?.[closeEmits.length - 1]).toEqual([false])
  })

  it('コメントが空のとき payload.comment は undefined（送信されない）', async () => {
    mockSubmitAbsence.mockResolvedValueOnce({
      userId: 101,
      displayName: '太郎',
      noticeType: 'ABSENCE',
      expectedArrivalMinutesLate: null,
      absenceReason: 'OTHER',
      comment: null,
      createdAt: '2026-04-27T09:00:00.000Z',
    } as AdvanceNoticeResponse)

    const wrapper = await mountSuspended(AbsenceNoticeDialog, {
      props: { teamId: 7, eventId: 42, userId: 101, open: true },
    })
    const vm = wrapper.vm as unknown as {
      absenceReason: AdvanceAbsenceReason
      comment: string
    }
    vm.absenceReason = 'OTHER'
    vm.comment = '   ' // 空白のみ → trim 後は空 → undefined
    await wrapper.vm.$nextTick()

    getByTestId<HTMLButtonElement>('absence-notice-submit').click()
    await new Promise((resolve) => setTimeout(resolve, 0))
    await new Promise((resolve) => setTimeout(resolve, 0))
    await wrapper.vm.$nextTick()

    expect(mockSubmitAbsence).toHaveBeenCalledWith({
      userId: 101,
      absenceReason: 'OTHER',
      comment: undefined,
    })
  })

  it('キャンセルで update:open(false) を emit', async () => {
    const wrapper = await mountSuspended(AbsenceNoticeDialog, {
      props: { teamId: 7, eventId: 42, userId: 101, open: true },
    })
    getByTestId<HTMLButtonElement>('absence-notice-cancel').click()
    await wrapper.vm.$nextTick()
    const closeEmits = wrapper.emitted('update:open')
    expect(closeEmits).toBeTruthy()
    expect(closeEmits?.[closeEmits.length - 1]).toEqual([false])
  })
})
