import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { ref } from 'vue'
import dayjs from 'dayjs'
import SlotMatrixPicker from '~/components/reservation/SlotMatrixPicker.vue'

/**
 * SlotMatrixPicker.vue（F03.4.4 マトリックスUI・機能H）ユニットテスト — 番人
 *
 * 観点（AC 対応）:
 *   AC-1: axis=LINE のレンジ呼び（from/to）でグリッドAPIを叩く（機能C の date 単日呼びとは別経路）
 *   AC-2: 予約対象ゼロは対象作成の空状態を表示する
 *   AC-3: 30分セル（span=1・AVAILABLE）クリックでメニュー選択ダイアログが開く（GroupBookingDialog）
 *   AC-4: 長尺手動枠（span>1・colspan跨ぎ描画）クリックは slotSelected を emit する（グループダイアログを開かない）
 *   AC-5: モバイル規約: 縦横スクロールコンテナに overscroll-contain が付与される（UX改善5点の4で縦横統合）
 *   AC-6: 時間ヘッダ行 sticky top・左上交差セル両軸 sticky（UX改善5点の4・マトリックス時間ヘッダsticky化）
 *
 * 注: テスト環境の既定ロケールは en。日付依存の flaky を避けるため、返す日は常に「明日」にする
 *     （isPastCell の過去判定に一切かからない・実行時刻に依存しない）。
 */
const mockGetLines = vi.fn()
const mockGetMenus = vi.fn()
const mockGetSlotGrid = vi.fn()

vi.mock('~/composables/useReservationApi', () => ({
  useReservationApi: () => ({
    getLines: mockGetLines,
    getMenus: mockGetMenus,
    getSlotGrid: mockGetSlotGrid,
  }),
}))

mockNuxtImport('useDatetime', () => () => ({ userTimezone: ref('Asia/Tokyo') }))

const activeLine = { id: 1, meta: { name: 'Seat1', isActive: true } }
const tomorrow = dayjs().add(1, 'day').format('YYYY-MM-DD')

function gridResponseWithCells() {
  return {
    data: {
      axis: 'LINE',
      meta: null,
      days: [
        {
          date: tomorrow,
          columns: [
            {
              lineId: 1,
              lineName: 'Seat1',
              lineIds: [],
              cells: [
                { slotId: 201, startTime: '10:00', endTime: '10:30', state: 'AVAILABLE' },
                { slotId: 202, startTime: '10:30', endTime: '11:30', state: 'AVAILABLE' }, // 60分・span=2
              ],
            },
            { lineId: null, lineName: null, lineIds: [], cells: [] },
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

function findByTestId<T extends Element = HTMLElement>(testId: string): T | null {
  return document.body.querySelector<T>(`[data-testid="${testId}"]`)
}

beforeEach(() => {
  mockGetLines.mockReset()
  mockGetMenus.mockReset()
  mockGetSlotGrid.mockReset()
  mockGetMenus.mockResolvedValue({ data: [] })
})

afterEach(() => {
  document.body.querySelectorAll('.p-dialog').forEach(el => el.remove())
})

describe('SlotMatrixPicker.vue', () => {
  it('AC-1: axis=LINE の from/to レンジ呼びでグリッドAPIを叩く', async () => {
    mockGetLines.mockResolvedValue({ data: [activeLine] })
    mockGetSlotGrid.mockResolvedValue(gridResponseWithCells())

    await mountSuspended(SlotMatrixPicker, {
      props: { teamId: 'team-slug', isAdmin: false },
    })
    await flush()

    expect(mockGetSlotGrid).toHaveBeenCalled()
    const [teamId, params] = mockGetSlotGrid.mock.calls[0] as [string, Record<string, unknown>]
    expect(teamId).toBe('team-slug')
    expect(params.axis).toBe('LINE')
    expect(params.from).toBeTruthy()
    expect(params.to).toBeTruthy()
    expect(params.date).toBeUndefined()
  })

  it('AC-2: 予約対象ゼロは対象作成の空状態を表示する', async () => {
    mockGetLines.mockResolvedValue({ data: [] })
    mockGetSlotGrid.mockResolvedValue({ data: { axis: 'LINE', days: [] } })

    const wrapper = await mountSuspended(SlotMatrixPicker, {
      props: { teamId: 'team-slug', isAdmin: true },
    })
    await flush()

    expect(wrapper.html()).toContain('No reservation targets yet')
  })

  it('AC-3: 30分セル（span=1・AVAILABLE）クリックでメニュー選択ダイアログが開く', async () => {
    mockGetLines.mockResolvedValue({ data: [activeLine] })
    mockGetSlotGrid.mockResolvedValue(gridResponseWithCells())

    const wrapper = await mountSuspended(SlotMatrixPicker, {
      props: { teamId: 'team-slug', isAdmin: false },
    })
    await flush()

    // 10:00 の30分セル（span=1）をクリック
    const cell10 = wrapper.findAll('button').find(b => b.attributes('aria-label')?.includes('10:00'))
    expect(cell10).toBeTruthy()
    await cell10!.trigger('click')
    await flush()

    // GroupBookingDialog が開き「メニューなしで30分だけ予約」ボタンが描画される
    expect(findByTestId('group-no-menu')).not.toBeNull()
    expect(wrapper.emitted('slotSelected')).toBeFalsy()
  })

  it('AC-4: 長尺手動枠（span>1）クリックは slotSelected を emit し、グループダイアログを開かない', async () => {
    mockGetLines.mockResolvedValue({ data: [activeLine] })
    mockGetSlotGrid.mockResolvedValue(gridResponseWithCells())

    const wrapper = await mountSuspended(SlotMatrixPicker, {
      props: { teamId: 'team-slug', isAdmin: false },
    })
    await flush()

    // 10:30-11:30（60分・span=2）セルをクリック
    const longCell = wrapper.findAll('button').find(b => b.attributes('aria-label')?.includes('10:30'))
    expect(longCell).toBeTruthy()
    await longCell!.trigger('click')
    await flush()

    expect(wrapper.emitted('slotSelected')).toBeTruthy()
    const payload = wrapper.emitted('slotSelected')![0]
    expect(payload).toEqual([202, 1, 'Seat1', tomorrow, '10:30', '11:30'])
    // 単枠フローは親のReservationFormへ委譲するため、グループダイアログ内の要素は出ない
    expect(findByTestId('group-no-menu')).toBeNull()
  })

  it('AC-5: 縦横スクロールコンテナに overscroll-contain が付与される（縦→横ホイール変換は実装しない。UX改善5点の4で縦スクロールも同一コンテナに統合）', async () => {
    mockGetLines.mockResolvedValue({ data: [activeLine] })
    mockGetSlotGrid.mockResolvedValue(gridResponseWithCells())

    const wrapper = await mountSuspended(SlotMatrixPicker, {
      props: { teamId: 'team-slug', isAdmin: false },
    })
    await flush()

    expect(wrapper.html()).toContain('overscroll-contain')
  })

  it('AC-6（UX改善5点の4）: 時間ヘッダ行が sticky top、左上の交差セルが両軸 sticky で higher z-index を持つ', async () => {
    mockGetLines.mockResolvedValue({ data: [activeLine] })
    mockGetSlotGrid.mockResolvedValue(gridResponseWithCells())

    const wrapper = await mountSuspended(SlotMatrixPicker, {
      props: { teamId: 'team-slug', isAdmin: false },
    })
    await flush()

    const html = wrapper.html()
    // 左上コーナー: 左右上下の両軸 sticky（left-0 と top-0 の両方）で最前面（z-20）
    expect(html).toMatch(/sticky left-0 top-0 z-20/)
    // 時間ヘッダセル: 上方向 sticky（top-0）
    expect(html).toMatch(/sticky top-0 z-10/)
    // 左の行ヘッダ列（日付×予約対象）は既存どおり left-0 sticky を維持
    expect(html).toMatch(/sticky left-0 z-10/)
  })
})
