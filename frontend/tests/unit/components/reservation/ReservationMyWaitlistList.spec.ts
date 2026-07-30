import { describe, it, expect, vi, beforeEach, beforeAll } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import ReservationMyWaitlistList from '~/components/reservation/ReservationMyWaitlistList.vue'

/**
 * ReservationMyWaitlistList.vue（自分のキャンセル待ち一覧・F03.4.5 §6.1 W2-4-FE）ユニットテスト — 番人
 *
 * 観点（AC 対応）:
 *   AC-1: listMyWaitlist の応答をこのチーム（teamId）分のみに絞り込んで表示する
 *   AC-2: 0件のときは空状態を表示する
 *   AC-3: 取消ボタンで leaveWaitlist を呼び、成功後に一覧を再読込する
 *   AC-4: 取消失敗=RESERVATION_046（対象なし）は専用文言で通知し、一覧を再読込する
 *
 * 注: テスト環境の既定ロケールは en。
 */
const mockListMyWaitlist = vi.fn()
const mockLeaveWaitlist = vi.fn()

vi.mock('~/composables/useReservationApi', () => ({
  useReservationApi: () => ({
    listMyWaitlist: mockListMyWaitlist,
    leaveWaitlist: mockLeaveWaitlist,
  }),
}))

const mockNotifySuccess = vi.fn()
const mockNotifyError = vi.fn()
mockNuxtImport('useNotification', () => () => ({
  success: mockNotifySuccess,
  info: vi.fn(),
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

async function flush() {
  await new Promise(r => setTimeout(r, 0))
  await new Promise(r => setTimeout(r, 0))
}

const entryTeam10 = {
  id: 'w-1', teamId: 10, slotId: 501, slotDate: '2026-08-01', startTime: '10:00:00', endTime: '10:30:00', slotTitle: 'Cut', status: 'WAITING',
}
const entryTeam99 = {
  id: 'w-2', teamId: 99, slotId: 502, slotDate: '2026-08-02', startTime: '11:00:00', endTime: '11:30:00', status: 'WAITING',
}

beforeEach(() => {
  mockListMyWaitlist.mockReset()
  mockLeaveWaitlist.mockReset()
  mockNotifySuccess.mockReset()
  mockNotifyError.mockReset()
  mockHandleApiError.mockReset()
})

/**
 * ウォームアップマウント（殿の実測で確定した対処・2026-07-30）。
 *
 * `mountSuspended` の**初回**呼び出しは、当該コンポーネント（および依存ツリー）の
 * transform（esbuild/vite変換）コストをそのテストの `testTimeout`（既定5秒）内で負担する。
 * 環境が重いとき（実測: transform 130〜380秒/ファイル）、この初回コストだけで
 * 1件目のテスト（AC-1）が確定的に5秒を超えて timeout する
 * （2件目以降はコンパイル済みモジュールを再利用するため速い・ロジックの欠陥ではない）。
 *
 * `setupNuxt` 用に既に大きい hookTimeout を持つ `beforeAll` で使い捨てマウントし、
 * transform コストを beforeAll 側に前払いすることで、各 it は既定の testTimeout のまま
 * 安定させる。testTimeout を config 全体で引き上げると他テストの本物の hang を隠すため禁止
 * （殿の指示）。
 */
beforeAll(async () => {
  mockListMyWaitlist.mockResolvedValue({ data: [] })
  const warmup = await mountSuspended(ReservationMyWaitlistList, { props: { teamId: 'warmup' } })
  warmup.unmount()
})

describe('ReservationMyWaitlistList.vue', () => {
  it('AC-1: 全チーム横断の応答から自チーム分のみを表示する', async () => {
    mockListMyWaitlist.mockResolvedValue({ data: [entryTeam10, entryTeam99] })

    const wrapper = await mountSuspended(ReservationMyWaitlistList, {
      props: { teamId: '10' },
    })
    await flush()

    expect(wrapper.text()).toContain('Cut')
    expect(wrapper.text()).toContain('2026-08-01')
    expect(wrapper.text()).not.toContain('2026-08-02')
  })

  it('AC-2: 0件のときは空状態を表示する', async () => {
    mockListMyWaitlist.mockResolvedValue({ data: [] })

    const wrapper = await mountSuspended(ReservationMyWaitlistList, {
      props: { teamId: '10' },
    })
    await flush()

    expect(wrapper.text()).toContain("You're not on any waitlists")
  })

  it('AC-3: 取消ボタンで leaveWaitlist を呼び、成功後に一覧を再読込する', async () => {
    mockListMyWaitlist.mockResolvedValueOnce({ data: [entryTeam10] })
    mockListMyWaitlist.mockResolvedValueOnce({ data: [] })
    mockLeaveWaitlist.mockResolvedValue({})

    const wrapper = await mountSuspended(ReservationMyWaitlistList, {
      props: { teamId: '10' },
    })
    await flush()

    const cancelBtn = wrapper.find('[data-testid="my-waitlist-cancel-w-1"]')
    expect(cancelBtn.exists()).toBe(true)
    await cancelBtn.trigger('click')
    await flush()

    expect(mockLeaveWaitlist).toHaveBeenCalledWith('10', 501)
    expect(mockNotifySuccess).toHaveBeenCalled()
    expect(mockListMyWaitlist).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain("You're not on any waitlists")
  })

  it('AC-4: 取消失敗=RESERVATION_046（対象なし）は専用文言で通知し一覧を再読込する', async () => {
    mockListMyWaitlist.mockResolvedValue({ data: [entryTeam10] })
    mockLeaveWaitlist.mockRejectedValue({ data: { error: { code: 'RESERVATION_046' } } })

    const wrapper = await mountSuspended(ReservationMyWaitlistList, {
      props: { teamId: '10' },
    })
    await flush()

    const cancelBtn = wrapper.find('[data-testid="my-waitlist-cancel-w-1"]')
    await cancelBtn.trigger('click')
    await flush()

    expect(mockNotifyError).toHaveBeenCalled()
    expect(mockListMyWaitlist).toHaveBeenCalledTimes(2)
    // ハンドラの共通経路（handleApiError）は呼ばない（専用分岐で処理済み）
    expect(mockHandleApiError).not.toHaveBeenCalled()
  })
})
