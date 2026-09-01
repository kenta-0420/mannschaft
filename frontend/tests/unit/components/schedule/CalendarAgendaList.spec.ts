import { defineComponent, h } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import CalendarAgendaList from '~/components/schedule/CalendarAgendaList.vue'
import type { CalendarEventItem } from '~/composables/useCalendarEvents'

// 「今日」を 2026-08-15 に固定する（`~/utils/calendarWeek` は素の TS import であり、
// composable ではないため mockNuxtImport ではなく vi.mock で差し替える）。
vi.mock('~/utils/calendarWeek', async (importOriginal) => {
  const actual = await importOriginal<typeof import('~/utils/calendarWeek')>()
  return { ...actual, todayInTimezone: () => '2026-08-15' }
})

/**
 * F03.19 §6.1/§6.2 アジェンダ（リスト）ビューの受け入れテスト（AC-13b・AC-22b・§6.3）。
 *
 * 実データの形をそのまま再現する — BE から届くのはオフセット付き ISO 文字列。
 * 表示中の月は 2026年8月（`year=2026, month=8`）。「今日」は 2026-08-15 に固定する。
 */

mockNuxtImport('useDatetime', () => () => ({ userTimezone: { value: 'Asia/Tokyo' } }))

/** ScheduleListRow は出欠 composable 等に依存するため、行を出すだけの軽量スタブに置き換える。 */
const ScheduleListRowStub = defineComponent({
  props: {
    event: { type: Object as () => CalendarEventItem, required: true },
    scopeType: String,
    scopeId: String,
  },
  emits: ['open', 'responded'],
  setup(props, { emit }) {
    return () => h(
      'button',
      {
        'type': 'button',
        'data-testid': `agenda-row-${props.event.uniqueKey}`,
        'onClick': () => emit('open', props.event.id),
      },
      props.event.title,
    )
  },
})

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

async function mountAgenda(events: CalendarEventItem[], year = 2026, month = 8) {
  return mountSuspended(CalendarAgendaList, {
    props: { year, month, events, scopeType: 'team', scopeId: 'team-1' },
    global: {
      stubs: {
        Button: true,
        ScheduleListRow: ScheduleListRowStub,
      },
    },
  })
}

describe('CalendarAgendaList (F03.19 §6.1/§6.2)', () => {
  it('AC-13b: 4件以上の予定でも「+N件」を一切出さず全件を行として並べる', async () => {
    const events = Array.from({ length: 6 }, (_, i) =>
      ev({ id: i + 1, startAt: `2026-08-10T0${i}:00:00+09:00`, endAt: `2026-08-10T0${i}:30:00+09:00` }))
    const wrapper = await mountAgenda(events)

    // 6件すべてが行として現れる（切り捨てが起きない）
    for (let i = 1; i <= 6; i++) {
      expect(wrapper.find(`[data-testid="agenda-row-${i}"]`).exists()).toBe(true)
    }
    // 「+N件」に相当する要素が一切存在しない
    expect(wrapper.findAll('[data-testid^="day-overflow-"]').length).toBe(0)
    expect(wrapper.text()).not.toMatch(/\+\d+/)
  })

  it('日付見出し＋時系列で並ぶ（日をまたいだ予定は複数の見出しの下に現れる）', async () => {
    const events = [
      ev({ id: 1, startAt: '2026-08-12T14:00:00+09:00', endAt: '2026-08-12T15:00:00+09:00' }),
      ev({ id: 2, startAt: '2026-08-10T09:00:00+09:00', endAt: '2026-08-10T10:00:00+09:00' }),
      ev({ id: 3, startAt: '2026-08-10T18:00:00+09:00', endAt: '2026-08-10T19:00:00+09:00' }),
    ]
    const wrapper = await mountAgenda(events)

    // 日付見出しは 8/10・8/12 の2つのみ（予定0件の日は見出しごと出さない）
    expect(wrapper.find('[data-testid="agenda-day-2026-08-10"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="agenda-day-2026-08-12"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="agenda-day-2026-08-11"]').exists()).toBe(false)

    // 同日内は時系列昇順（id=2 が id=3 より先）
    const day10 = wrapper.get('[data-testid="agenda-day-2026-08-10"]')
    const rowIds = day10.findAll('button[data-testid^="agenda-row-"]').map(r => r.attributes('data-testid'))
    expect(rowIds).toEqual(['agenda-row-2', 'agenda-row-3'])
  })

  it('日をまたぐ予定は eventOccupiesDate と同じ基準で両日の見出しに現れる', async () => {
    // 8/6 22:00 〜 8/7 02:00
    const events = [ev({ id: 61, startAt: '2026-08-06T22:00:00+09:00', endAt: '2026-08-07T02:00:00+09:00' })]
    const wrapper = await mountAgenda(events)
    expect(wrapper.find('[data-testid="agenda-day-2026-08-06"]').text()).toContain('予定61')
    expect(wrapper.find('[data-testid="agenda-day-2026-08-07"]').text()).toContain('予定61')
  })

  it('予定0件の期間は schedule.calendar.empty を出す', async () => {
    const wrapper = await mountAgenda([])
    expect(wrapper.find('[data-testid="agenda-empty"]').exists()).toBe(true)
    expect(wrapper.findAll('[data-testid^="agenda-day-"]').length).toBe(0)
  })

  it('行クリックで eventClick を、reflection 行では reflectionClick を発火する', async () => {
    const events = [
      ev({ id: 5, startAt: '2026-08-10T09:00:00+09:00', endAt: '2026-08-10T10:00:00+09:00' }),
      ev({
        id: -1,
        uniqueKey: 'ref:abc',
        title: 'ふりかえり',
        isReflection: true,
        referenceUuid: 'abc',
        referenceKind: 'REFLECTION_ENTRY',
        startAt: '2026-08-11T09:00:00+09:00',
        endAt: '2026-08-11T09:00:00+09:00',
      }),
    ]
    const wrapper = await mountAgenda(events)

    await wrapper.get('[data-testid="agenda-row-5"]').trigger('click')
    expect(wrapper.emitted('eventClick')).toEqual([[5, true]])

    await wrapper.get('[data-testid="agenda-row-ref:abc"]').trigger('click')
    expect(wrapper.emitted('reflectionClick')).toEqual([['abc', 'REFLECTION_ENTRY']])
  })

  it('prevMonth/nextMonth/today を emit する（月ナビ・「今日」ボタン）', async () => {
    const wrapper = await mountAgenda([])
    await wrapper.get('[data-testid="agenda-prev"]').trigger('click')
    await wrapper.get('[data-testid="agenda-next"]').trigger('click')
    await wrapper.get('[data-testid="calendar-today-button"]').trigger('click')
    expect(wrapper.emitted('prevMonth')).toHaveLength(1)
    expect(wrapper.emitted('nextMonth')).toHaveLength(1)
    expect(wrapper.emitted('today')).toHaveLength(1)
  })

  it('AC-22b: 行や見出しをドラッグしてもハイライトも作成ダイアログも一切現れない（ドラッグ系イベント購読なし）', async () => {
    const events = [ev({ id: 1, startAt: '2026-08-10T09:00:00+09:00', endAt: '2026-08-10T10:00:00+09:00' })]
    const wrapper = await mountAgenda(events)
    const list = wrapper.get('[data-testid="agenda-list"]')

    // 週ビューのようなドラッグ選択関連の要素（ハイライト矩形・スロット）が構造的に存在しない
    expect(wrapper.find('[data-testid="week-selection-highlight"]').exists()).toBe(false)
    expect(wrapper.findAll('[data-testid^="week-slot-"]').length).toBe(0)

    // pointerdown → pointermove → pointerup を投げても、何のイベントも emit されない
    await list.trigger('pointerdown', { clientX: 0, clientY: 0 })
    await list.trigger('pointermove', { clientX: 100, clientY: 100 })
    await list.trigger('pointerup', { clientX: 100, clientY: 100 })
    expect(wrapper.emitted('rangeSelect')).toBeUndefined()
  })

  it('§6.3: 今日の日付見出しに data-agenda-today が付き、focusToday() が scrollIntoView を呼ぶ', async () => {
    const events = [
      ev({ id: 1, startAt: '2026-08-15T09:00:00+09:00', endAt: '2026-08-15T10:00:00+09:00' }),
      ev({ id: 2, startAt: '2026-08-20T09:00:00+09:00', endAt: '2026-08-20T10:00:00+09:00' }),
    ]
    const wrapper = await mountAgenda(events)

    // 今日（vi.mock で固定した 2026-08-15）の見出しにだけ目印が付く
    expect(wrapper.get('[data-testid="agenda-day-2026-08-15"]').attributes('data-agenda-today')).toBe('true')
    expect(wrapper.get('[data-testid="agenda-day-2026-08-20"]').attributes('data-agenda-today')).toBeUndefined()

    const todayHeading = wrapper.get('[data-testid="agenda-day-2026-08-15"]')
    let called = false
    // jsdom は scrollIntoView 未実装のため差し込む
    ;(todayHeading.element as HTMLElement).scrollIntoView = () => { called = true }

    await (wrapper.vm as unknown as { focusToday: () => void }).focusToday()
    expect(called).toBe(true)
  })

  it('§6.3: 今日が表示中の月に無い場合、focusToday() は例外を投げず何もしない', async () => {
    // 表示中の月は 2026年8月だが「今日」は 8/15。9月分のイベントのみを渡す形で確認する。
    const wrapper = await mountAgenda([], 2026, 9)
    expect(() => (wrapper.vm as unknown as { focusToday: () => void }).focusToday()).not.toThrow()
  })

  /**
   * 殿の是正指摘（検分差し戻し）: 月ビューは 2026年8月を「1日の直前の日曜（7/26）から42日」の
   * 6週グリッドで描き、`pages/calendar.vue` の取得範囲もその6週グリッドを包含する
   * （`CalendarGrid.vue:119-137`／`pages/calendar.vue:508` のコメント）。よって `filteredEvents`
   * には 7/26〜7/31・9/1〜9/5 の予定が既に含まれている。アジェンダを当月の日付だけに絞ると、
   * 月ビューには見えているこれらの予定がビュー切替で消える（AC-13 違反・AC-13b の趣旨が崩れる）。
   */
  it('AC-13/AC-13b: 月境界で絞り込まず、前後月へはみ出した6週グリッド分の予定も行として現れる', async () => {
    const events = [
      // 前月側のはみ出し（月グリッドの開始日 = 2026-07-26 の日曜）
      ev({ id: 701, title: '前月末の予定', startAt: '2026-07-26T09:00:00+09:00', endAt: '2026-07-26T10:00:00+09:00' }),
      ev({ id: 702, title: '前月末の予定2', startAt: '2026-07-30T09:00:00+09:00', endAt: '2026-07-30T10:00:00+09:00' }),
      // 当月内（比較用）
      ev({ id: 703, title: '当月の予定', startAt: '2026-08-15T09:00:00+09:00', endAt: '2026-08-15T10:00:00+09:00' }),
      // 翌月側のはみ出し（月グリッドの終了日 = 2026-09-05 の土曜）
      ev({ id: 704, title: '翌月頭の予定', startAt: '2026-09-01T09:00:00+09:00', endAt: '2026-09-01T10:00:00+09:00' }),
      ev({ id: 705, title: '翌月頭の予定2', startAt: '2026-09-05T09:00:00+09:00', endAt: '2026-09-05T10:00:00+09:00' }),
      // グリッド範囲そのものの外（月グリッドは 7/26〜9/5。9/6 は範囲外なので出てはならない）
      ev({ id: 706, title: '範囲外', startAt: '2026-09-06T09:00:00+09:00', endAt: '2026-09-06T10:00:00+09:00' }),
    ]
    const wrapper = await mountAgenda(events)

    for (const id of [701, 702, 703, 704, 705]) {
      expect(wrapper.find(`[data-testid="agenda-row-${id}"]`).exists()).toBe(true)
    }
    expect(wrapper.find('[data-testid="agenda-row-706"]').exists()).toBe(false)

    // 前後月の日付見出しには「存在を隠さない」ため見出し自体は出るが、区別のため目印を持つ
    expect(wrapper.get('[data-testid="agenda-day-2026-07-26"]').attributes('data-agenda-outside-month')).toBe('true')
    expect(wrapper.get('[data-testid="agenda-day-2026-09-05"]').attributes('data-agenda-outside-month')).toBe('true')
    expect(wrapper.get('[data-testid="agenda-day-2026-08-15"]').attributes('data-agenda-outside-month')).toBeUndefined()
  })
})
