import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { ref } from 'vue'
import dayjs from 'dayjs'
import SlotGridPicker from '~/components/reservation/SlotGridPicker.vue'

/**
 * SlotGridPicker.vue（機能C 空きグリッド・スタッフ別）ユニットテスト — 番人
 *
 * 観点（AC 対応）:
 *   AC-1（検分是正・2026-07-30）: 日付/表示モードを切り替えてグリッドを再取得したあとも、
 *        その日の登録済み枠が「待機中」として認識される（`loadMyWaitlist` の呼び忘れで日付移動後に
 *        「待機中」表示が消え、登録ボタンを押すと409になる実バグの回帰防止・SlotMatrixPicker と同型）
 *
 * 注: テスト環境の既定ロケールは en。
 */
const mockGetLines = vi.fn()
const mockGetSlotGrid = vi.fn()
const mockListMyWaitlist = vi.fn()

vi.mock('~/composables/useReservationApi', () => ({
  useReservationApi: () => ({
    getLines: mockGetLines,
    getSlotGrid: mockGetSlotGrid,
    listMyWaitlist: mockListMyWaitlist,
  }),
}))

mockNuxtImport('useDatetime', () => () => ({ userTimezone: ref('Asia/Tokyo') }))

const activeLine = { id: 1, meta: { name: 'Seat1', isActive: true } }
const tomorrow = dayjs().add(1, 'day').format('YYYY-MM-DD')

/** 満席（BOOKED）セル1枠のみを持つグリッド応答を作る。 */
function bookedGridResponse(slotId: number) {
  return {
    data: {
      axis: 'STAFF',
      columns: [
        {
          staffUserId: 1,
          staffName: 'Seat1',
          lineIds: [1],
          cells: [
            { slotId, startTime: '10:00', endTime: '10:30', state: 'BOOKED' },
          ],
        },
      ],
    },
  }
}

async function flush() {
  await new Promise(r => setTimeout(r, 0))
  await new Promise(r => setTimeout(r, 0))
}

beforeEach(() => {
  mockGetLines.mockReset()
  mockGetSlotGrid.mockReset()
  mockListMyWaitlist.mockReset()
  mockGetLines.mockResolvedValue({ data: [activeLine] })
  mockListMyWaitlist.mockResolvedValue({ data: [] })
})

afterEach(() => {
  document.body.querySelectorAll('.p-dialog').forEach(el => el.remove())
})

describe('SlotGridPicker.vue', () => {
  it('AC-1（検分是正）: 日付を移動すると新しい日の登録済み枠が「待機中」として認識される', async () => {
    // 自分の WAITING は slotId=902（初期表示の日にはまだ現れない・移動後の日に現れる）のみ。
    mockListMyWaitlist.mockResolvedValue({ data: [{ id: 'w-1', teamId: 999, slotId: 902, status: 'WAITING' }] })

    // 初期表示: slotId=901（BOOKED・未登録）のみを含む。
    // 注: mockResolvedValueOnce ではなく persistent な mockResolvedValue にする
    // （初期 mount 中の呼び出し回数に依存しないテストにするため・SlotMatrixPicker.spec.ts と同方針）。
    mockGetSlotGrid.mockResolvedValue(bookedGridResponse(901))

    const wrapper = await mountSuspended(SlotGridPicker, {
      props: { teamId: 'team-slug', isAdmin: false },
    })
    await flush()

    // 初期表示の応答には登録先(902)が含まれないため「待機中」は出ない（901はBOOKEDのまま=Full表示）
    expect(wrapper.text()).not.toContain('Waiting')
    expect(wrapper.text()).toContain('Full')

    // 日付を移動: 以後の全呼び出しを slotId=902（BOOKED・登録済み）を含む応答に切り替える
    mockGetSlotGrid.mockResolvedValue(bookedGridResponse(902))

    const datePicker = wrapper.findComponent({ name: 'DatePicker' })
    expect(datePicker.exists(), 'DatePicker が見つかること').toBe(true)
    await datePicker.vm.$emit('update:modelValue', dayjs(tomorrow).add(1, 'day').toDate())
    await flush()

    // loadMyWaitlist がグリッド再取得後に呼ばれ、902 が loadedSlotIds に含まれるようになったことで
    // セルの表示が「待機中」に切り替わる（呼び忘れがあれば表示されない＝このアサーションが落ちる）。
    // 凡例に常時「Full」ラベルが出るため、ページ全体に「Full」が無いことではなく
    // 「Waiting」セルが実際に出現したことで判定する。
    expect(wrapper.text()).toContain('Waiting')
  })
})
