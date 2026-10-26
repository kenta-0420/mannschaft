import { defineComponent, h } from 'vue'
import dayjs from 'dayjs'
import { afterEach, describe, expect, it } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import CalendarWeekGrid from '~/components/schedule/CalendarWeekGrid.vue'
import type { CalendarEventItem } from '~/composables/useCalendarEvents'

/**
 * F03.19 §6.6.5 / R16 — **夏時間の切替日でも、選んだ壁時計の時刻がそのまま emit される**こと。
 *
 * ユーザーの地域を `America/New_York` に固定する。2026-03-08 はこの地域の夏時間切替日で、
 * 深夜 0:00 は `-05:00`（EST）、02:00 以降は `-04:00`（EDT）である。
 *
 * **「その日の 0:00 に分を足す」実装はここで壊れる** — 0:00 のオフセットのまま加算するため、
 * 切替後の時刻を選ぶと1時間ずれた瞬間が出る。同じ日・同じ地域で切替の前後を対比させ、
 * **異なるオフセットが正しく出ること**を1つのテストで示す。
 */

mockNuxtImport('useDatetime', () => () => ({ userTimezone: { value: 'America/New_York' } }))
mockNuxtImport('useHolidays', () => () => ({ getHoliday: () => null }))

/** 2026-03-08(日) を含む週。週起点は日曜なので 3/8 自身が dayIndex 0。 */
const WEEK_START = '2026-03-08'
const USER_TZ = 'America/New_York'

const PopoverStub = defineComponent({
  setup(_props, { slots, expose }) {
    expose({ show: () => {}, hide: () => {} })
    return () => slots.default?.()
  },
})

const ScheduleListRowStub = defineComponent({
  props: { event: { type: Object as () => CalendarEventItem, required: true } },
  setup(props) {
    return () => h('div', props.event.title)
  },
})

// ---- 実寸ジオメトリ（jsdom は getBoundingClientRect が全ゼロ） ----
const MIN_H = 48 / 60
const GRID_LEFT = 100
const GRID_TOP = 200
const COL_W = 70

const yOf = (minutes: number) => GRID_TOP + minutes * MIN_H
const xOf = (dayIndex: number) => GRID_LEFT + dayIndex * COL_W + COL_W / 2

function pointerEvent(type: string, init: { clientX: number; clientY: number }): Event {
  const evt = new Event(type, { bubbles: true, cancelable: true })
  return Object.assign(evt, { clientX: init.clientX, clientY: init.clientY, pointerType: 'mouse', button: 0 })
}

const mountedWrappers: Array<{ unmount: () => void }> = []
afterEach(() => {
  while (mountedWrappers.length > 0) mountedWrappers.pop()?.unmount()
})

async function mountWeek(weekStart: string = WEEK_START) {
  const wrapper = await mountSuspended(CalendarWeekGrid, {
    props: { weekStart, events: [] as CalendarEventItem[] },
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

/** dayIndex 0（= 2026-03-08）の列で startMin..endMin をドラッグして確定する。 */
async function dragOn(
  wrapper: Awaited<ReturnType<typeof mountWeek>>,
  startMin: number,
  endMin: number,
): Promise<[string, string]> {
  const x = xOf(0)
  wrapper.get('[data-testid="week-grid-columns"]').element
    .dispatchEvent(pointerEvent('pointerdown', { clientX: x, clientY: yOf(startMin) }))
  window.dispatchEvent(pointerEvent('pointermove', { clientX: x, clientY: yOf(endMin) }))
  window.dispatchEvent(pointerEvent('pointerup', { clientX: x, clientY: yOf(endMin) }))
  await wrapper.vm.$nextTick()
  const emitted = wrapper.emitted('rangeSelect')
  expect(emitted).toBeDefined()
  return emitted![emitted!.length - 1] as [string, string]
}

describe('CalendarWeekGrid — 夏時間の切替日（§6.6.5 / R16）', () => {
  it('切替日の同じ列で、切替の前後に異なるオフセットが出る（前 -05:00 / 後 -04:00）', async () => {
    // 前提: 2026-03-08 は America/New_York の夏時間切替日である
    expect(dayjs.tz('2026-03-08T00:30:00', USER_TZ).format()).toContain('-05:00')
    expect(dayjs.tz('2026-03-08T09:00:00', USER_TZ).format()).toContain('-04:00')

    // --- 切替前（早朝 0:30 – 1:30）: EST = -05:00 ---
    const before = await dragOn(await mountWeek(), 30, 90)
    expect(before[0]).toBe('2026-03-08T00:30:00-05:00')
    expect(before[1]).toBe('2026-03-08T01:30:00-05:00')

    // --- 切替後（9:00 – 10:00）: EDT = -04:00 ---
    const after = await dragOn(await mountWeek(), 9 * 60, 10 * 60)
    expect(after[0]).toBe('2026-03-08T09:00:00-04:00')
    expect(after[1]).toBe('2026-03-08T10:00:00-04:00')

    // 同じ日・同じ地域なのにオフセットが異なる = 切替が正しく反映されている
    expect(before[0].slice(-6)).toBe('-05:00')
    expect(after[0].slice(-6)).toBe('-04:00')
    expect(before[0].slice(-6)).not.toBe(after[0].slice(-6))
  })

  it('受け側でユーザー地域へ戻しても 9:00 のまま（1時間ずれない）', async () => {
    const [startAt, endAt] = await dragOn(await mountWeek(), 9 * 60, 10 * 60)

    // R16 が定める受け側の解釈: dayjs(value).tz(userTimezone) で壁時計へ戻す
    expect(dayjs(startAt).tz(USER_TZ).format('HH:mm')).toBe('09:00')
    expect(dayjs(endAt).tz(USER_TZ).format('HH:mm')).toBe('10:00')

    // 「0:00 に分を足す」実装が出す誤った瞬間（10:00 EDT 相当）と一致しないこと
    const naive = dayjs.tz('2026-03-08T00:00:00', USER_TZ).add(9 * 60, 'minute')
    expect(dayjs(startAt).valueOf()).not.toBe(naive.valueOf())
  })

  it('夏時間と無関係な日（同じ週の 3/10）では通常どおり -04:00 で出る', async () => {
    // dayIndex 2 = 2026-03-10（切替後なので EDT）
    const wrapper = await mountWeek()
    const x = xOf(2)
    wrapper.get('[data-testid="week-grid-columns"]').element
      .dispatchEvent(pointerEvent('pointerdown', { clientX: x, clientY: yOf(9 * 60) }))
    window.dispatchEvent(pointerEvent('pointermove', { clientX: x, clientY: yOf(10 * 60) }))
    window.dispatchEvent(pointerEvent('pointerup', { clientX: x, clientY: yOf(10 * 60) }))
    await wrapper.vm.$nextTick()

    expect(wrapper.emitted('rangeSelect')).toEqual([['2026-03-10T09:00:00-04:00', '2026-03-10T10:00:00-04:00']])
  })

  /**
   * [検分2巡目 P2] 春の切替日には**その地域に存在しない時刻帯**がある。
   * America/New_York の 2026-03-08 は 02:00〜03:00 が丸ごと飛び、その範囲の壁時計は
   * 実在時刻へ正規化される（02:30 も 03:30 も 03:30 -04:00 へ潰れる）。
   *
   * 分の世界の最小長保証（normalizeRange）はこの潰れを検出できない。
   * **変換後の瞬間で正の期間を保証する**ことで、原因を問わずゼロ分を渡さない。
   */
  it('[存在しない時刻] ギャップ 02:30–03:30 を選んでもゼロ分にならず 15分以上になる', async () => {
    // 前提: 02:30 と 03:30 は同じ実在の瞬間へ潰れる（ギャップが実在することの確認）
    const collapsedA = dayjs.tz('2026-03-08T02:30:00', USER_TZ)
    const collapsedB = dayjs.tz('2026-03-08T03:30:00', USER_TZ)
    expect(collapsedA.valueOf()).toBe(collapsedB.valueOf())

    const [startAt, endAt] = await dragOn(await mountWeek(), 2 * 60 + 30, 3 * 60 + 30)

    const durationMin = (dayjs(endAt).valueOf() - dayjs(startAt).valueOf()) / 60_000
    expect(durationMin).toBeGreaterThanOrEqual(15)
    expect(dayjs(endAt).isAfter(dayjs(startAt))).toBe(true)
  })

  it('[存在しない時刻] ギャップを跨がない通常の範囲は従来どおり（延長が誤発火しない）', async () => {
    const [startAt, endAt] = await dragOn(await mountWeek(), 9 * 60, 10 * 60)
    expect(startAt).toBe('2026-03-08T09:00:00-04:00')
    expect(endAt).toBe('2026-03-08T10:00:00-04:00')
    // ちょうど 60分のまま（15分へ縮んだり、延長で伸びたりしない）
    expect((dayjs(endAt).valueOf() - dayjs(startAt).valueOf()) / 60_000).toBe(60)
  })

  /**
   * [秋の重複] 2026-11-01 の America/New_York は 01:00〜02:00 が二度来る。
   *
   * 設計書には秋の重複についての記載が無いため**方針を決め打ちしない**。
   * ここで守るべき不変条件は「ゼロ分・負の期間を渡さない」だけであり、それを検証する。
   * 実測では dayjs.tz は重複時刻に対して**先に来る側（EDT）**を一貫して選ぶため、
   * 範囲が重複帯を跨いでも期間は正のまま（壁時計 30分が実時間 90分として出る）。
   * これは「実際に経過する時間」としては正しい値である。
   */
  it('[秋の重複] 重複帯を含む範囲でも期間が正のまま（ゼロ分・負にならない）', async () => {
    const FALL_WEEK = '2026-11-01'

    // 重複帯の内側（01:00–01:30）
    const inside = await dragOn(await mountWeek(FALL_WEEK), 60, 90)
    expect(dayjs(inside[1]).isAfter(dayjs(inside[0]))).toBe(true)
    expect((dayjs(inside[1]).valueOf() - dayjs(inside[0]).valueOf()) / 60_000).toBeGreaterThanOrEqual(15)

    // 重複帯を跨ぐ（01:30–02:00）。壁時計では 30分だが、実時間は 90分になる
    const across = await dragOn(await mountWeek(FALL_WEEK), 90, 120)
    expect(dayjs(across[1]).isAfter(dayjs(across[0]))).toBe(true)
    expect((dayjs(across[1]).valueOf() - dayjs(across[0]).valueOf()) / 60_000).toBe(90)

    // オフセットが EDT → EST へ切り替わっている（重複日であることの裏取り）
    expect(across[0].slice(-6)).toBe('-04:00')
    expect(across[1].slice(-6)).toBe('-05:00')
  })

  it('24:00 まで選ぶと翌日 0:00 として emit される（23:60 のような不正時刻を作らない）', async () => {
    const [startAt, endAt] = await dragOn(await mountWeek(), 23 * 60 + 30, 24 * 60)
    expect(startAt).toBe('2026-03-08T23:30:00-04:00')
    expect(endAt).toBe('2026-03-09T00:00:00-04:00')
    expect(dayjs(endAt).isAfter(dayjs(startAt))).toBe(true)
  })
})
