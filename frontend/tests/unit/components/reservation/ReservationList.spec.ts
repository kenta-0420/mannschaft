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

vi.mock('~/composables/useReservationApi', () => ({
  useReservationApi: () => ({
    listMyReservations: mockListMyReservations,
    listReservations: mockListReservations,
    confirmReservation: vi.fn(),
    cancelReservation: vi.fn(),
  }),
}))

mockNuxtImport('useNotification', () => () => ({ success: vi.fn(), error: vi.fn(), warn: vi.fn() }))
mockNuxtImport('useConfirm', () => () => ({ require: vi.fn(), close: vi.fn() }))

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
