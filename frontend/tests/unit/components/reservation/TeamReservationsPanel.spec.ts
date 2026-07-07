import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { ref } from 'vue'
import TeamReservationsPanel from '~/components/reservation/TeamReservationsPanel.vue'
import ReservationForm from '~/components/reservation/ReservationForm.vue'

/**
 * TeamReservationsPanel.vue ユニットテスト — 予約直後の再読込結線ガード（実機E2E発見バグの根治）
 *
 * 背景:
 *   実機E2E（予約v2第一弾）で、予約確定後に一覧・枠の空き状況が更新されない実バグを発見した。
 *   原因は ReservationForm が emit('reserved') する一方、TeamReservationsPanel が
 *   <ReservationForm @reserved="..."> を結線しておらず、ReservationList（一覧）・SlotPicker（枠）
 *   の再読込がトリガーされていなかったこと（再読込＝タブ切替や再訪問まで放置される）。
 *
 * 観点（AC 対応）:
 *   AC-1: reserved emit 後、SlotPicker の枠再取得（getSlots）が再実行される
 *   AC-2: reserved emit 後、ReservationList の一覧再取得（listMyReservations）が再実行される
 *
 * 注: useRoleAccess を isAdmin=false/isAdminOrDeputy=false に固定し、ADMIN限定タブ
 *     （ライン管理・緊急休業）を DOM に出さない（v-if で最初から存在しないため mount 不要）。
 *     bookDisplayMode は既定 'list' のため SlotPicker のみが枠表示として実マウントされる。
 */
const mockGetReservationSettings = vi.fn()
const mockGetLines = vi.fn()
const mockGetSlots = vi.fn()
const mockListMyReservations = vi.fn()
const mockCreateReservation = vi.fn()

vi.mock('~/composables/useReservationApi', () => ({
  useReservationApi: () => ({
    getReservationSettings: mockGetReservationSettings,
    getLines: mockGetLines,
    getSlots: mockGetSlots,
    listMyReservations: mockListMyReservations,
    createReservation: mockCreateReservation,
  }),
}))

mockNuxtImport('useRoleAccess', () => () => ({
  isAdmin: ref(false),
  isAdminOrDeputy: ref(false),
  isMember: ref(true),
  roleName: ref('MEMBER'),
  loadPermissions: vi.fn().mockResolvedValue({ ok: true }),
}))

mockNuxtImport('useNotification', () => () => ({ success: vi.fn(), error: vi.fn(), warn: vi.fn() }))
mockNuxtImport('useConfirm', () => () => ({ require: vi.fn(), close: vi.fn() }))

const activeLine = { id: 10, meta: { name: 'テスト予約対象', isActive: true } }
const availableSlot = {
  id: 100,
  status: { slotStatus: 'AVAILABLE' },
  basic: { slotDate: '2026-07-10', startTime: '09:00', endTime: '10:00' },
}

beforeEach(() => {
  mockGetReservationSettings.mockReset()
  mockGetLines.mockReset()
  mockGetSlots.mockReset()
  mockListMyReservations.mockReset()
  mockCreateReservation.mockReset()

  mockGetReservationSettings.mockResolvedValue({ data: { allowPublicReservation: true } })
  mockGetLines.mockResolvedValue({ data: [activeLine] })
  mockGetSlots.mockResolvedValue({ data: [availableSlot] })
  mockListMyReservations.mockResolvedValue({ data: [] })
})

describe('TeamReservationsPanel.vue 予約直後の再読込結線', () => {
  it('AC-1/2: ReservationForm の reserved emit で枠(SlotPicker)・一覧(ReservationList)が再読込される', async () => {
    const wrapper = await mountSuspended(TeamReservationsPanel, {
      props: { teamId: 'team-slug' },
    })
    await flushPromises()

    // mount 直後の初回読込回数（SlotPicker は selectedLineId 確定時の watch 発火分を含みうるため
    // 絶対値ではなく「emit 前後の差分」で判定する）。
    const slotsCallsBefore = mockGetSlots.mock.calls.length
    const listCallsBefore = mockListMyReservations.mock.calls.length
    expect(slotsCallsBefore).toBeGreaterThan(0)
    expect(listCallsBefore).toBeGreaterThan(0)

    // ReservationForm は常時 DOM 上に存在する（v-model:visible で開閉するのみ）。
    // 実際のダイアログ操作（Teleport 経由）を介さず、結線対象のイベントを直接発火して
    // 「reserved を受けたら再読込が走る」という結線契約そのものを検証する。
    const form = wrapper.findComponent(ReservationForm)
    expect(form.exists()).toBe(true)
    await form.vm.$emit('reserved')
    await flushPromises()

    // emit 後にそれぞれ最低1回追加で呼ばれていること = @reserved が正しく結線され
    // 枠(SlotPicker)・一覧(ReservationList)の再読込がトリガーされたことの証跡。
    expect(mockGetSlots.mock.calls.length).toBeGreaterThan(slotsCallsBefore)
    expect(mockListMyReservations.mock.calls.length).toBeGreaterThan(listCallsBefore)
  })
})
