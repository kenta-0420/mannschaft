import { defineComponent } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import CalendarGrid from '~/components/schedule/CalendarGrid.vue'
import type { CalendarEventItem } from '~/composables/useCalendarEvents'

mockNuxtImport('useDatetime', () => () => ({ userTimezone: { value: 'Asia/Tokyo' } }))
mockNuxtImport('useHolidays', () => () => ({ getHoliday: () => null }))

const AudienceStub = defineComponent({
  props: {
    compact: Boolean,
    targetMode: String,
    targetCount: Number,
    targets: Array,
  },
  template: '<span data-testid="audience" :data-compact="compact" :data-count="targetCount" />',
})

// PrimeVue Popover の実オーバーレイ挙動（Teleport・トランジション）はここでは検証対象外。
// show/hide を no-op にしたうえでスロット本体は常時描画し、中身（日別ポップオーバー）の
// 内容だけを検証する。
const PopoverStub = defineComponent({
  setup(_props, { slots, expose }) {
    expose({ show: () => {}, hide: () => {} })
    return () => slots.default?.()
  },
})

// ScheduleListRow は出欠回答 composable 等に依存するため、行クリックで open を発火するだけの
// 軽量スタブに置き換える（本テストの関心はポップオーバーの行数・クリック伝播）。
const ScheduleListRowStub = defineComponent({
  props: {
    event: { type: Object as () => CalendarEventItem, required: true },
    scopeType: String,
    scopeId: String,
  },
  emits: ['open'],
  template:
    '<button type="button" :data-testid="`popover-row-${event.uniqueKey}`" @click="$emit(\'open\', event.id)">{{ event.title }}</button>',
})

describe('CalendarGrid', () => {
  it('複数日barにもcompact対象者表示を描画する', async () => {
    const wrapper = await mountSuspended(CalendarGrid, {
      props: {
        year: 2026,
        month: 7,
        events: [{
          id: 10,
          uniqueKey: '10',
          title: '家族旅行',
          startAt: '2026-07-06T00:00:00+09:00',
          endAt: '2026-07-08T23:59:59+09:00',
          allDay: true,
          color: '#2563EB',
          isPersonal: false,
          scopeType: 'TEAM',
          scopeName: '家族',
          scopeIconUrl: null,
          targetMode: 'SELECTED_MEMBERS',
          targetCount: 2,
          targets: [
            { userId: 1, displayName: '父', avatarUrl: null, calendarColor: '#2563EB' },
            { userId: 2, displayName: '母', avatarUrl: null, calendarColor: '#DC2626' },
          ],
        }],
      },
      global: {
        stubs: {
          Button: true,
          ScheduleTargetAudience: AudienceStub,
        },
      },
    })

    const audience = wrapper.get('[data-testid="audience"]')
    expect(audience.attributes('data-compact')).toBe('true')
    expect(audience.attributes('data-count')).toBe('2')
  })

  // CalendarGrid はチーム/組織スケジュール画面・ダッシュボードウィジェットでも再利用される。
  // それらの親は today イベントを購読していないため、既定では「今日」ボタンを出してはならない
  // （押しても無反応なボタンを出さない）。
  it('showTodayButton を渡さない既定では「今日」ボタンを描画しない（無反応ボタンの露出防止）', async () => {
    const wrapper = await mountSuspended(CalendarGrid, {
      props: { year: 2026, month: 7, events: [] },
      global: { stubs: { Button: true, ScheduleTargetAudience: AudienceStub, Popover: PopoverStub } },
    })

    expect(wrapper.find('[data-testid="calendar-today-button"]').exists()).toBe(false)
  })

  it('showTodayButton=true では「今日」ボタンを描画し、クリックで today イベントを発火する', async () => {
    const wrapper = await mountSuspended(CalendarGrid, {
      props: { year: 2026, month: 7, events: [], showTodayButton: true },
      global: { stubs: { Button: true, ScheduleTargetAudience: AudienceStub, Popover: PopoverStub } },
    })

    const todayButton = wrapper.get('[data-testid="calendar-today-button"]')
    await todayButton.trigger('click')
    expect(wrapper.emitted('today')).toBeTruthy()
  })

  // AC-12: 単日イベントの「+N件」オーバーフロー（§6.2）
  it('AC-12: 単日イベントが5件のセルは先頭2件のみ表示し「他3件」を出す。クリックで5件全てが日別ポップオーバーに並ぶ', async () => {
    const events: CalendarEventItem[] = Array.from({ length: 5 }, (_, i) => ({
      id: i + 1,
      uniqueKey: `single-${i + 1}`,
      title: `単日予定${i + 1}`,
      startAt: '2026-07-10T09:00:00+09:00',
      endAt: '2026-07-10T10:00:00+09:00',
      allDay: false,
      color: '#22c55e',
      isPersonal: true,
      scopeType: 'PERSONAL',
    }))

    const wrapper = await mountSuspended(CalendarGrid, {
      props: { year: 2026, month: 7, events },
      global: {
        stubs: {
          Button: true,
          ScheduleTargetAudience: AudienceStub,
          Popover: PopoverStub,
          ScheduleListRow: ScheduleListRowStub,
        },
      },
    })

    // 先頭2件のみ表示・3件目以降は本文に現れない
    expect(wrapper.text()).toContain('単日予定1')
    expect(wrapper.text()).toContain('単日予定2')
    expect(wrapper.text()).not.toContain('単日予定3')

    // day-overflow 要素・件数（テスト環境はブラウザ言語検出で既定ロケールが変わりうるため、
    // 文言そのものではなく i18n 置換後の件数「3」が出ていることで検証する）
    const overflow = wrapper.get('[data-testid="day-overflow-2026-07-10"]')
    expect(overflow.text()).toContain('3')

    // クリックすると5件すべてが日別ポップオーバーに並ぶ
    await overflow.trigger('click')
    const rows = wrapper.findAll('[data-testid^="popover-row-"]')
    expect(rows).toHaveLength(5)
    expect(rows.map(r => r.text())).toEqual([
      '単日予定1', '単日予定2', '単日予定3', '単日予定4', '単日予定5',
    ])

    // 行クリックは既存の eventClick 振り分けへ委譲される
    await rows[2]!.trigger('click')
    expect(wrapper.emitted('eventClick')).toEqual([[3, true]])
  })

  it('AC-12: 単日イベントが3件以下のセルは全件表示し「他N件」は出さない（境界）', async () => {
    const events: CalendarEventItem[] = Array.from({ length: 3 }, (_, i) => ({
      id: i + 1,
      uniqueKey: `single3-${i + 1}`,
      title: `境界予定${i + 1}`,
      startAt: '2026-07-11T09:00:00+09:00',
      endAt: '2026-07-11T10:00:00+09:00',
      allDay: false,
      color: '#22c55e',
      isPersonal: true,
      scopeType: 'PERSONAL',
    }))

    const wrapper = await mountSuspended(CalendarGrid, {
      props: { year: 2026, month: 7, events },
      global: {
        stubs: {
          Button: true,
          ScheduleTargetAudience: AudienceStub,
          Popover: PopoverStub,
          ScheduleListRow: ScheduleListRowStub,
        },
      },
    })

    expect(wrapper.text()).toContain('境界予定1')
    expect(wrapper.text()).toContain('境界予定2')
    expect(wrapper.text()).toContain('境界予定3')
    expect(wrapper.find('[data-testid="day-overflow-2026-07-11"]').exists()).toBe(false)
  })

  // AC-12b: 複数日バーのレーン超過「+N件」（§6.2）
  it('AC-12b: 同一週に4本重なる複数日バーはレーン超過分を日ごとの「+N件」で表示し、無言で消さない', async () => {
    // 2026年8月: 8/3(月)〜8/5(水)が同一週（日始まり: 8/2 日〜8/8 土）。
    const events: CalendarEventItem[] = [
      {
        id: 101, uniqueKey: 'md-1', title: '複数日A',
        startAt: '2026-08-03T00:00:00+09:00', endAt: '2026-08-04T23:59:59+09:00',
        allDay: true, color: '#6366f1', isPersonal: false, scopeType: 'TEAM',
      },
      {
        id: 102, uniqueKey: 'md-2', title: '複数日B',
        startAt: '2026-08-03T00:00:00+09:00', endAt: '2026-08-04T23:59:59+09:00',
        allDay: true, color: '#6366f1', isPersonal: false, scopeType: 'TEAM',
      },
      {
        id: 103, uniqueKey: 'md-3', title: '複数日C',
        startAt: '2026-08-03T00:00:00+09:00', endAt: '2026-08-04T23:59:59+09:00',
        allDay: true, color: '#6366f1', isPersonal: false, scopeType: 'TEAM',
      },
      {
        id: 104, uniqueKey: 'md-4', title: '複数日D',
        startAt: '2026-08-04T00:00:00+09:00', endAt: '2026-08-05T23:59:59+09:00',
        allDay: true, color: '#6366f1', isPersonal: false, scopeType: 'TEAM',
      },
    ]

    const wrapper = await mountSuspended(CalendarGrid, {
      props: { year: 2026, month: 8, events },
      global: {
        stubs: {
          Button: true,
          ScheduleTargetAudience: AudienceStub,
          Popover: PopoverStub,
          ScheduleListRow: ScheduleListRowStub,
        },
      },
    })

    // 実バーは2本のみ描画（複数日A・複数日B）。レーン超過分（複数日C・D）は無言で消えない。
    expect(wrapper.text()).toContain('複数日A')
    expect(wrapper.text()).toContain('複数日B')

    // 8/3（複数日C・Dのうち8/3に掛かるのはCのみ→非表示1件）。
    // テスト環境はブラウザ言語検出で既定ロケールが変わりうるため、文言そのものではなく
    // i18n 置換後の件数（N はその日に掛かる非表示バー数と一致するはず＝AC-12b の核心）で検証する。
    const overflow0803 = wrapper.get('[data-testid="day-overflow-2026-08-03"]')
    expect(overflow0803.text()).toContain('1')

    // 8/4（複数日C・Dの両方が掛かる→非表示2件）
    const overflow0804 = wrapper.get('[data-testid="day-overflow-2026-08-04"]')
    expect(overflow0804.text()).toContain('2')

    // 8/5（複数日Dのみが掛かる→非表示1件）
    const overflow0805 = wrapper.get('[data-testid="day-overflow-2026-08-05"]')
    expect(overflow0805.text()).toContain('1')

    // クリックするとその日に掛かる予定が全件ポップオーバーに並ぶ（8/3: A/B/C の3件）
    await overflow0803.trigger('click')
    const rows = wrapper.findAll('[data-testid^="popover-row-"]')
    expect(rows.map(r => r.text())).toEqual(['複数日A', '複数日B', '複数日C'])
  })

  // §6.2 の表: 「その週のレーン数が MAX_LANES(3) 以下なら全バー表示」の境界。
  // 3本ちょうどのときに「+N件」へ切り詰めてしまう表示退行（改修前より悪化）を防ぐ回帰テスト。
  it('複数日バーが3本ちょうどの週は全バー表示し「+N件」を出さない（3レーン境界・表示退行の回帰）', async () => {
    const events: CalendarEventItem[] = [
      {
        id: 201, uniqueKey: 'md3-1', title: '境界バーA',
        startAt: '2026-08-03T00:00:00+09:00', endAt: '2026-08-04T23:59:59+09:00',
        allDay: true, color: '#6366f1', isPersonal: false, scopeType: 'TEAM',
      },
      {
        id: 202, uniqueKey: 'md3-2', title: '境界バーB',
        startAt: '2026-08-03T00:00:00+09:00', endAt: '2026-08-04T23:59:59+09:00',
        allDay: true, color: '#6366f1', isPersonal: false, scopeType: 'TEAM',
      },
      {
        id: 203, uniqueKey: 'md3-3', title: '境界バーC',
        startAt: '2026-08-03T00:00:00+09:00', endAt: '2026-08-04T23:59:59+09:00',
        allDay: true, color: '#6366f1', isPersonal: false, scopeType: 'TEAM',
      },
    ]

    const wrapper = await mountSuspended(CalendarGrid, {
      props: { year: 2026, month: 8, events },
      global: {
        stubs: {
          Button: true,
          ScheduleTargetAudience: AudienceStub,
          Popover: PopoverStub,
          ScheduleListRow: ScheduleListRowStub,
        },
      },
    })

    // 3本すべて実バーとして描画される（3本目を「+1件」に化けさせて消してはならない）
    expect(wrapper.text()).toContain('境界バーA')
    expect(wrapper.text()).toContain('境界バーB')
    expect(wrapper.text()).toContain('境界バーC')
    // 「+N件」ボタン自体は v-show で常に DOM 上に存在するため、存在ではなく非表示（display:none）で
    // 判定する。isVisible()（getComputedStyle 経由の CSS カスケード評価）は jsdom 環境で疑似要素を
    // 伴う要素に対して不安定だったため、v-show が書き込む inline style を直接見る（原因切り分け済み:
    // 純粋な算出ロジックを Node 単体で再現し laneOverflowByCol が両列とも 0 になることを確認済み）。
    const overflow0803 = wrapper.get('[data-testid="day-overflow-2026-08-03"]')
    const overflow0804 = wrapper.get('[data-testid="day-overflow-2026-08-04"]')
    expect(overflow0803.attributes('style')).toContain('display: none')
    expect(overflow0804.attributes('style')).toContain('display: none')
  })

  it('複数日バーが4本になった週で初めて「+N件」の溢れが出る（3本境界の対比）', async () => {
    const events: CalendarEventItem[] = Array.from({ length: 4 }, (_, i) => ({
      id: 301 + i,
      uniqueKey: `md4-${i + 1}`,
      title: `4本目境界${i + 1}`,
      startAt: '2026-08-03T00:00:00+09:00',
      endAt: '2026-08-04T23:59:59+09:00',
      allDay: true,
      color: '#6366f1',
      isPersonal: false,
      scopeType: 'TEAM',
    }))

    const wrapper = await mountSuspended(CalendarGrid, {
      props: { year: 2026, month: 8, events },
      global: {
        stubs: {
          Button: true,
          ScheduleTargetAudience: AudienceStub,
          Popover: PopoverStub,
          ScheduleListRow: ScheduleListRowStub,
        },
      },
    })

    // 実バーは2本のみ（レーン0・1）、3・4本目はレーン2の「+2件」へ回る
    expect(wrapper.text()).toContain('4本目境界1')
    expect(wrapper.text()).toContain('4本目境界2')
    expect(wrapper.text()).not.toContain('4本目境界3')
    expect(wrapper.text()).not.toContain('4本目境界4')
    const overflow = wrapper.get('[data-testid="day-overflow-2026-08-03"]')
    expect(overflow.text()).toContain('2')
  })

  // [A-1・検分二巡目] 「+N件」のタップ領域拡張が、レーン1の実バーや下の単日予定と
  // 重なって誤操作（別の予定のつもりが「+N件」を開いてしまう）を生んでいないことの回帰テスト。
  // jsdom は実レイアウト（getBoundingClientRect）を計算しないため、コンポーネントが実際に
  // 算出した inline style（top/height）を読み取り、矩形として重ならないことを検証する。
  it('[A-1回帰] レーン1のバーと「+N件」チップは縦方向に重ならず、バー領域のクリックはその予定を開く', async () => {
    const events: CalendarEventItem[] = Array.from({ length: 4 }, (_, i) => ({
      id: 401 + i,
      uniqueKey: `a1-${i + 1}`,
      title: `A1予定${i + 1}`,
      startAt: '2026-08-03T00:00:00+09:00',
      endAt: '2026-08-04T23:59:59+09:00',
      allDay: true,
      color: '#6366f1',
      isPersonal: false,
      scopeType: 'TEAM',
    }))

    const wrapper = await mountSuspended(CalendarGrid, {
      props: { year: 2026, month: 8, events },
      global: {
        stubs: {
          Button: true,
          ScheduleTargetAudience: AudienceStub,
          Popover: PopoverStub,
          ScheduleListRow: ScheduleListRowStub,
        },
      },
    })

    const parsePx = (style: string | undefined, prop: string): number => {
      const m = new RegExp(`${prop}:\\s*(-?[0-9.]+)px`).exec(style ?? '')
      if (!m) throw new Error(`${prop} not found in style="${style}"`)
      return Number(m[1])
    }

    // レーン1の実バー（2本目に表示される予定）
    const bars = wrapper.findAll('div.absolute.cursor-pointer.select-none')
    const lane1Bar = bars.find(b => b.text().includes('A1予定2'))
    expect(lane1Bar).toBeTruthy()
    const barTop = parsePx(lane1Bar!.attributes('style'), 'top')
    const barHeight = parsePx(lane1Bar!.attributes('style'), 'height')
    const barBottom = barTop + barHeight

    // 「+N件」チップ
    const overflow = wrapper.get('[data-testid="day-overflow-2026-08-03"]')
    const overflowTop = parsePx(overflow.attributes('style'), 'top')

    // 縦方向に重ならない（チップの上端がバーの下端以上）
    expect(overflowTop).toBeGreaterThanOrEqual(barBottom)

    // レーン1のバー領域をクリックすると、そのバーの予定が開く（「+N件」ではない）
    await lane1Bar!.trigger('click')
    expect(wrapper.emitted('eventClick')).toEqual([[402, false]])
    // 「+N件」側のクリックハンドラ（day-overflow）は誤って発火していない
    // （日別ポップオーバーが開いていない＝popoverDateStr が設定されていないことを、
    // ポップオーバー内に行が無いことで確認する）
    expect(wrapper.findAll('[data-testid^="popover-row-"]')).toHaveLength(0)
  })

  // AC-12d: 「今日」ボタン押下時のフォーカス移動（月移動は親コンポーネントの責務のため、
  // ここでは CalendarGrid が公開する focusToday() のフォーカス付与のみを検証する）
  it('AC-12d: focusToday() を呼ぶと今日のセルへフォーカスが移る（当月表示中でも必ず移る）', async () => {
    // isToday() は dayjs() の実時刻で判定するため、テスト時刻を固定して今日=2026-07-15 にする。
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-07-15T00:00:00+09:00'))

    try {
      const wrapper = await mountSuspended(CalendarGrid, {
        attachTo: document.body,
        props: { year: 2026, month: 7, events: [] },
        global: {
          stubs: {
            Button: true,
            ScheduleTargetAudience: AudienceStub,
            Popover: PopoverStub,
          },
        },
      })

      // 今日（2026-07-15）のセルが月数字に丸背景を持つことを確認（isToday の可視化）
      const todayBadge = wrapper.find('.bg-primary.text-white')
      expect(todayBadge.exists()).toBe(true)
      expect(todayBadge.text()).toBe('15')

      type ExposedGrid = { focusToday: () => void }
      ;(wrapper.vm as unknown as ExposedGrid).focusToday()
      await wrapper.vm.$nextTick()

      // フォーカスは今日のセル（tabindex="-1" の日付セル div）に付与される
      expect(document.activeElement?.getAttribute('tabindex')).toBe('-1')
      expect(document.activeElement?.textContent).toContain('15')

      wrapper.unmount()
    }
    finally {
      vi.useRealTimers()
    }
  })
  /**
   * 検分二巡目 [1] の回帰テスト（月ビュー側）。
   *
   * 日別ポップオーバーを週ビューと共有する ScheduleDayDetailPopover へ集約したことで、
   * 「その日に存在するか」の判定が eventOccupiesDate 一本になった。境界の是正が
   * **共通化後の一箇所で行われ、月ビューにも自動的に効いている**ことをここで実証する。
   */
  it('[1・二巡目] 零時ちょうどに終わる予定は翌日の日別ポップオーバーに混入しない', async () => {
    const single = (id: number, dateStr: string): CalendarEventItem => ({
      id,
      uniqueKey: String(id),
      title: `単日${id}`,
      startAt: `${dateStr}T09:00:00+09:00`,
      endAt: `${dateStr}T10:00:00+09:00`,
      allDay: false,
      color: '#2563EB',
      isPersonal: true,
    })

    const events: CalendarEventItem[] = [
      // 8/3 22:00 〜 8/4 00:00。8/4 には一瞬も存在しない。
      {
        id: 900,
        uniqueKey: '900',
        title: '境界予定',
        startAt: '2026-08-03T22:00:00+09:00',
        endAt: '2026-08-04T00:00:00+09:00',
        allDay: false,
        color: '#DC2626',
        isPersonal: true,
      },
      // 各日に4件ずつ置き、両日で「他N件」を出す（単日は4件以上で溢れる）
      ...[901, 902, 903, 904].map(id => single(id, '2026-08-03')),
      ...[905, 906, 907, 908].map(id => single(id, '2026-08-04')),
    ]

    const wrapper = await mountSuspended(CalendarGrid, {
      props: { year: 2026, month: 8, events },
      global: {
        stubs: {
          Button: true,
          ScheduleTargetAudience: AudienceStub,
          Popover: PopoverStub,
          ScheduleListRow: ScheduleListRowStub,
        },
      },
    })

    const rowIds = () => wrapper.findAll('[data-testid^="popover-row-"]')
      .map(r => r.attributes('data-testid'))

    await wrapper.get('[data-testid="day-overflow-2026-08-03"]').trigger('click')
    expect(rowIds()).toContain('popover-row-900')

    await wrapper.get('[data-testid="day-overflow-2026-08-04"]').trigger('click')
    expect(rowIds()).not.toContain('popover-row-900')
    // 8/4 本来の予定は漏れなく出る（混入を消した副作用で本物まで消していないこと）
    for (const id of [905, 906, 907, 908]) {
      expect(rowIds()).toContain(`popover-row-${id}`)
    }
  })

})
