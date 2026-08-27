import { defineComponent, h } from 'vue'
import { describe, expect, it } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import CalendarWeekGrid from '~/components/schedule/CalendarWeekGrid.vue'
import type { CalendarEventItem } from '~/composables/useCalendarEvents'

/**
 * F03.19 §6.5 バーチカル週ビューの受け入れテスト（AC-13 / AC-13d〜AC-13h）。
 *
 * 実データの形をそのまま再現する — BE から届くのはオフセット付き ISO 文字列であり、
 * `allDay=true` は 00:00:00〜23:59:59、日をまたぐ時刻付き予定は開始日と終了日が異なる。
 * 週は 2026-08-02(日) 〜 2026-08-08(土)。dayIndex は 0=日 … 4=木(8/6) 5=金(8/7) 6=土(8/8)。
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

/**
 * PrimeVue Popover の実オーバーレイ挙動（Teleport・トランジション）は検証対象外。
 * show/hide を no-op にしてスロット本体を常時描画し、中身だけを見る（月ビューのテストと同じ流儀）。
 */
const PopoverStub = defineComponent({
  setup(_props, { slots, expose }) {
    expose({ show: () => {}, hide: () => {} })
    return () => slots.default?.()
  },
})

/** ScheduleListRow は出欠 composable 等に依存するため、行を出すだけの軽量スタブに置き換える。 */
const ScheduleListRowStub = defineComponent({
  props: {
    event: { type: Object as () => CalendarEventItem, required: true },
    scopeType: String,
    scopeId: String,
  },
  emits: ['open'],
  setup(props, { emit }) {
    return () => h(
      'button',
      { 'type': 'button', 'data-testid': `popover-row-${props.event.uniqueKey}`, 'onClick': () => emit('open', props.event.id) },
      props.event.title,
    )
  },
})

async function mountWeek(events: CalendarEventItem[], weekStart = WEEK_START) {
  return mountSuspended(CalendarWeekGrid, {
    props: { weekStart, events },
    global: {
      stubs: {
        Button: true,
        Popover: PopoverStub,
        ScheduleListRow: ScheduleListRowStub,
      },
    },
  })
}

/** 時間グリッド側の予定バー（終日帯のバーとは data-testid の接頭辞で区別する）。 */
function timedBars(wrapper: Awaited<ReturnType<typeof mountWeek>>, uniqueKey: string) {
  return wrapper.findAll(`[data-testid="week-event-${uniqueKey}"]`)
}

function styleOf(el: { attributes: (n: string) => string | undefined }): string {
  return el.attributes('style') ?? ''
}

/** style 文字列から 1プロパティを取り出す（jsdom の inline style は正規化されるため文字列で見る）。 */
function styleProp(style: string, prop: string): string {
  const m = new RegExp(`(?:^|;)\\s*${prop}\\s*:\\s*([^;]+)`).exec(style)
  return m?.[1]?.trim() ?? ''
}

describe('CalendarWeekGrid (F03.19 §6.5)', () => {
  it('AC-13: 与えられた予定集合を 7 日分の列に束ね直して描画する（自身では取得しない）', async () => {
    const events = [
      ev({ id: 1, startAt: '2026-08-02T09:00:00+09:00', endAt: '2026-08-02T10:00:00+09:00' }),
      ev({ id: 2, startAt: '2026-08-05T13:00:00+09:00', endAt: '2026-08-05T14:00:00+09:00' }),
      ev({ id: 3, startAt: '2026-08-08T20:00:00+09:00', endAt: '2026-08-08T21:00:00+09:00' }),
    ]
    const wrapper = await mountWeek(events)

    // 7列の日付ヘッダー
    for (let di = 0; di < 7; di++) {
      expect(wrapper.find(`[data-testid="week-day-header-${di}"]`).exists()).toBe(true)
    }
    // 1件も落とさず、それぞれ正しい曜日の列に入る
    expect(timedBars(wrapper, '1')[0]?.attributes('data-day-index')).toBe('0')
    expect(timedBars(wrapper, '2')[0]?.attributes('data-day-index')).toBe('3')
    expect(timedBars(wrapper, '3')[0]?.attributes('data-day-index')).toBe('6')
  })

  it('§6.5.4: スロットに week-slot-{dayIndex}-{hour}-{minute} を付与する（E2E がピクセル計算を持たないため）', async () => {
    const wrapper = await mountWeek([])
    // 月曜 9:00（設計書の例）
    expect(wrapper.find('[data-testid="week-slot-1-9-0"]').exists()).toBe(true)
    // 境界: 日曜 0:00 / 土曜 23:45
    expect(wrapper.find('[data-testid="week-slot-0-0-0"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="week-slot-6-23-45"]').exists()).toBe(true)
    // スナップ境界は 0/15/30/45 のみ（ゼロ埋めしない）
    expect(wrapper.find('[data-testid="week-slot-1-09-00"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="week-slot-1-9-10"]').exists()).toBe(false)
    // 7日 × 24時間 × 4 = 672
    expect(wrapper.findAll('[data-testid^="week-slot-"]').length).toBe(672)
  })

  it('AC-13d: allDay=true と複数日にまたがる予定は終日帯に置かれ、時間グリッドには出ない', async () => {
    const events = [
      // 終日1日（例: 燃えるゴミ）
      ev({ id: 11, allDay: true, startAt: '2026-08-04T00:00:00+09:00', endAt: '2026-08-04T23:59:59+09:00' }),
      // 終日で複数日にまたがる（例: 夏合宿）
      ev({ id: 12, allDay: true, startAt: '2026-08-05T00:00:00+09:00', endAt: '2026-08-07T23:59:59+09:00' }),
      // 比較用の時刻付き
      ev({ id: 13, startAt: '2026-08-05T10:00:00+09:00', endAt: '2026-08-05T11:00:00+09:00' }),
    ]
    const wrapper = await mountWeek(events)

    expect(wrapper.find('[data-testid="week-allday-event-11"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="week-allday-event-12"]').exists()).toBe(true)
    // 時間グリッド内には現れない
    expect(timedBars(wrapper, '11').length).toBe(0)
    expect(timedBars(wrapper, '12').length).toBe(0)
    // 時刻付きの方は時間グリッドにあり、終日帯には無い
    expect(timedBars(wrapper, '13').length).toBe(1)
    expect(wrapper.find('[data-testid="week-allday-event-13"]').exists()).toBe(false)

    // 縦スクロールしても見え続ける = 終日帯は sticky で日付ヘッダー直下に固定されている
    const lane = wrapper.get('[data-testid="week-allday-lane"]')
    expect(lane.classes()).toContain('sticky')
    expect(styleProp(styleOf(lane), 'top')).toBe('48px')
  })

  it('AC-13e: 同一時間帯に3件重なると3件すべてが約1/3幅で横並びに描画される（1件も欠落しない）', async () => {
    const events = [
      ev({ id: 21, startAt: '2026-08-04T10:00:00+09:00', endAt: '2026-08-04T11:00:00+09:00' }),
      ev({ id: 22, startAt: '2026-08-04T10:30:00+09:00', endAt: '2026-08-04T11:30:00+09:00' }),
      ev({ id: 23, startAt: '2026-08-04T10:45:00+09:00', endAt: '2026-08-04T12:00:00+09:00' }),
    ]
    const wrapper = await mountWeek(events)

    for (const key of ['21', '22', '23']) {
      const bars = timedBars(wrapper, key)
      expect(bars.length).toBe(1)
      expect(styleProp(styleOf(bars[0]!), 'width')).toContain('33.3333%')
    }
    // 左オフセットは 0 / 1 / 2 列目にきれいに割り振られる（同じ場所に重ね書きしない）
    const lefts = ['21', '22', '23'].map(k => styleProp(styleOf(timedBars(wrapper, k)[0]!), 'left'))
    expect(new Set(lefts).size).toBe(3)
  })

  it('§6.5.2: 重なりに上限を設けない（10本重なっても1本も消さない）', async () => {
    const events = Array.from({ length: 10 }, (_, i) =>
      ev({ id: 100 + i, startAt: '2026-08-04T10:00:00+09:00', endAt: '2026-08-04T12:00:00+09:00' }))
    const wrapper = await mountWeek(events)

    for (let i = 0; i < 10; i++) {
      const bars = timedBars(wrapper, String(100 + i))
      expect(bars.length).toBe(1)
      expect(styleProp(styleOf(bars[0]!), 'width')).toContain('10.0000%')
    }
    // 月ビューのような「+N件」への切り捨ては時間グリッドには存在しない
    expect(wrapper.findAll('[data-testid^="day-overflow-"]').length).toBe(0)
  })

  it('§6.5.2: 重ならない予定はクラスタが切れて全幅に戻る', async () => {
    const events = [
      ev({ id: 31, startAt: '2026-08-04T10:00:00+09:00', endAt: '2026-08-04T11:00:00+09:00' }),
      // 11:00 ちょうど開始は「重なっていない」= 別クラスタ
      ev({ id: 32, startAt: '2026-08-04T11:00:00+09:00', endAt: '2026-08-04T12:00:00+09:00' }),
    ]
    const wrapper = await mountWeek(events)
    expect(styleProp(styleOf(timedBars(wrapper, '31')[0]!), 'width')).toContain('100.0000%')
    expect(styleProp(styleOf(timedBars(wrapper, '32')[0]!), 'width')).toContain('100.0000%')
  })

  it('AC-13f: 15分の予定でも最低高さ20px以上で描画される（潰れて消えない）', async () => {
    const events = [
      ev({ id: 41, startAt: '2026-08-04T10:00:00+09:00', endAt: '2026-08-04T10:15:00+09:00' }),
      // 比較: 1時間 = 48px
      ev({ id: 42, startAt: '2026-08-05T10:00:00+09:00', endAt: '2026-08-05T11:00:00+09:00' }),
    ]
    const wrapper = await mountWeek(events)

    const shortH = Number.parseFloat(styleProp(styleOf(timedBars(wrapper, '41')[0]!), 'height'))
    expect(shortH).toBeGreaterThanOrEqual(20)
    // 実時刻どおりなら 15 * 0.8 = 12px。下限が効いていることを明示する。
    expect(shortH).toBeGreaterThan(12)
    expect(Number.parseFloat(styleProp(styleOf(timedBars(wrapper, '42')[0]!), 'height'))).toBeCloseTo(48, 1)
  })

  it('AC-13f: 最低高さは描画にのみ効き、重なり判定は実時刻ベースのまま', async () => {
    // 10:00-10:05（描画上は20px = 25分相当まで伸びる）と 10:10-10:20。
    // 描画高さで重なりを測っていたらこの2件は重なり扱いになり 1/2 幅になってしまう。
    const events = [
      ev({ id: 51, startAt: '2026-08-04T10:00:00+09:00', endAt: '2026-08-04T10:05:00+09:00' }),
      ev({ id: 52, startAt: '2026-08-04T10:10:00+09:00', endAt: '2026-08-04T10:20:00+09:00' }),
    ]
    const wrapper = await mountWeek(events)
    expect(styleProp(styleOf(timedBars(wrapper, '51')[0]!), 'width')).toContain('100.0000%')
    expect(styleProp(styleOf(timedBars(wrapper, '52')[0]!), 'width')).toContain('100.0000%')
  })

  it('AC-13g: 8/6 22:00〜8/7 02:00 は2片に分割され ▼ / ▲ が付き、どちらのクリックでも同じ予定を開く', async () => {
    const events = [
      ev({ id: 61, title: '夜間行事', startAt: '2026-08-06T22:00:00+09:00', endAt: '2026-08-07T02:00:00+09:00' }),
    ]
    const wrapper = await mountWeek(events)

    const bars = timedBars(wrapper, '61')
    expect(bars.length).toBe(2)
    // 終日帯へは送らない
    expect(wrapper.find('[data-testid="week-allday-event-61"]').exists()).toBe(false)

    const [first, second] = bars
    // 8/6(木)=dayIndex 4 に 22:00〜24:00
    expect(first!.attributes('data-day-index')).toBe('4')
    expect(Number.parseFloat(styleProp(styleOf(first!), 'top'))).toBeCloseTo(22 * 60 * 0.8, 1)
    expect(Number.parseFloat(styleProp(styleOf(first!), 'height'))).toBeCloseTo(2 * 60 * 0.8, 1)
    expect(first!.find('[data-testid="week-event-continues-after"]').text()).toBe('▼')
    expect(first!.find('[data-testid="week-event-continues-before"]').exists()).toBe(false)

    // 8/7(金)=dayIndex 5 に 00:00〜02:00
    expect(second!.attributes('data-day-index')).toBe('5')
    expect(Number.parseFloat(styleProp(styleOf(second!), 'top'))).toBeCloseTo(0, 1)
    expect(Number.parseFloat(styleProp(styleOf(second!), 'height'))).toBeCloseTo(2 * 60 * 0.8, 1)
    expect(second!.find('[data-testid="week-event-continues-before"]').text()).toBe('▲')
    expect(second!.find('[data-testid="week-event-continues-after"]').exists()).toBe(false)

    // 分割片は1つの予定 — どちらをクリックしても同じ詳細が開く
    await first!.trigger('click')
    await second!.trigger('click')
    const emitted = wrapper.emitted('eventClick')
    expect(emitted).toHaveLength(2)
    expect(emitted![0]).toEqual([61, true])
    expect(emitted![1]).toEqual([61, true])
  })

  it('AC-13g: 翌日 0:00 ちょうどに終わる予定は翌日に空の片を作らず、継続記号も出さない', async () => {
    const events = [
      ev({ id: 62, startAt: '2026-08-06T22:00:00+09:00', endAt: '2026-08-07T00:00:00+09:00' }),
    ]
    const wrapper = await mountWeek(events)
    const bars = timedBars(wrapper, '62')
    expect(bars.length).toBe(1)
    expect(bars[0]!.attributes('data-day-index')).toBe('4')
    expect(bars[0]!.find('[data-testid="week-event-continues-after"]').exists()).toBe(false)
  })

  it('AC-13h: 3日にまたがる時刻付き予定の中間日（24時間フル占有）は終日帯に置かれる', async () => {
    const events = [
      ev({ id: 71, title: '長時間行事', startAt: '2026-08-05T22:00:00+09:00', endAt: '2026-08-07T02:00:00+09:00' }),
    ]
    const wrapper = await mountWeek(events)

    // 中間日 8/6 は終日帯へ
    const allDayBar = wrapper.find('[data-testid="week-allday-event-71"]')
    expect(allDayBar.exists()).toBe(true)

    // 時間グリッドに残るのは 8/5(dayIndex 3) と 8/7(dayIndex 5) の2片のみ。
    // 中間日 8/6(dayIndex 4) の列には1本も置かれない（時間グリッドを丸ごと覆わない）。
    const bars = timedBars(wrapper, '71')
    expect(bars.map(b => b.attributes('data-day-index'))).toEqual(['3', '5'])
  })

  it('§6.5.1: 現在時刻ラインは今日の列にだけ出る', async () => {
    // 2026-08-02 の週に「今日」は含まれない（テスト実行日が同じ週でない限り）ので出ない
    const past = await mountWeek([], '1999-01-03')
    expect(past.find('[data-testid="week-now-line"]').exists()).toBe(false)

    // 今日を含む週なら1本だけ出る
    const now = new Date()
    const ord = Math.floor(Date.UTC(now.getFullYear(), now.getMonth(), now.getDate()) / 86400000)
    const startOrd = ord - ((ord + 4) % 7)
    const d = new Date(startOrd * 86400000)
    const pad = (n: number) => String(n).padStart(2, '0')
    const thisWeek = `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}`
    const current = await mountWeek([], thisWeek)
    expect(current.findAll('[data-testid="week-now-line"]').length).toBe(1)
  })

  it('§6.5.1: アンマウント時に現在時刻ラインのタイマーを止める', async () => {
    const before = globalThis.setInterval
    const ids: Array<ReturnType<typeof setInterval>> = []
    const cleared: Array<ReturnType<typeof setInterval>> = []
    const originalClear = globalThis.clearInterval
    globalThis.setInterval = ((fn: () => void, ms: number) => {
      const id = before(fn, ms)
      ids.push(id)
      return id
    }) as typeof globalThis.setInterval
    globalThis.clearInterval = ((id: ReturnType<typeof setInterval>) => {
      cleared.push(id)
      return originalClear(id)
    }) as typeof globalThis.clearInterval

    try {
      const wrapper = await mountWeek([])
      expect(ids.length).toBeGreaterThan(0)
      wrapper.unmount()
      expect(cleared).toEqual(expect.arrayContaining(ids))
    }
    finally {
      globalThis.setInterval = before
      globalThis.clearInterval = originalClear
    }
  })

  it('§6.5.1: 終日帯のレーンが超過したら月ビューと同じ「+N件」を出す', async () => {
    // 同じ日に掛かる終日予定を4本 = レーン4本（MAX_LANES=3 超過）
    const events = Array.from({ length: 4 }, (_, i) =>
      ev({
        id: 200 + i,
        allDay: true,
        startAt: '2026-08-04T00:00:00+09:00',
        endAt: '2026-08-04T23:59:59+09:00',
      }))
    const wrapper = await mountWeek(events)
    const overflow = wrapper.find('[data-testid="day-overflow-2026-08-04"]')
    expect(overflow.exists()).toBe(true)
    // 実バー2本 + 残り2本が「+N件」へ
    expect(wrapper.findAll('[data-testid^="week-allday-event-"]').length).toBe(2)
  })

  it('[1] 終日帯の「+N件」を押すとその日の全予定が並び、省かれていた予定も開ける', async () => {
    const events = [
      ...Array.from({ length: 4 }, (_, i) =>
        ev({
          id: 200 + i,
          title: `終日${200 + i}`,
          allDay: true,
          startAt: '2026-08-04T00:00:00+09:00',
          endAt: '2026-08-04T23:59:59+09:00',
        })),
      // 同じ日の時刻付き予定も一覧に含まれる
      ev({ id: 210, title: '会議', startAt: '2026-08-04T10:00:00+09:00', endAt: '2026-08-04T11:00:00+09:00' }),
      // 別の日の予定は含まれない
      ev({ id: 211, title: '別日', startAt: '2026-08-05T10:00:00+09:00', endAt: '2026-08-05T11:00:00+09:00' }),
    ]
    const wrapper = await mountWeek(events)

    // 実バーとして出ているのは 200 / 201 のみ。202 / 203 はレーンから省かれている。
    const visibleKeys = wrapper.findAll('[data-testid^="week-allday-event-"]')
      .map(b => b.attributes('data-testid'))
    expect(visibleKeys).toEqual(['week-allday-event-200', 'week-allday-event-201'])

    await wrapper.get('[data-testid="day-overflow-2026-08-04"]').trigger('click')
    await wrapper.vm.$nextTick()

    // get() は不在なら例外を投げるので、これ自体がポップオーバー存在の検証になる
    const popover = wrapper.get('[data-testid="day-detail-popover"]')

    // 省かれていた 202 / 203 が確実に含まれる（これが本修正の核心）
    for (const id of [200, 201, 202, 203, 210]) {
      expect(popover.find(`[data-testid="popover-row-${id}"]`).exists()).toBe(true)
    }
    // 別の日の予定は出ない
    expect(popover.find('[data-testid="popover-row-211"]').exists()).toBe(false)

    // 省かれていた予定の行をクリックすると、その予定の詳細を開く経路が発火する
    await popover.get('[data-testid="popover-row-202"]').trigger('click')
    const emitted = wrapper.emitted('eventClick')
    expect(emitted).toBeTruthy()
    expect(emitted![emitted!.length - 1]).toEqual([202, true])
  })

  it('[1・二巡目] 零時ちょうどに終わる予定は翌日の一覧に混入しない', async () => {
    const events = [
      // 8/3 22:00 〜 8/4 00:00。8/4 には一瞬も存在しない。
      ev({ id: 900, title: '境界予定', startAt: '2026-08-03T22:00:00+09:00', endAt: '2026-08-04T00:00:00+09:00' }),
      // 8/3・8/4 それぞれで終日レーンを溢れさせ、両日の「+N件」を出す
      ...Array.from({ length: 4 }, (_, i) =>
        ev({ id: 911 + i, title: `3日終日${i}`, allDay: true, startAt: '2026-08-03T00:00:00+09:00', endAt: '2026-08-03T23:59:59+09:00' })),
      ...Array.from({ length: 4 }, (_, i) =>
        ev({ id: 921 + i, title: `4日終日${i}`, allDay: true, startAt: '2026-08-04T00:00:00+09:00', endAt: '2026-08-04T23:59:59+09:00' })),
    ]
    const wrapper = await mountWeek(events)

    const rowIds = () => wrapper.findAll('[data-testid^="popover-row-"]')
      .map(r => r.attributes('data-testid'))

    // 8/3 の一覧には出る
    await wrapper.get('[data-testid="day-overflow-2026-08-03"]').trigger('click')
    await wrapper.vm.$nextTick()
    expect(rowIds()).toContain('popover-row-900')

    // 8/4 の一覧には出ない（時間グリッドの分類と同じ排他的な日区間で判定する）
    await wrapper.get('[data-testid="day-overflow-2026-08-04"]').trigger('click')
    await wrapper.vm.$nextTick()
    expect(rowIds()).not.toContain('popover-row-900')
    // 8/4 本来の予定は漏れなく出る
    for (const id of [921, 922, 923, 924]) {
      expect(rowIds()).toContain(`popover-row-${id}`)
    }
  })

  /**
   * [P2] 秒の切り捨てで消える欠陥の対比。
   * 直前の「ちょうど零時は翌日に出ない」テストと隣り合わせで、境界の区別が付いていることを示す。
   */
  it('[P2] 零時を30秒越える予定は、翌日の一覧にも時間グリッドにも出て継続記号が付く', async () => {
    const events = [
      // 8/3 22:00:00 〜 8/4 00:00:30。8/4 には 30秒だけ掛かっている。
      ev({ id: 930, title: '30秒越え', startAt: '2026-08-03T22:00:00+09:00', endAt: '2026-08-04T00:00:30+09:00' }),
      ...Array.from({ length: 4 }, (_, i) =>
        ev({ id: 941 + i, allDay: true, startAt: '2026-08-04T00:00:00+09:00', endAt: '2026-08-04T23:59:59+09:00' })),
    ]
    const wrapper = await mountWeek(events)

    // 時間グリッドに2片描かれる（8/3=dayIndex 1、8/4=dayIndex 2）
    const bars = timedBars(wrapper, '930')
    expect(bars.map(b => b.attributes('data-day-index'))).toEqual(['1', '2'])

    // 継続記号が両側に付く
    expect(bars[0]!.find('[data-testid="week-event-continues-after"]').text()).toBe('▼')
    expect(bars[1]!.find('[data-testid="week-event-continues-before"]').text()).toBe('▲')

    // 翌日側の片は 00:00 の位置にあり、潰れず最低高さで見える
    expect(Number.parseFloat(styleProp(styleOf(bars[1]!), 'top'))).toBeCloseTo(0, 1)
    expect(Number.parseFloat(styleProp(styleOf(bars[1]!), 'height'))).toBeGreaterThanOrEqual(20)
    // 分の表示は切り捨てて 00:00（"0:0.5" のような文字列にならない）
    expect(bars[1]!.text()).toContain('00:00')

    // 翌日の一覧にも出る
    await wrapper.get('[data-testid="day-overflow-2026-08-04"]').trigger('click')
    await wrapper.vm.$nextTick()
    expect(wrapper.findAll('[data-testid^="popover-row-"]').map(r => r.attributes('data-testid')))
      .toContain('popover-row-930')
  })

  it('[P2] ちょうど零時に終わる予定は翌日側の片も継続記号も出ない（30秒越えとの対比）', async () => {
    const wrapper = await mountWeek([
      ev({ id: 931, title: 'ちょうど零時', startAt: '2026-08-03T22:00:00+09:00', endAt: '2026-08-04T00:00:00+09:00' }),
    ])
    const bars = timedBars(wrapper, '931')
    expect(bars.map(b => b.attributes('data-day-index'))).toEqual(['1'])
    expect(bars[0]!.find('[data-testid="week-event-continues-after"]').exists()).toBe(false)
  })

  it('[1・二巡目] 零時を1分でも過ぎれば翌日の一覧にも出る', async () => {
    const events = [
      ev({ id: 901, title: '跨ぎ予定', startAt: '2026-08-03T22:00:00+09:00', endAt: '2026-08-04T00:01:00+09:00' }),
      ...Array.from({ length: 4 }, (_, i) =>
        ev({ id: 921 + i, allDay: true, startAt: '2026-08-04T00:00:00+09:00', endAt: '2026-08-04T23:59:59+09:00' })),
    ]
    const wrapper = await mountWeek(events)
    await wrapper.get('[data-testid="day-overflow-2026-08-04"]').trigger('click')
    await wrapper.vm.$nextTick()
    expect(wrapper.findAll('[data-testid^="popover-row-"]').map(r => r.attributes('data-testid')))
      .toContain('popover-row-901')
  })

  it('[1] 溢れていない日の「+N件」は存在しない（幽霊要素を残さない）', async () => {
    const wrapper = await mountWeek([
      ev({ id: 220, allDay: true, startAt: '2026-08-04T00:00:00+09:00', endAt: '2026-08-04T23:59:59+09:00' }),
    ])
    expect(wrapper.findAll('[data-testid^="day-overflow-"]').length).toBe(0)
  })

  it('[2] 日またぎの翌日側の片は 00:00 を表示する（元の開始時刻を出さない）', async () => {
    const events = [
      ev({ id: 61, title: '夜間行事', startAt: '2026-08-06T22:00:00+09:00', endAt: '2026-08-07T02:00:00+09:00' }),
    ]
    const wrapper = await mountWeek(events)
    const bars = timedBars(wrapper, '61')
    expect(bars.length).toBe(2)

    // 8/6 側は実際の開始時刻
    expect(bars[0]!.text()).toContain('22:00')
    // 8/7 側は 00:00 の位置にあるので 00:00 を表示する。22:00 と出てはならない。
    expect(bars[1]!.text()).toContain('00:00')
    expect(bars[1]!.text()).not.toContain('22:00')
  })

  it('[2] 分割されない予定の表示時刻は従来どおり実際の開始時刻', async () => {
    const wrapper = await mountWeek([
      ev({ id: 63, startAt: '2026-08-04T09:05:00+09:00', endAt: '2026-08-04T10:00:00+09:00' }),
    ])
    expect(timedBars(wrapper, '63')[0]!.text()).toContain('09:05')
  })

  it('[4] 曜日名・週の見出しは選択中のロケールから生成される（日本語直書きしない）', async () => {
    // テスト環境の既定ロケールは en。日本語の曜日・「年」「月」が出てはならない。
    const wrapper = await mountWeek([])

    const headerText = wrapper.get('[data-testid="week-day-header-0"]').text()
    expect(headerText).toContain('Sun')
    expect(headerText).not.toMatch(/[日月火水木金土]/)

    const label = wrapper.get('h2').text()
    expect(label).not.toContain('年')
    expect(label).not.toContain('月')
    // 週の両端（8/2〜8/8）と年が読み取れる
    expect(label).toContain('August')
    expect(label).toContain('2026')
  })

  it('[3同根] 曜日名は端末ローカルTZに引きずられない（UTC+14 の端末でもずれない）', async () => {
    // 週の列は通日番号（＝ユーザー設定TZで確定した暦の日付）なので、Intl も UTC で読み戻す必要がある。
    // まず「timeZone を渡さなければ実際にずれる」ことを示す — これが避けている実害である。
    const noonUtc = new Date(Date.UTC(2026, 7, 2, 12, 0, 0)) // 2026-08-02(日) 正午 UTC
    expect(new Intl.DateTimeFormat('en', { weekday: 'short', timeZone: 'UTC' }).format(noonUtc)).toBe('Sun')
    expect(new Intl.DateTimeFormat('en', { weekday: 'short', timeZone: 'Pacific/Kiritimati' }).format(noonUtc))
      .toBe('Mon')

    // コンポーネントは UTC 固定で整形するため、列と曜日名の対応は常に保たれる。
    const wrapper = await mountWeek([], '2026-08-02')
    expect(wrapper.get('[data-testid="week-day-header-0"]').text()).toContain('Sun')
    expect(wrapper.get('[data-testid="week-day-header-6"]').text()).toContain('Sat')
    // 見出しの両端も 8/2〜8/8 のまま（1日ずれない）
    const label = wrapper.get('h2').text()
    expect(label).toContain('August 2')
    expect(label).toContain('8')
  })

  it('[4] 週が月をまたいでも見出しに両端が出る', async () => {
    // 2026-07-26(日) 〜 2026-08-01(土)
    const wrapper = await mountWeek([], '2026-07-26')
    const label = wrapper.get('h2').text()
    expect(label).toContain('July')
    expect(label).toContain('August')
  })
})
