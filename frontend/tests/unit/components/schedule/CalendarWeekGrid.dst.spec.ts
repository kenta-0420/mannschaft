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

async function mountWeek() {
  const wrapper = await mountSuspended(CalendarWeekGrid, {
    props: { weekStart: WEEK_START, events: [] as CalendarEventItem[] },
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

  it('24:00 まで選ぶと翌日 0:00 として emit される（23:60 のような不正時刻を作らない）', async () => {
    const [startAt, endAt] = await dragOn(await mountWeek(), 23 * 60 + 30, 24 * 60)
    expect(startAt).toBe('2026-03-08T23:30:00-04:00')
    expect(endAt).toBe('2026-03-09T00:00:00-04:00')
    expect(dayjs(endAt).isAfter(dayjs(startAt))).toBe(true)
  })
})
