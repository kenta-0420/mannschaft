import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import ReservationWaitlistDialog, { type WaitlistDialogContext } from '~/components/reservation/ReservationWaitlistDialog.vue'

/**
 * ReservationWaitlistDialog.vue（キャンセル待ち登録・取消ダイアログ・F03.4.5 §6.1 W2-4-FE）ユニットテスト — 番人
 *
 * 観点（AC 対応）:
 *   AC-1: 未登録の場合は「登録」ボタンが表示され、クリックで joinWaitlist を呼ぶ
 *   AC-2: 登録済み（registeredSlotIds に slotId を含む）の場合は「取消」ボタンが表示され、
 *         クリックで leaveWaitlist を呼ぶ
 *   AC-3: isAdmin=true の場合、開いたときに getWaitlistCount を1回だけ呼び件数を表示する
 *   AC-4: isAdmin=false の場合、getWaitlistCount を呼ばない（403回避）
 *   AC-5: 登録失敗＝RESERVATION_048（空きあり）は info トーストを出し changed を emit して閉じる
 *   AC-6: 登録失敗＝RESERVATION_047（二重登録）は error トーストを出し changed を emit して閉じる
 *   AC-7: 取消失敗＝RESERVATION_046（対象なし）は error トーストを出し changed を emit して閉じる
 *
 * 注: テスト環境の既定ロケールは en。Dialog は Teleport で document.body にレンダリングされる。
 */
const mockJoinWaitlist = vi.fn()
const mockLeaveWaitlist = vi.fn()
const mockGetWaitlistCount = vi.fn()

vi.mock('~/composables/useReservationApi', () => ({
  useReservationApi: () => ({
    joinWaitlist: mockJoinWaitlist,
    leaveWaitlist: mockLeaveWaitlist,
    getWaitlistCount: mockGetWaitlistCount,
  }),
}))

const mockNotifySuccess = vi.fn()
const mockNotifyError = vi.fn()
const mockNotifyInfo = vi.fn()
mockNuxtImport('useNotification', () => () => ({
  success: mockNotifySuccess,
  info: mockNotifyInfo,
  warn: vi.fn(),
  error: mockNotifyError,
}))

const mockHandleApiError = vi.fn()
mockNuxtImport('useErrorHandler', () => () => ({
  resolveMessage: (code: string) => code,
  handleApiError: mockHandleApiError,
  handleError: mockHandleApiError,
  getFieldErrors: () => ({}),
}))

function findByTestId<T extends Element = HTMLElement>(testId: string): T | null {
  return document.body.querySelector<T>(`[data-testid="${testId}"]`)
}

async function flush() {
  await new Promise(r => setTimeout(r, 0))
  await new Promise(r => setTimeout(r, 0))
}

const context: WaitlistDialogContext = {
  slotId: 501,
  date: '2026-08-01',
  startTime: '10:00',
  endTime: '10:30',
  lineName: 'Seat1',
}

beforeEach(() => {
  mockJoinWaitlist.mockReset()
  mockLeaveWaitlist.mockReset()
  mockGetWaitlistCount.mockReset()
  mockNotifySuccess.mockReset()
  mockNotifyError.mockReset()
  mockNotifyInfo.mockReset()
  mockHandleApiError.mockReset()
})

afterEach(() => {
  document.body.querySelectorAll('.p-dialog').forEach(el => el.remove())
})

describe('ReservationWaitlistDialog.vue', () => {
  it('AC-1: 未登録なら「登録」ボタンが出て、クリックで joinWaitlist を呼ぶ', async () => {
    mockJoinWaitlist.mockResolvedValue({ data: {} })
    const wrapper = await mountSuspended(ReservationWaitlistDialog, {
      props: {
        visible: true,
        teamId: 'team-slug',
        isAdmin: false,
        resourceName: 'Seat',
        context,
        registeredSlotIds: new Set<number>(),
      },
    })
    await flush()

    expect(findByTestId('waitlist-register')).not.toBeNull()
    expect(findByTestId('waitlist-cancel')).toBeNull()

    findByTestId<HTMLButtonElement>('waitlist-register')!.click()
    await flush()

    expect(mockJoinWaitlist).toHaveBeenCalledWith('team-slug', 501)
    expect(mockNotifySuccess).toHaveBeenCalled()
    expect(wrapper.emitted('changed')).toBeTruthy()
    expect(wrapper.emitted('update:visible')).toBeTruthy()
  })

  it('AC-2: 登録済み（registeredSlotIds に501を含む）なら「取消」ボタンが出て、クリックで leaveWaitlist を呼ぶ', async () => {
    mockLeaveWaitlist.mockResolvedValue({})
    const wrapper = await mountSuspended(ReservationWaitlistDialog, {
      props: {
        visible: true,
        teamId: 'team-slug',
        isAdmin: false,
        resourceName: 'Seat',
        context,
        registeredSlotIds: new Set<number>([501]),
      },
    })
    await flush()

    expect(findByTestId('waitlist-cancel')).not.toBeNull()
    expect(findByTestId('waitlist-register')).toBeNull()

    findByTestId<HTMLButtonElement>('waitlist-cancel')!.click()
    await flush()

    expect(mockLeaveWaitlist).toHaveBeenCalledWith('team-slug', 501)
    expect(mockNotifySuccess).toHaveBeenCalled()
    expect(wrapper.emitted('changed')).toBeTruthy()
  })

  it('AC-3: isAdmin=true は開いたときに getWaitlistCount を呼び件数を表示する', async () => {
    mockGetWaitlistCount.mockResolvedValue({ data: { slotId: 501, waitingCount: 3 } })
    await mountSuspended(ReservationWaitlistDialog, {
      props: {
        visible: true,
        teamId: 'team-slug',
        isAdmin: true,
        resourceName: 'Seat',
        context,
        registeredSlotIds: new Set<number>(),
      },
    })
    await flush()

    expect(mockGetWaitlistCount).toHaveBeenCalledWith('team-slug', 501)
    expect(findByTestId('waitlist-admin-count')?.textContent).toContain('3')
  })

  it('AC-4: isAdmin=false は getWaitlistCount を呼ばない（403回避）', async () => {
    await mountSuspended(ReservationWaitlistDialog, {
      props: {
        visible: true,
        teamId: 'team-slug',
        isAdmin: false,
        resourceName: 'Seat',
        context,
        registeredSlotIds: new Set<number>(),
      },
    })
    await flush()

    expect(mockGetWaitlistCount).not.toHaveBeenCalled()
  })

  it('AC-5: 登録失敗=RESERVATION_048（空きあり）は info トーストを出し changed を emit して閉じる', async () => {
    mockJoinWaitlist.mockRejectedValue({ data: { error: { code: 'RESERVATION_048' } } })
    const wrapper = await mountSuspended(ReservationWaitlistDialog, {
      props: {
        visible: true,
        teamId: 'team-slug',
        isAdmin: false,
        resourceName: 'Seat',
        context,
        registeredSlotIds: new Set<number>(),
      },
    })
    await flush()

    findByTestId<HTMLButtonElement>('waitlist-register')!.click()
    await flush()

    expect(mockNotifyInfo).toHaveBeenCalled()
    expect(wrapper.emitted('changed')).toBeTruthy()
    expect(wrapper.emitted('update:visible')).toBeTruthy()
  })

  it('AC-6: 登録失敗=RESERVATION_047（二重登録）は error トーストを出し changed を emit して閉じる', async () => {
    mockJoinWaitlist.mockRejectedValue({ data: { error: { code: 'RESERVATION_047' } } })
    const wrapper = await mountSuspended(ReservationWaitlistDialog, {
      props: {
        visible: true,
        teamId: 'team-slug',
        isAdmin: false,
        resourceName: 'Seat',
        context,
        registeredSlotIds: new Set<number>(),
      },
    })
    await flush()

    findByTestId<HTMLButtonElement>('waitlist-register')!.click()
    await flush()

    expect(mockNotifyError).toHaveBeenCalled()
    expect(wrapper.emitted('changed')).toBeTruthy()
  })

  it('AC-7: 取消失敗=RESERVATION_046（対象なし）は error トーストを出し changed を emit して閉じる', async () => {
    mockLeaveWaitlist.mockRejectedValue({ data: { error: { code: 'RESERVATION_046' } } })
    const wrapper = await mountSuspended(ReservationWaitlistDialog, {
      props: {
        visible: true,
        teamId: 'team-slug',
        isAdmin: false,
        resourceName: 'Seat',
        context,
        registeredSlotIds: new Set<number>([501]),
      },
    })
    await flush()

    findByTestId<HTMLButtonElement>('waitlist-cancel')!.click()
    await flush()

    expect(mockNotifyError).toHaveBeenCalled()
    expect(wrapper.emitted('changed')).toBeTruthy()
  })
})
