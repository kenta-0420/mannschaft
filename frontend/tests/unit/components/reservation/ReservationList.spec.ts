import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import ReservationList from '~/components/reservation/ReservationList.vue'

/**
 * ReservationList.vue（予約一覧）ユニットテスト
 *
 * 観点（マージブロッカーの回帰ガード）:
 *   MINE-001: mine モードで BE GET /reservations/my が meta を持たない { data: [...] } を返しても、
 *             空表示に倒れず自分の予約行が描画される。
 *             （旧実装は両モード共通で res.meta.totalElements を参照 → mine で TypeError →
 *              直後の catch が握り潰して空表示になる実害バグ。ここで再現→green 化する）
 *   MINE-002: mine モードでは予約者名列を描画しない（他人の氏名を漏らさない・情報漏洩の回帰防止）。
 *
 * 注: useReservationApi は明示 import ではなく auto-import 対象のため、モジュールパスを vi.mock で差し替える。
 */
const mockListMyReservations = vi.fn()
const mockListReservations = vi.fn()
const mockConfirmReservation = vi.fn()
const mockCancelReservation = vi.fn()
const mockCancelMyReservation = vi.fn()
const mockConfirmGroup = vi.fn()
const mockCancelGroup = vi.fn()

vi.mock('~/composables/useReservationApi', () => ({
  useReservationApi: () => ({
    listMyReservations: mockListMyReservations,
    listReservations: mockListReservations,
    confirmReservation: mockConfirmReservation,
    cancelReservation: mockCancelReservation,
    cancelMyReservation: mockCancelMyReservation,
    confirmGroup: mockConfirmGroup,
    cancelGroup: mockCancelGroup,
  }),
}))

let confirmAcceptCallback: (() => void | Promise<void>) | null = null
const mockNotifySuccess = vi.fn()
const mockNotifyError = vi.fn()
mockNuxtImport('useNotification', () => () => ({ success: mockNotifySuccess, error: mockNotifyError, warn: vi.fn() }))
mockNuxtImport('useConfirm', () => () => ({
  require: (opts: { accept: () => void | Promise<void> }) => { confirmAcceptCallback = opts.accept },
  close: vi.fn(),
}))
const mockHandleApiError = vi.fn()
mockNuxtImport('useErrorHandler', () => () => ({
  resolveMessage: (code: string) => code,
  handleApiError: mockHandleApiError,
  handleError: mockHandleApiError,
}))

// BE /reservations/my は ApiResponse<List> ＝ { data: [...] }。meta フィールドは存在しない。
const myReservation = {
  id: 1,
  slot: { slotDate: '2026-07-10', startTime: '09:00', endTime: '10:00', lineName: 'テスト予約対象' },
  status: { status: 'CONFIRMED' },
  identifier: { userName: '他人ダミー氏名' },
}

beforeEach(() => {
  mockListMyReservations.mockReset()
  mockListReservations.mockReset()
  mockConfirmReservation.mockReset()
  mockCancelReservation.mockReset()
  mockCancelMyReservation.mockReset()
  mockConfirmGroup.mockReset()
  mockCancelGroup.mockReset()
  mockNotifySuccess.mockReset()
  mockNotifyError.mockReset()
  mockHandleApiError.mockReset()
  confirmAcceptCallback = null
})

describe('ReservationList.vue（mine モード）', () => {
  it('MINE-001: meta 無しの { data: [1件] } でも空にならず予約行が描画される', async () => {
    // meta を意図的に持たせない（実 BE と同一形状）。旧実装ならここで res.meta 参照が例外化した。
    mockListMyReservations.mockResolvedValue({ data: [myReservation] })

    const wrapper = await mountSuspended(ReservationList, {
      props: { teamId: 'team-slug', canManage: false, mode: 'mine' as const },
    })
    await new Promise((r) => setTimeout(r, 0))

    // mine では listMyReservations を呼び、team 用の listReservations は呼ばない。
    expect(mockListMyReservations).toHaveBeenCalledTimes(1)
    expect(mockListReservations).not.toHaveBeenCalled()

    // 予約対象名が描画され、空表示メッセージ（「予約はありません」）は出ない。
    expect(wrapper.html()).toContain('テスト予約対象')
    expect(wrapper.html()).not.toContain('予約はありません')
  })

  it('MINE-002: mine モードでは予約者名（他人氏名）を描画しない', async () => {
    mockListMyReservations.mockResolvedValue({ data: [myReservation] })

    const wrapper = await mountSuspended(ReservationList, {
      props: { teamId: 'team-slug', canManage: false, mode: 'mine' as const },
    })
    await new Promise((r) => setTimeout(r, 0))

    expect(wrapper.html()).not.toContain('他人ダミー氏名')
  })
})

/**
 * F03.4.3 §5.6#10 / 本タスク項目4 — 一覧のグループ表示＋グループ行の操作ルーティング。
 *
 * 観点:
 *   GROUP-001: group（GroupSummaryDto）が非null の行はメニュー名・枠数を併記する
 *   GROUP-002: グループ行のキャンセル確定は cancelGroup を呼ぶ（cancelReservation ではない。
 *              単票キャンセルは 400=RESERVATION_042 で拒否されるため）
 *   GROUP-003: グループ行の承認/却下（PENDING）は confirmGroup/cancelGroup を呼ぶ
 *   GROUP-004: 単枠（group=null）は従来どおり confirmReservation/cancelReservation を呼ぶ
 */
const groupedConfirmed = {
  id: 10,
  slot: { slotDate: '2026-07-10', startTime: '10:00', endTime: '10:30', lineName: '席1' },
  status: { status: 'CONFIRMED' },
  identifier: { userName: '予約者A' },
  group: { groupId: 'grp-uuid-1', groupSize: 2, groupEndTime: '11:00', menuName: 'カット' },
}

const groupedPending = {
  id: 11,
  slot: { slotDate: '2026-07-11', startTime: '10:00', endTime: '10:30', lineName: '席1' },
  status: { status: 'PENDING' },
  identifier: { userName: '予約者B' },
  group: { groupId: 'grp-uuid-2', groupSize: 3, groupEndTime: '11:30', menuName: 'カラー' },
}

const singleConfirmed = {
  id: 12,
  slot: { slotDate: '2026-07-12', startTime: '09:00', endTime: '09:30', lineName: '席2' },
  status: { status: 'CONFIRMED' },
  identifier: { userName: '予約者C' },
  group: null,
}

describe('ReservationList.vue（グループ予約の表示・操作ルーティング）', () => {
  it('GROUP-001: グループ行はメニュー名・枠数を併記し、終了時刻はグループ末尾時刻を表示する', async () => {
    mockListReservations.mockResolvedValue({ data: [groupedConfirmed], meta: { totalElements: 1 } })

    const wrapper = await mountSuspended(ReservationList, {
      props: { teamId: 'team-slug', canManage: true, mode: 'team' as const },
    })
    await new Promise(r => setTimeout(r, 0))

    const html = wrapper.html()
    expect(html).toContain('カット')
    expect(html).toContain('11:00') // group.groupEndTime（単枠の slot.endTime=10:30 ではない）
  })

  it('GROUP-002: CONFIRMED グループ行のキャンセルは cancelGroup を呼ぶ（cancelReservation は呼ばない）', async () => {
    mockListReservations.mockResolvedValue({ data: [groupedConfirmed], meta: { totalElements: 1 } })

    const wrapper = await mountSuspended(ReservationList, {
      props: { teamId: 'team-slug', canManage: true, mode: 'team' as const },
    })
    await new Promise(r => setTimeout(r, 0))

    const cancelBtn = wrapper.findAll('button').find(b => b.find('.pi-ban').exists())
    expect(cancelBtn).toBeTruthy()
    await cancelBtn!.trigger('click')

    expect(confirmAcceptCallback).toBeTruthy()
    await confirmAcceptCallback!()

    expect(mockCancelGroup).toHaveBeenCalledWith('team-slug', 'grp-uuid-1')
    expect(mockCancelReservation).not.toHaveBeenCalled()
  })

  it('GROUP-003: PENDING グループ行の承認/却下は confirmGroup/cancelGroup を呼ぶ', async () => {
    mockListReservations.mockResolvedValue({ data: [groupedPending], meta: { totalElements: 1 } })

    const wrapper = await mountSuspended(ReservationList, {
      props: { teamId: 'team-slug', canManage: true, mode: 'team' as const },
    })
    await new Promise(r => setTimeout(r, 0))

    const approveBtn = wrapper.findAll('button').find(b => b.find('.pi-check').exists())
    expect(approveBtn).toBeTruthy()
    await approveBtn!.trigger('click')
    await new Promise(r => setTimeout(r, 0))

    expect(mockConfirmGroup).toHaveBeenCalledWith('team-slug', 'grp-uuid-2')
    expect(mockConfirmReservation).not.toHaveBeenCalled()

    const rejectBtn = wrapper.findAll('button').find(b => b.find('.pi-times').exists())
    expect(rejectBtn).toBeTruthy()
    await rejectBtn!.trigger('click')
    await new Promise(r => setTimeout(r, 0))

    expect(mockCancelGroup).toHaveBeenCalledWith('team-slug', 'grp-uuid-2', expect.any(String))
    expect(mockCancelReservation).not.toHaveBeenCalled()
  })

  it('GROUP-004: 単枠（group=null）は従来どおり confirmReservation/cancelReservation を呼ぶ', async () => {
    mockListReservations.mockResolvedValue({ data: [singleConfirmed], meta: { totalElements: 1 } })

    const wrapper = await mountSuspended(ReservationList, {
      props: { teamId: 'team-slug', canManage: true, mode: 'team' as const },
    })
    await new Promise(r => setTimeout(r, 0))

    const cancelBtn = wrapper.findAll('button').find(b => b.find('.pi-ban').exists())
    expect(cancelBtn).toBeTruthy()
    await cancelBtn!.trigger('click')

    expect(confirmAcceptCallback).toBeTruthy()
    await confirmAcceptCallback!()

    expect(mockCancelReservation).toHaveBeenCalledWith('team-slug', 12)
    expect(mockCancelGroup).not.toHaveBeenCalled()
  })
})

/**
 * 本人自己キャンセル（mine モード）— 検分で発見された機能穴の根治ガード。
 *
 * 背景: BE には本人キャンセル API（POST /api/v1/reservations/{id}/cancel = cancelByUser・
 * 期限 026 ガード。グループは cancelGroup が「本人=締切内/ADMIN=常時」を許可）が実在するのに、
 * FE の mine モード（canManage=false）は操作列ごと非表示でキャンセル手段が存在しなかった。
 * 本スイートは mine モードのキャンセル UI 結線を固定し、操作列非表示への後退を防ぐ。
 *
 * 観点:
 *   MYCANCEL-001: mine モードで PENDING/CONFIRMED 行にキャンセルボタンが表示され、
 *                 CANCELLED 行には表示されない
 *   MYCANCEL-002: 単枠行は cancelMyReservation（共通API・cancelByUser）を呼ぶ
 *                 （team スコープの cancelReservation は管理者専用のため呼ばない）。
 *                 グループ行は cancelGroup を呼ぶ（単票は 400=RESERVATION_042 のため）
 *   MYCANCEL-003: 期限超過（RESERVATION_026）はエラーを握りつぶさず、
 *                 丁寧な文言（cancel_deadline_passed）で通知する
 */
const myPending = {
  id: 20,
  slot: { slotDate: '2026-07-15', startTime: '09:00', endTime: '09:30', lineName: '席1' },
  status: { status: 'PENDING' },
  group: null,
}

const myCancelled = {
  id: 21,
  slot: { slotDate: '2026-07-16', startTime: '09:00', endTime: '09:30', lineName: '席1' },
  status: { status: 'CANCELLED' },
  group: null,
}

const myGroupConfirmed = {
  id: 22,
  slot: { slotDate: '2026-07-17', startTime: '10:00', endTime: '10:30', lineName: '席1' },
  status: { status: 'CONFIRMED' },
  group: { groupId: 'grp-uuid-mine', groupSize: 2, groupEndTime: '11:00', menuName: 'カット' },
}

describe('ReservationList.vue（mine モード・本人自己キャンセル）', () => {
  it('MYCANCEL-001: PENDING/CONFIRMED 行にキャンセルボタンが出て、CANCELLED 行には出ない', async () => {
    mockListMyReservations.mockResolvedValue({ data: [myPending, myReservation, myCancelled] })

    const wrapper = await mountSuspended(ReservationList, {
      props: { teamId: 'team-slug', canManage: false, mode: 'mine' as const },
    })
    await new Promise(r => setTimeout(r, 0))

    // PENDING(1件) + CONFIRMED(1件) = 2つ。CANCELLED 行には出ない。
    const cancelButtons = wrapper.findAll('[data-testid="my-reservation-cancel"]')
    expect(cancelButtons.length).toBe(2)
  })

  it('MYCANCEL-002: 単枠行は cancelMyReservation・グループ行は cancelGroup を呼ぶ（team用cancelReservationは呼ばない）', async () => {
    mockCancelMyReservation.mockResolvedValue({})
    mockCancelGroup.mockResolvedValue({ data: {} })
    mockListMyReservations.mockResolvedValue({ data: [myPending, myGroupConfirmed] })

    const wrapper = await mountSuspended(ReservationList, {
      props: { teamId: 'team-slug', canManage: false, mode: 'mine' as const },
    })
    await new Promise(r => setTimeout(r, 0))

    const cancelButtons = wrapper.findAll('[data-testid="my-reservation-cancel"]')
    expect(cancelButtons.length).toBe(2)

    // 単枠行（myPending・id=20）: 共通API cancelMyReservation（引数は reservationId のみ）
    await cancelButtons[0]!.trigger('click')
    expect(confirmAcceptCallback).toBeTruthy()
    await confirmAcceptCallback!()
    expect(mockCancelMyReservation).toHaveBeenCalledWith(20)

    // グループ行（myGroupConfirmed）: グループAPI cancelGroup
    confirmAcceptCallback = null
    await cancelButtons[1]!.trigger('click')
    expect(confirmAcceptCallback).toBeTruthy()
    await confirmAcceptCallback!()
    expect(mockCancelGroup).toHaveBeenCalledWith('team-slug', 'grp-uuid-mine')

    // 管理者専用の team スコープ cancelReservation は一切呼ばれない
    expect(mockCancelReservation).not.toHaveBeenCalled()
  })

  it('MYCANCEL-003: 期限超過（RESERVATION_026）は cancel_deadline_passed の丁寧な文言で通知する', async () => {
    mockCancelMyReservation.mockRejectedValue({
      data: { error: { code: 'RESERVATION_026' } },
    })
    mockListMyReservations.mockResolvedValue({ data: [myPending] })

    const wrapper = await mountSuspended(ReservationList, {
      props: { teamId: 'team-slug', canManage: false, mode: 'mine' as const },
    })
    await new Promise(r => setTimeout(r, 0))

    const cancelBtn = wrapper.find('[data-testid="my-reservation-cancel"]')
    expect(cancelBtn.exists()).toBe(true)
    await cancelBtn.trigger('click')
    expect(confirmAcceptCallback).toBeTruthy()
    await confirmAcceptCallback!()

    // 026 は専用文言（テスト環境の既定ロケールは en）。成功トーストは出ず、
    // 汎用ハンドラ（handleApiError）にも回さない。
    expect(mockNotifyError).toHaveBeenCalledTimes(1)
    const message = String(mockNotifyError.mock.calls[0]?.[0] ?? '')
    expect(message).toContain('cancellation deadline has passed')
    expect(mockNotifySuccess).not.toHaveBeenCalled()
    expect(mockHandleApiError).not.toHaveBeenCalled()
  })
})
