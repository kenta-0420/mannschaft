import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import ReservationForm from '~/components/reservation/ReservationForm.vue'

/**
 * ReservationForm.vue（単枠予約確認ダイアログ・F03.4.5 §6.3/§6.4 W2-6-FE）ユニットテスト — 番人
 *
 * 観点（AC 対応）:
 *   AC-1（仮押さえ自動失効の会員向け注意書き）: 承認モード=MANUAL かつ pendingExpireHours が
 *        非NULLのとき「{n}時間以内に承認されない場合は自動的にキャンセルされます」を表示する
 *   AC-2: pendingExpireHours が NULL（自動失効なし設定）のときは注意書きを表示しない
 *        （出すと「自動キャンセルされる」という誤情報になるため）
 *   AC-3: 承認モード=AUTO のチームは注意書きを表示しない（仮押さえが発生しないため無意味）
 *   AC-4（回帰）: 429=RESERVATION_053 受信時は専用文言のトーストを表示する
 *
 * 注: テスト環境の既定ロケールは en。Dialog は Teleport で document.body にレンダリングされる。
 * GET /reservation-settings は ADMIN 限定ではなく view ゲート（会員/公開）のため、会員側の
 * ReservationForm からも取得できる（`ReservationBusinessHourController` の viewAccessGuard Javadoc）。
 */
const mockCreateReservation = vi.fn()
const mockGetReservationSettings = vi.fn()

vi.mock('~/composables/useReservationApi', () => ({
  useReservationApi: () => ({
    createReservation: mockCreateReservation,
    getReservationSettings: mockGetReservationSettings,
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

function findByTestId<T extends Element = HTMLElement>(testId: string): T | null {
  return document.body.querySelector<T>(`[data-testid="${testId}"]`)
}

async function flush() {
  await new Promise(r => setTimeout(r, 0))
  await new Promise(r => setTimeout(r, 0))
}

const baseProps = {
  teamId: 'team-slug',
  slotId: 1,
  lineId: 1,
  lineName: 'Seat1',
  date: '2026-08-01',
  startTime: '10:00',
  endTime: '10:30',
  visible: true,
}

beforeEach(() => {
  mockCreateReservation.mockReset()
  mockGetReservationSettings.mockReset()
  mockNotifySuccess.mockReset()
  mockNotifyError.mockReset()
})

afterEach(() => {
  document.body.querySelectorAll('.p-dialog').forEach(el => el.remove())
})

describe('ReservationForm.vue（仮押さえ自動失効の会員向け注意書き・W2-6-FE）', () => {
  it('AC-1: MANUAL かつ pendingExpireHours 非NULL のとき注意書きを表示する', async () => {
    mockGetReservationSettings.mockResolvedValue({
      data: { approvalMode: 'MANUAL', pendingExpireHours: 24 },
    })

    await mountSuspended(ReservationForm, { props: baseProps })
    await flush()

    const notice = findByTestId('pending-expire-notice')
    expect(notice).not.toBeNull()
    expect(notice!.textContent).toContain('24')
  })

  it('AC-2: pendingExpireHours が NULL（自動失効なし設定）のときは注意書きを表示しない', async () => {
    mockGetReservationSettings.mockResolvedValue({
      data: { approvalMode: 'MANUAL', pendingExpireHours: null },
    })

    await mountSuspended(ReservationForm, { props: baseProps })
    await flush()

    expect(findByTestId('pending-expire-notice')).toBeNull()
  })

  it('AC-3: 承認モード=AUTO のチームは注意書きを表示しない', async () => {
    mockGetReservationSettings.mockResolvedValue({
      data: { approvalMode: 'AUTO', pendingExpireHours: 24 },
    })

    await mountSuspended(ReservationForm, { props: baseProps })
    await flush()

    expect(findByTestId('pending-expire-notice')).toBeNull()
  })

  it('AC-4（回帰）: 429=RESERVATION_053 受信時は専用文言のトーストを表示する', async () => {
    mockGetReservationSettings.mockResolvedValue({ data: { approvalMode: 'AUTO', pendingExpireHours: 24 } })
    mockCreateReservation.mockRejectedValue({ data: { error: { code: 'RESERVATION_053' } } })

    const wrapper = await mountSuspended(ReservationForm, { props: baseProps })
    await flush()

    const reserveBtn = wrapper.findAllComponents({ name: 'Button' }).find(b => b.props('label') === 'Reserve')
    expect(reserveBtn).toBeTruthy()
    await reserveBtn!.trigger('click')
    await flush()

    expect(mockNotifyError).toHaveBeenCalledWith("You're creating reservations too quickly. Please wait a moment and try again")
  })
})
