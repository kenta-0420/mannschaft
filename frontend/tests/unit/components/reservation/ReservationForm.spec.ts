import { describe, it, expect, vi, beforeEach, beforeAll, afterEach } from 'vitest'
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
 *
 * `findByTestId` は `document.body.querySelector` を使う（PrimeVue Dialog は Teleport 先が
 * `body` 直下のため。GroupBookingDialog.spec.ts と同じ確立された方式）。teleport 残骸対策は
 * `beforeAll` ウォームアップ（transform timeout の根絶）＋ `afterEach` での明示的
 * `wrapper.unmount()` の二重防御で行う。
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

/**
 * 直近でマウントした wrapper（afterEach で明示的に unmount するための追跡用）。
 * `mountSuspended` はジェネリック関数のため `ReturnType<typeof mountSuspended>` は
 * 具体型を失い `.findAllComponents().find()` のコールバック引数が implicit any になる
 * （typecheck で実際に検出・2026-07-30）。ダックタイピングの最小インターフェースに留め、
 * 各テストでは `mountSuspended` 呼び出しの戻り値をそのままローカル変数で受けて具体型を保つ。
 */
let currentWrapper: { unmount: () => void } | null = null

beforeEach(() => {
  mockCreateReservation.mockReset()
  mockGetReservationSettings.mockReset()
  mockNotifySuccess.mockReset()
  mockNotifyError.mockReset()
})

afterEach(() => {
  // teleport 残骸対策の二重防御: Vue 側のライフサイクルを確実に畳んだうえで（unmount）、
  // 万一 DOM に残っても除去する（querySelector 除去）。
  currentWrapper?.unmount()
  currentWrapper = null
  document.body.querySelectorAll('.p-dialog').forEach(el => el.remove())
})

/**
 * ウォームアップマウント（殿の実測・家老の実走で確定した対処・2026-07-30是正）。
 *
 * `mountSuspended` の**初回**呼び出しは、当該コンポーネントの transform（esbuild/vite変換）コストを
 * そのテストの `testTimeout`（既定5秒）内で負担する。環境が重いと、この初回コストだけで1件目の
 * テストが確定的に5秒を超えて timeout する（2件目以降はコンパイル済みモジュールを再利用するため
 * 速い・ロジックの欠陥ではない。`ReservationMyWaitlistList.spec.ts` と同一の対処）。
 * `setupNuxt` 用に既に大きい hookTimeout を持つ `beforeAll` で使い捨てマウントし、transform コストを
 * 前払いすることで各 it は既定の testTimeout のまま安定させる。
 */
beforeAll(async () => {
  mockGetReservationSettings.mockResolvedValue({ data: { approvalMode: 'AUTO', pendingExpireHours: 24 } })
  const warmup = await mountSuspended(ReservationForm, { props: baseProps })
  warmup.unmount()
})

describe('ReservationForm.vue（仮押さえ自動失効の会員向け注意書き・W2-6-FE）', () => {
  it('AC-1: MANUAL かつ pendingExpireHours 非NULL のとき注意書きを表示する', async () => {
    mockGetReservationSettings.mockResolvedValue({
      data: { approvalMode: 'MANUAL', pendingExpireHours: 24 },
    })

    const wrapper = await mountSuspended(ReservationForm, { props: baseProps })
    currentWrapper = wrapper
    await flush()

    const notice = findByTestId('pending-expire-notice')
    expect(notice).not.toBeNull()
    expect(notice!.textContent).toContain('24')
  })

  it('AC-2: pendingExpireHours が NULL（自動失効なし設定）のときは注意書きを表示しない', async () => {
    mockGetReservationSettings.mockResolvedValue({
      data: { approvalMode: 'MANUAL', pendingExpireHours: null },
    })

    const wrapper = await mountSuspended(ReservationForm, { props: baseProps })
    currentWrapper = wrapper
    await flush()

    expect(findByTestId('pending-expire-notice')).toBeNull()
  })

  it('AC-3: 承認モード=AUTO のチームは注意書きを表示しない', async () => {
    mockGetReservationSettings.mockResolvedValue({
      data: { approvalMode: 'AUTO', pendingExpireHours: 24 },
    })

    const wrapper = await mountSuspended(ReservationForm, { props: baseProps })
    currentWrapper = wrapper
    await flush()

    expect(findByTestId('pending-expire-notice')).toBeNull()
  })

  it('AC-4（回帰）: 429=RESERVATION_053 受信時は専用文言のトーストを表示する', async () => {
    mockGetReservationSettings.mockResolvedValue({ data: { approvalMode: 'AUTO', pendingExpireHours: 24 } })
    mockCreateReservation.mockRejectedValue({ data: { error: { code: 'RESERVATION_053' } } })

    const wrapper = await mountSuspended(ReservationForm, { props: baseProps })
    currentWrapper = wrapper
    await flush()

    const reserveBtn = wrapper.findAllComponents({ name: 'Button' }).find(b => b.props('label') === 'Reserve')
    expect(reserveBtn).toBeTruthy()
    await reserveBtn!.trigger('click')
    await flush()

    expect(mockNotifyError).toHaveBeenCalledWith("You're creating reservations too quickly. Please wait a moment and try again")
  })
})
