import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { defineComponent, h } from 'vue'
import { afterEach, describe, expect, it } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import CalendarWeekGrid from '~/components/schedule/CalendarWeekGrid.vue'
import type { CalendarEventItem } from '~/composables/useCalendarEvents'

/**
 * F03.19 §6.6 グリッド選択による予定作成（AC-21b / AC-21f / AC-21g / AC-22b / AC-22d）。
 *
 * ここは composable ではなく **コンポーネントに結線された実際の DOM** を踏む。
 * 「どの要素から始めたジェスチャを弾くか」「ハイライトがどの列に出るか」
 * 「emit されるのがユーザー TZ のオフセット付き ISO か」は結線でしか壊れないため、
 * 座標変換の土台（getBoundingClientRect）だけを実寸に置き換えて本物の経路を通す。
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

function styleOf(el: { attributes: (n: string) => string | undefined }): string {
  return el.attributes('style') ?? ''
}

function styleProp(style: string, prop: string): string {
  const m = new RegExp(`(?:^|;)\\s*${prop}\\s*:\\s*([^;]+)`).exec(style)
  return m?.[1]?.trim() ?? ''
}

// ---- 実寸のジオメトリ（jsdom は getBoundingClientRect が全ゼロで座標変換が成立しない） ----
/** 1時間 = 48px。 */
const MIN_H = 48 / 60
const GRID_LEFT = 100
const GRID_TOP = 200
const COL_W = 70

const yOf = (minutes: number) => GRID_TOP + minutes * MIN_H
const xOf = (dayIndex: number) => GRID_LEFT + dayIndex * COL_W + COL_W / 2

function pointerEvent(type: string, init: { clientX: number; clientY: number; pointerType?: string }): Event {
  const evt = new Event(type, { bubbles: true, cancelable: true })
  return Object.assign(evt, {
    clientX: init.clientX,
    clientY: init.clientY,
    pointerType: init.pointerType ?? 'mouse',
    button: 0,
  })
}

/**
 * テスト間の汚染防止（必須）。ハイライト選択中のまま放置されたコンポーネントは
 * `window` にポインタリスナを張り続け、次のテストの pointermove/pointerup を掴む。
 * `window` は全テストで共有されている。
 */
const mountedWrappers: Array<{ unmount: () => void }> = []

afterEach(() => {
  while (mountedWrappers.length > 0) mountedWrappers.pop()?.unmount()
})

async function mountSelectable(events: CalendarEventItem[] = [], extraProps: Record<string, unknown> = {}) {
  const wrapper = await mountSuspended(CalendarWeekGrid, {
    props: { weekStart: WEEK_START, events, ...extraProps },
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

type Wrapper = Awaited<ReturnType<typeof mountSelectable>>

function pressAt(wrapper: Wrapper, x: number, y: number): void {
  wrapper.get('[data-testid="week-grid-columns"]').element
    .dispatchEvent(pointerEvent('pointerdown', { clientX: x, clientY: y }))
}

function moveTo(x: number, y: number): void {
  window.dispatchEvent(pointerEvent('pointermove', { clientX: x, clientY: y }))
}

function releaseAt(x: number, y: number): void {
  window.dispatchEvent(pointerEvent('pointerup', { clientX: x, clientY: y }))
}

function timedBars(wrapper: Wrapper, uniqueKey: string) {
  return wrapper.findAll(`[data-testid="week-event-${uniqueKey}"]`)
}

describe('CalendarWeekGrid — グリッド選択による予定作成（F03.19 §6.6）', () => {
  it('AC-21b: ドラッグ中に week-selection-highlight が出て、時刻ラベルが追従して更新される', async () => {
    const wrapper = await mountSelectable()
    expect(wrapper.find('[data-testid="week-selection-highlight"]').exists()).toBe(false)

    pressAt(wrapper, xOf(4), yOf(9 * 60 + 7))
    moveTo(xOf(4), yOf(10 * 60 + 23))
    await wrapper.vm.$nextTick()

    const box = wrapper.get('[data-testid="week-selection-highlight"]')
    // 15分スナップ・最も近い境界へ丸めて 9:00 – 10:30（10:23 は 10:30 側が近い）
    expect(box.text()).toContain('9:00')
    expect(box.text()).toContain('10:30')
    // 位置と高さも実時刻どおり（9:00 = 432px, 90分 = 72px）
    expect(Number.parseFloat(styleProp(styleOf(box), 'top'))).toBeCloseTo(432, 1)
    expect(Number.parseFloat(styleProp(styleOf(box), 'height'))).toBeCloseTo(72, 1)
    // 色だけに依存させない（§6.6.3）— 破線枠を持つ
    expect(box.classes()).toContain('border-dashed')

    moveTo(xOf(4), yOf(12 * 60))
    await wrapper.vm.$nextTick()
    expect(wrapper.get('[data-testid="week-selection-highlight"]').text()).toContain('12:00')
  })

  it('§6.6.6: ハイライトは作成スコープのレイヤー色の半透明（opacity 0.35）で描かれる', async () => {
    const wrapper = await mountSelectable([], { createScopeColor: '#ff8800' })
    pressAt(wrapper, xOf(2), yOf(9 * 60))
    moveTo(xOf(2), yOf(10 * 60))
    await wrapper.vm.$nextTick()

    const box = wrapper.get('[data-testid="week-selection-highlight"]')
    expect(styleProp(styleOf(box), 'border-color')).toBe('#ff8800')
    const fill = box.get('span')
    expect(styleProp(styleOf(fill), 'opacity')).toBe('0.35')
    expect(styleProp(styleOf(fill), 'background-color')).toBe('#ff8800')
  })

  it('AC-21f: 横へ別の曜日の列まで動かしてもハイライトは開始した列にだけ出る', async () => {
    const wrapper = await mountSelectable()
    pressAt(wrapper, xOf(1), yOf(9 * 60))
    moveTo(xOf(5), yOf(11 * 60))
    await wrapper.vm.$nextTick()

    expect(wrapper.findAll('[data-testid="week-selection-highlight"]').length).toBe(1)
    const columns = wrapper.get('[data-testid="week-grid-columns"]').element.children
    expect(columns[1]!.querySelector('[data-testid="week-selection-highlight"]')).not.toBeNull()
    expect(columns[5]!.querySelector('[data-testid="week-selection-highlight"]')).toBeNull()

    releaseAt(xOf(5), yOf(11 * 60))
    await wrapper.vm.$nextTick()
    // 複数日にまたがらず、開始した月曜(8/3)に閉じる
    expect(wrapper.emitted('rangeSelect')).toEqual([['2026-08-03T09:00:00+09:00', '2026-08-03T11:00:00+09:00']])
  })

  it('離すと rangeSelect がユーザー TZ のオフセット付き ISO 8601 で emit され、ハイライトが消える', async () => {
    const wrapper = await mountSelectable()
    // dayIndex 4 = 2026-08-06(木)
    pressAt(wrapper, xOf(4), yOf(9 * 60))
    moveTo(xOf(4), yOf(10 * 60 + 30))
    releaseAt(xOf(4), yOf(10 * 60 + 30))
    await wrapper.vm.$nextTick()

    expect(wrapper.emitted('rangeSelect')).toEqual([['2026-08-06T09:00:00+09:00', '2026-08-06T10:30:00+09:00']])
    // ナイーブ文字列を渡さない（オフセットが無いと受け側で端末TZ解釈されて壊れる・R16）
    for (const iso of wrapper.emitted('rangeSelect')![0] as string[]) {
      expect(iso).toMatch(/[+-]\d{2}:\d{2}$/)
    }
    expect(wrapper.find('[data-testid="week-selection-highlight"]').exists()).toBe(false)
  })

  it('AC-21g: 単クリック（閾値未満）はその位置から既定 60分として emit される', async () => {
    const wrapper = await mountSelectable()
    pressAt(wrapper, xOf(0), yOf(13 * 60))
    releaseAt(xOf(0), yOf(13 * 60))
    await wrapper.vm.$nextTick()

    expect(wrapper.emitted('rangeSelect')).toEqual([['2026-08-02T13:00:00+09:00', '2026-08-02T14:00:00+09:00']])
  })

  it('AC-22d: 既存の予定バーの上で開始しても選択は始まらない（従来どおり eventClick）', async () => {
    const events = [ev({ id: 51, startAt: '2026-08-06T09:00:00+09:00', endAt: '2026-08-06T10:00:00+09:00' })]
    const wrapper = await mountSelectable(events)

    timedBars(wrapper, '51')[0]!.element
      .dispatchEvent(pointerEvent('pointerdown', { clientX: xOf(4), clientY: yOf(9 * 60) }))
    moveTo(xOf(4), yOf(11 * 60))
    releaseAt(xOf(4), yOf(11 * 60))
    await wrapper.vm.$nextTick()

    expect(wrapper.find('[data-testid="week-selection-highlight"]').exists()).toBe(false)
    expect(wrapper.emitted('rangeSelect')).toBeUndefined()
  })

  it('§6.6.2: 終日帯の上で開始しても選択が始まらない', async () => {
    const wrapper = await mountSelectable()
    const lane = wrapper.get('[data-testid="week-allday-lane"]')
    expect(lane.attributes('data-range-select-ignore')).toBeDefined()

    lane.element.dispatchEvent(pointerEvent('pointerdown', { clientX: xOf(3), clientY: yOf(9 * 60) }))
    moveTo(xOf(3), yOf(11 * 60))
    releaseAt(xOf(3), yOf(11 * 60))
    await wrapper.vm.$nextTick()

    expect(wrapper.find('[data-testid="week-selection-highlight"]').exists()).toBe(false)
    expect(wrapper.emitted('rangeSelect')).toBeUndefined()
  })

  it('§6.6.2: 時刻ラベル列の上で開始しても選択が始まらない（スクロール操作と衝突するため）', async () => {
    const wrapper = await mountSelectable()
    const timeCol = wrapper.findAll('[data-range-select-ignore]')
      .find(el => el.text().includes('23:00'))
    expect(timeCol).toBeDefined()

    timeCol!.element.dispatchEvent(pointerEvent('pointerdown', { clientX: GRID_LEFT - 20, clientY: yOf(9 * 60) }))
    moveTo(xOf(0), yOf(11 * 60))
    releaseAt(xOf(0), yOf(11 * 60))
    await wrapper.vm.$nextTick()

    expect(wrapper.find('[data-testid="week-selection-highlight"]').exists()).toBe(false)
    expect(wrapper.emitted('rangeSelect')).toBeUndefined()
  })

  it('§6.6.4-4: touch-action は選択モード中だけ none になる（常時 none だとスクロールが死ぬ）', async () => {
    const wrapper = await mountSelectable()
    expect(styleProp(styleOf(wrapper.get('[data-testid="week-grid-columns"]')), 'touch-action')).toBe('auto')

    pressAt(wrapper, xOf(2), yOf(9 * 60))
    moveTo(xOf(2), yOf(10 * 60))
    await wrapper.vm.$nextTick()
    expect(styleProp(styleOf(wrapper.get('[data-testid="week-grid-columns"]')), 'touch-action')).toBe('none')

    releaseAt(xOf(2), yOf(10 * 60))
    await wrapper.vm.$nextTick()
    expect(styleProp(styleOf(wrapper.get('[data-testid="week-grid-columns"]')), 'touch-action')).toBe('auto')
  })

  it('AC-20: 操作ヒントは直書きせず locale の文言を出す（テスト環境の既定ロケールは en）', async () => {
    const wrapper = await mountSelectable()
    // 日本語が直書きされていれば en でも日本語が出る。locale ファイル経由であることの証明になる。
    expect(wrapper.get('[data-testid="week-drag-hint"]').text()).toBe('Drag to create an event')
  })

  it('AC-22b: 月ビュー（CalendarGrid）はグリッド選択の結線を一切持たない（ドラッグ選択は週ビュー限定）', () => {
    // vitest の cwd は frontend/。import.meta.url は file: スキームとは限らないため使わない。
    const source = readFileSync(resolve(process.cwd(), 'app/components/schedule/CalendarGrid.vue'), 'utf-8')
    expect(source).not.toContain('week-selection-highlight')
    expect(source).not.toContain('useGridRangeSelect')
    expect(source).not.toContain('rangeSelect')
  })
})
