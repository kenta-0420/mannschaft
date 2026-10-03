import { defineComponent, h } from 'vue'
import { afterEach, describe, expect, it } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import CalendarWeekGrid from '~/components/schedule/CalendarWeekGrid.vue'
import type { CalendarEventItem } from '~/composables/useCalendarEvents'

/**
 * F03.19 §6.7 アクセシビリティ（AC-25 / AC-25b・W3-a4）。
 *
 * キーボード経路は**ドラッグ経路と同じ `rangeSelect` に落ちる**ことが要件（§6.7.1 末尾）なので、
 * ここでは両経路を同じテスト内で走らせて **emit の同値**を直接比べる。
 * 「キーボードでも動く」だけでは、別系統の状態が生まれて片方だけずれる事故を検出できない。
 *
 * テスト環境の既定ロケールは en（既存 rangeSelect スペックと同じ前提）。
 * 読み上げ文言が locale ファイル経由であることは、en の訳文が出ることで証明される。
 */

mockNuxtImport('useDatetime', () => () => ({ userTimezone: { value: 'Asia/Tokyo' } }))
mockNuxtImport('useHolidays', () => () => ({ getHoliday: () => null }))

const WEEK_START = '2026-08-02'

function ev(over: Partial<CalendarEventItem> & { id: number; startAt: string; endAt: string }): CalendarEventItem {
  return {
    uniqueKey: String(over.id),
    title: `予定${over.id}`,
    allDay: false,
    color: '#2563EB',
    isPersonal: true,
    scopeType: 'PERSONAL',
    ...over,
  } as CalendarEventItem
}

const PopoverStub = defineComponent({
  setup(_props, { slots, expose }) {
    expose({ show: () => {}, hide: () => {} })
    return () => slots.default?.()
  },
})

const ScheduleListRowStub = defineComponent({
  props: { event: { type: Object as () => CalendarEventItem, required: true } },
  emits: ['open'],
  setup(props, { emit }) {
    return () => h('button', { 'type': 'button', 'onClick': () => emit('open', props.event.id) }, props.event.title)
  },
})

// ---- 実寸のジオメトリ（jsdom は getBoundingClientRect が全ゼロで座標変換が成立しない） ----
const MIN_H = 48 / 60
const GRID_LEFT = 100
const GRID_TOP = 200
const COL_W = 70

const yOf = (minutes: number) => GRID_TOP + minutes * MIN_H
const xOf = (dayIndex: number) => GRID_LEFT + dayIndex * COL_W + COL_W / 2

function pointerEvent(type: string, init: { clientX: number; clientY: number }): Event {
  const evt = new Event(type, { bubbles: true, cancelable: true })
  return Object.assign(evt, {
    clientX: init.clientX,
    clientY: init.clientY,
    pointerType: 'mouse',
    button: 0,
  })
}

/** 選択中のまま放置されたコンポーネントは window にリスナを張り続け、次のテストを汚染する。 */
const mountedWrappers: Array<{ unmount: () => void }> = []

afterEach(() => {
  while (mountedWrappers.length > 0) mountedWrappers.pop()?.unmount()
})

async function mountGrid(events: CalendarEventItem[] = []) {
  const wrapper = await mountSuspended(CalendarWeekGrid, {
    props: { weekStart: WEEK_START, events },
    attachTo: document.body,
    global: { stubs: { Button: true, Popover: PopoverStub, ScheduleListRow: ScheduleListRowStub } },
  })
  const columns = wrapper.get('[data-testid="week-grid-columns"]').element
  const rect = {
    left: GRID_LEFT,
    top: GRID_TOP,
    right: GRID_LEFT + COL_W * 7,
    bottom: GRID_TOP + 24 * 48,
    width: COL_W * 7,
    height: 24 * 48,
    x: GRID_LEFT,
    y: GRID_TOP,
    toJSON: () => ({}),
  }
  columns.getBoundingClientRect = () => rect as DOMRect
  mountedWrappers.push(wrapper)
  return wrapper
}

type Wrapper = Awaited<ReturnType<typeof mountGrid>>

function grid(wrapper: Wrapper) {
  return wrapper.get('[data-testid="week-grid-columns"]')
}

async function press(wrapper: Wrapper, key: string, shiftKey = false): Promise<void> {
  await grid(wrapper).trigger('keydown', { key, shiftKey })
}

describe('CalendarWeekGrid — キーボード操作（F03.19 §6.7.1・AC-25）', () => {
  it('グリッド全体が1タブストップになっている（セルは個別のタブストップにしない）', async () => {
    const wrapper = await mountGrid()
    const g = grid(wrapper)
    expect(g.attributes('tabindex')).toBe('0')
    // グリッド配下に他のタブストップが無い（672個のセルが Tab の的になっていない）
    expect(g.element.querySelectorAll('[tabindex]').length).toBe(0)
  })

  it('↓ でフォーカスセルがスナップ単位（15分）で動き、aria-activedescendant が追従する', async () => {
    const wrapper = await mountGrid()
    // 初期フォーカスは 8:00（= 480分）
    expect(grid(wrapper).attributes('aria-activedescendant')).toBe('wg-slot-0-480')

    await press(wrapper, 'ArrowDown')
    expect(grid(wrapper).attributes('aria-activedescendant')).toBe('wg-slot-0-495')
    await press(wrapper, 'ArrowDown')
    expect(grid(wrapper).attributes('aria-activedescendant')).toBe('wg-slot-0-510')
    await press(wrapper, 'ArrowUp')
    expect(grid(wrapper).attributes('aria-activedescendant')).toBe('wg-slot-0-495')
  })

  it('← → で前日・翌日へ移り、週の端では前週・翌週へ繰り上がる', async () => {
    const wrapper = await mountGrid()
    await press(wrapper, 'ArrowRight')
    expect(grid(wrapper).attributes('aria-activedescendant')).toBe('wg-slot-1-480')

    await press(wrapper, 'ArrowLeft')
    await press(wrapper, 'ArrowLeft')
    // 週頭からさらに左 → 前週へ繰り上がり、フォーカスは週末（土曜=列6）へ
    expect(wrapper.emitted('prevWeek')).toHaveLength(1)
    expect(grid(wrapper).attributes('aria-activedescendant')).toBe('wg-slot-6-480')

    await press(wrapper, 'ArrowRight')
    expect(wrapper.emitted('nextWeek')).toHaveLength(1)
    expect(grid(wrapper).attributes('aria-activedescendant')).toBe('wg-slot-0-480')
  })

  it('Shift+↓ で選択が延び、Shift+↑ で縮み、最小15分で止まる', async () => {
    const wrapper = await mountGrid()
    await press(wrapper, 'ArrowDown', true)
    let box = wrapper.get('[data-testid="week-selection-highlight"]')
    expect(box.text()).toContain('8:00')
    expect(box.text()).toContain('8:15')

    await press(wrapper, 'ArrowDown', true)
    await press(wrapper, 'ArrowDown', true)
    box = wrapper.get('[data-testid="week-selection-highlight"]')
    expect(box.text()).toContain('8:45')

    await press(wrapper, 'ArrowUp', true)
    expect(wrapper.get('[data-testid="week-selection-highlight"]').text()).toContain('8:30')

    // 最小15分より縮まない（何度押しても 8:00–8:15 で止まる）
    await press(wrapper, 'ArrowUp', true)
    await press(wrapper, 'ArrowUp', true)
    await press(wrapper, 'ArrowUp', true)
    box = wrapper.get('[data-testid="week-selection-highlight"]')
    expect(box.text()).toContain('8:00')
    expect(box.text()).toContain('8:15')
  })

  it('Escape で選択が破棄される（フォーカスは残る）', async () => {
    const wrapper = await mountGrid()
    await press(wrapper, 'ArrowDown', true)
    expect(wrapper.find('[data-testid="week-selection-highlight"]').exists()).toBe(true)

    await press(wrapper, 'Escape')
    expect(wrapper.find('[data-testid="week-selection-highlight"]').exists()).toBe(false)
    expect(wrapper.emitted('rangeSelect')).toBeUndefined()
    expect(grid(wrapper).attributes('aria-activedescendant')).toBe('wg-slot-0-480')
  })

  it('選択が無い状態の Enter は、フォーカス位置から既定60分で emit される（単クリックと同じ）', async () => {
    const wrapper = await mountGrid()
    await press(wrapper, 'Enter')
    expect(wrapper.emitted('rangeSelect')).toEqual([['2026-08-02T08:00:00+09:00', '2026-08-02T09:00:00+09:00']])
  })

  it('Space も Enter と同じく確定する', async () => {
    const wrapper = await mountGrid()
    await press(wrapper, ' ')
    expect(wrapper.emitted('rangeSelect')).toEqual([['2026-08-02T08:00:00+09:00', '2026-08-02T09:00:00+09:00']])
  })

  it('§6.7.1 末尾: キーボード経路とドラッグ経路が同一の rangeSelect を emit する', async () => {
    // --- キーボード: 8:00 から ↓×4 で 9:00 へ、Shift+↓×6 で 10:30 まで延ばして Enter ---
    const kb = await mountGrid()
    for (let i = 0; i < 4; i++) await press(kb, 'ArrowDown')
    for (let i = 0; i < 6; i++) await press(kb, 'ArrowDown', true)
    expect(kb.get('[data-testid="week-selection-highlight"]').text()).toContain('10:30')
    await press(kb, 'Enter')

    // --- ドラッグ: 同じ 9:00 → 10:30 を同じ列でなぞる ---
    const drag = await mountGrid()
    const el = drag.get('[data-testid="week-grid-columns"]').element
    el.dispatchEvent(pointerEvent('pointerdown', { clientX: xOf(0), clientY: yOf(9 * 60) }))
    window.dispatchEvent(pointerEvent('pointermove', { clientX: xOf(0), clientY: yOf(10 * 60 + 30) }))
    window.dispatchEvent(pointerEvent('pointerup', { clientX: xOf(0), clientY: yOf(10 * 60 + 30) }))
    await drag.vm.$nextTick()

    expect(kb.emitted('rangeSelect')).toEqual([['2026-08-02T09:00:00+09:00', '2026-08-02T10:30:00+09:00']])
    // 到達点が同一であることの直接の証明（経路が違うだけ）
    expect(kb.emitted('rangeSelect')).toEqual(drag.emitted('rangeSelect'))
  })
})

describe('CalendarWeekGrid — ARIA と読み上げ（F03.19 §6.7.2・AC-25b）', () => {
  it('時間グリッドが role="grid"、各日の列が role="row"、各スロットが role="gridcell" を持つ', async () => {
    const wrapper = await mountGrid()
    const g = grid(wrapper)
    expect(g.attributes('role')).toBe('grid')
    expect(g.element.querySelectorAll('[role="row"]').length).toBe(7)
    // 7日 × 96スロット（15分刻み）
    expect(g.element.querySelectorAll('[role="gridcell"]').length).toBe(7 * 96)
  })

  it('role="grid" の aria-label に週の範囲が入る', async () => {
    const wrapper = await mountGrid()
    const label = grid(wrapper).attributes('aria-label') ?? ''
    expect(label).toContain('2026')
    expect(label).toContain('August')
    // 直書きではなく locale ファイル経由（en の訳文が出ている）
    expect(label).toMatch(/^Week of /)
  })

  it('各 gridcell の aria-label に時刻が含まれ、空きスロットは「空き」を伴う', async () => {
    const wrapper = await mountGrid()
    const cell = wrapper.get('[data-testid="week-slot-4-9-0"]')
    const label = cell.attributes('aria-label') ?? ''
    // 時刻（AC-25b の要件そのもの）
    expect(label).toContain('9:00')
    // 日付と曜日（8月6日 木曜日 に相当する en 表記）
    expect(label).toContain('August 6')
    expect(label).toContain('Thursday')
    // 空きスロットの表示（en は Available）
    expect(label).toContain('Available')
  })

  it('予定があるスロットの aria-label には予定タイトルが入り、「空き」にはならない', async () => {
    const events = [ev({ id: 51, startAt: '2026-08-06T09:00:00+09:00', endAt: '2026-08-06T10:00:00+09:00' })]
    const wrapper = await mountGrid(events)
    const label = wrapper.get('[data-testid="week-slot-4-9-30"]').attributes('aria-label') ?? ''
    expect(label).toContain('9:30')
    expect(label).toContain('予定51')
    expect(label).not.toContain('Available')

    // 予定の外側のスロットは従来どおり「空き」
    expect(wrapper.get('[data-testid="week-slot-4-11-0"]').attributes('aria-label')).toContain('Available')
  })

  it('選択範囲が aria-live="polite" 領域でアナウンスされ、破棄すると消える', async () => {
    const wrapper = await mountGrid()
    const live = wrapper.get('[data-testid="week-selection-announcement"]')
    expect(live.attributes('aria-live')).toBe('polite')
    expect(live.text()).toBe('')

    await press(wrapper, 'ArrowDown', true)
    // en: 'Selecting {start} to {end}'
    expect(live.text()).toBe('Selecting 8:00 to 8:15')

    await press(wrapper, 'Escape')
    expect(wrapper.get('[data-testid="week-selection-announcement"]').text()).toBe('')
  })

  it('選択中のセルに aria-selected が立つ（範囲外は立たない）', async () => {
    const wrapper = await mountGrid()
    await press(wrapper, 'ArrowDown', true)
    // 8:00–8:15 が選択範囲
    expect(wrapper.get('[data-testid="week-slot-0-8-0"]').attributes('aria-selected')).toBe('true')
    expect(wrapper.get('[data-testid="week-slot-0-8-15"]').attributes('aria-selected')).toBe('false')
  })

  it('終日帯は role="grid" の外にあり、独立した role="list" である（時間軸を持たないため）', async () => {
    const events = [ev({ id: 61, allDay: true, startAt: '2026-08-04T00:00:00+09:00', endAt: '2026-08-04T00:00:00+09:00' })]
    const wrapper = await mountGrid(events)
    const lane = wrapper.get('[data-testid="week-allday-lane"]')
    const list = lane.get('[role="list"]')
    expect(list.attributes('aria-label')).toBe('All-day events')
    // grid の内側に終日帯が入り込んでいないこと
    expect(grid(wrapper).element.querySelector('[role="list"]')).toBeNull()
    expect(list.element.querySelectorAll('[role="listitem"]').length).toBe(1)
  })
})
