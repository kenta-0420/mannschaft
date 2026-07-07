import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { ref } from 'vue'
import TeamReservationsPanel from '~/components/reservation/TeamReservationsPanel.vue'
import ReservationForm from '~/components/reservation/ReservationForm.vue'
import SlotMatrixPicker from '~/components/reservation/SlotMatrixPicker.vue'
import SlotPicker from '~/components/reservation/SlotPicker.vue'
import SlotGridPicker from '~/components/reservation/SlotGridPicker.vue'

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
 *   AC-3（F03.4.4 追加）: 表示選好 localStorage が未設定の場合、既定タブは SlotMatrixPicker（マトリックス）
 *
 * 注: useRoleAccess を isAdmin=false/isAdminOrDeputy=false に固定し、ADMIN限定タブ
 *     （ライン管理・緊急休業）を DOM に出さない（v-if で最初から存在しないため mount 不要）。
 *     AC-1/2 は SlotPicker 固有の再読込結線を検証する観点のため、localStorage に
 *     表示選好 'list' を事前設定して SlotPicker を実マウントさせる（F03.4.4 で既定が
 *     'matrix' へ変わったため。§5.4 の localStorage 記憶方針に基づく明示的な選好切替）。
 */
const mockGetReservationSettings = vi.fn()
const mockGetLines = vi.fn()
const mockGetSlots = vi.fn()
const mockGetSlotGrid = vi.fn()
const mockGetMenus = vi.fn()
const mockListMyReservations = vi.fn()
const mockCreateReservation = vi.fn()

vi.mock('~/composables/useReservationApi', () => ({
  useReservationApi: () => ({
    getReservationSettings: mockGetReservationSettings,
    getLines: mockGetLines,
    getSlots: mockGetSlots,
    getSlotGrid: mockGetSlotGrid,
    getMenus: mockGetMenus,
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
  mockGetSlotGrid.mockReset()
  mockGetMenus.mockReset()
  mockListMyReservations.mockReset()
  mockCreateReservation.mockReset()
  localStorage.clear()

  mockGetReservationSettings.mockResolvedValue({ data: { allowPublicReservation: true } })
  mockGetLines.mockResolvedValue({ data: [activeLine] })
  mockGetSlots.mockResolvedValue({ data: [availableSlot] })
  mockGetSlotGrid.mockResolvedValue({ data: { axis: 'LINE', days: [] } })
  mockGetMenus.mockResolvedValue({ data: [] })
  mockListMyReservations.mockResolvedValue({ data: [] })
})

describe('TeamReservationsPanel.vue 予約直後の再読込結線', () => {
  it('AC-1/2: ReservationForm の reserved emit で枠(SlotPicker)・一覧(ReservationList)が再読込される', async () => {
    // F03.4.4 で既定タブが matrix へ変わったため、本 AC は SlotPicker 固有の結線検証を
    // 継続するために表示選好を明示的に 'list' へ切り替える。
    localStorage.setItem('mannschaft.reservation.bookDisplayMode', 'list')
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

  it('AC-3（F03.4.4）: 表示選好が未設定なら既定タブは SlotMatrixPicker（マトリックス）で、grid/list はマウントされない', async () => {
    const wrapper = await mountSuspended(TeamReservationsPanel, {
      props: { teamId: 'team-slug' },
    })
    await flushPromises()

    expect(wrapper.findComponent(SlotMatrixPicker).exists()).toBe(true)
    expect(wrapper.findComponent(SlotPicker).exists()).toBe(false)
    expect(wrapper.findComponent(SlotGridPicker).exists()).toBe(false)
    // マトリックスは axis=LINE のレンジ呼びでグリッドAPIを叩く（機能C の date 単日呼びとは別経路）
    expect(mockGetSlotGrid).toHaveBeenCalled()
  })

  it('AC-4（F03.4.4）: SlotMatrixPicker の reserved emit で一覧(ReservationList)が再読込される', async () => {
    const wrapper = await mountSuspended(TeamReservationsPanel, {
      props: { teamId: 'team-slug' },
    })
    await flushPromises()

    const listCallsBefore = mockListMyReservations.mock.calls.length
    expect(listCallsBefore).toBeGreaterThan(0)

    const matrix = wrapper.findComponent(SlotMatrixPicker)
    expect(matrix.exists()).toBe(true)
    await matrix.vm.$emit('reserved')
    await flushPromises()

    expect(mockListMyReservations.mock.calls.length).toBeGreaterThan(listCallsBefore)
  })
})
