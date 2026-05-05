import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { setActivePinia, createPinia } from 'pinia'
import { defineComponent, h } from 'vue'
import WidgetScheduleCalendar from '~/components/widgets/WidgetScheduleCalendar.vue'

/**
 * ダッシュボード新ウィジェット {@code WidgetScheduleCalendar.vue} のユニットテスト。
 *
 * <ul>
 *   <li>初期表示で {@code listSchedules} が呼ばれ、当月の events が CalendarGrid に渡る</li>
 *   <li>次月ボタンクリックで currentMonth が進み、再フェッチされる</li>
 *   <li>API エラー時に events が空配列になり captureQuiet が呼ばれる</li>
 * </ul>
 *
 * 子コンポーネントの {@code CalendarGrid} はスタブ化し、props と emit ハンドラ
 * のみを検証する。
 */

// === Mocks ===
const scheduleApiMock = {
  listSchedules: vi.fn(),
  getMySchedules: vi.fn(),
}
const captureQuietMock = vi.fn()

vi.mock('~/composables/useScheduleApi', () => ({
  useScheduleApi: () => scheduleApiMock,
}))

vi.mock('~/composables/useErrorReport', () => ({
  useErrorReport: () => ({
    state: { value: {} },
    capture: vi.fn(),
    captureQuiet: captureQuietMock,
    submitComment: vi.fn(),
    close: vi.fn(),
  }),
}))

/**
 * CalendarGrid のスタブ。受け取った props を data-* 属性に流して
 * テスト側から検証可能にする。prev/next ボタンを露出させて emit を確認する。
 */
const CalendarGridStub = defineComponent({
  name: 'CalendarGrid',
  props: {
    year: { type: Number, required: true },
    month: { type: Number, required: true },
    events: { type: Array, required: true },
  },
  emits: ['prevMonth', 'nextMonth', 'dateClick', 'eventClick'],
  setup(props, { emit }) {
    return () =>
      h(
        'div',
        {
          'data-testid': 'calendar-grid-stub',
          'data-year': String(props.year),
          'data-month': String(props.month),
          'data-event-count': String(props.events.length),
        },
        [
          h(
            'button',
            { 'data-testid': 'stub-prev', onClick: () => emit('prevMonth') },
            'prev',
          ),
          h(
            'button',
            { 'data-testid': 'stub-next', onClick: () => emit('nextMonth') },
            'next',
          ),
        ],
      )
  },
})

const emptyResponse = { data: [], meta: { page: 0, size: 0, totalElements: 0, totalPages: 0 } }

beforeEach(() => {
  setActivePinia(createPinia())
  scheduleApiMock.listSchedules.mockReset()
  scheduleApiMock.getMySchedules.mockReset()
  captureQuietMock.mockReset()
})

/** 当月の月初〜月末範囲を組み立てるヘルパー (コンポーネント側と同じロジック)。 */
function expectedMonthRange(year: number, month: number) {
  const pad = (n: number) => String(n).padStart(2, '0')
  const lastDay = new Date(year, month, 0).getDate()
  return {
    from: `${year}-${pad(month)}-01T00:00:00`,
    to: `${year}-${pad(month)}-${pad(lastDay)}T23:59:59`,
  }
}

describe('WidgetScheduleCalendar.vue', () => {
  it('初期表示で当月の listSchedules と getMySchedules が呼ばれ events が CalendarGrid に渡る', async () => {
    // startAt を現在月に合わせてフィルタ通過させる
    const now = new Date()
    const pad = (n: number) => String(n).padStart(2, '0')
    const currentYearMonth = `${now.getFullYear()}-${pad(now.getMonth() + 1)}`
    scheduleApiMock.listSchedules.mockResolvedValueOnce({
      data: [
        {
          id: 1,
          title: 'ミーティング',
          startAt: `${currentYearMonth}-10T10:00:00`,
          endAt: `${currentYearMonth}-10T11:00:00`,
          allDay: false,
          color: '#6366f1',
        },
        {
          id: 2,
          title: '社内研修',
          startAt: `${currentYearMonth}-15T13:00:00`,
          endAt: `${currentYearMonth}-15T15:00:00`,
          allDay: false,
          color: null,
        },
      ],
      meta: { page: 0, size: 50, totalElements: 2, totalPages: 1 },
    })
    scheduleApiMock.getMySchedules.mockResolvedValueOnce(emptyResponse)

    const wrapper = await mountSuspended(WidgetScheduleCalendar, {
      props: { scopeType: 'team', scopeId: 42 },
      global: { stubs: { CalendarGrid: CalendarGridStub, Skeleton: true } },
    })

    // onMounted のフェッチ完了を待つ
    await new Promise((resolve) => setTimeout(resolve, 0))
    await wrapper.vm.$nextTick()

    expect(scheduleApiMock.listSchedules).toHaveBeenCalledTimes(1)
    expect(scheduleApiMock.getMySchedules).toHaveBeenCalledTimes(1)

    // cacheHalfMonths: 2 のため、当月 ±2ヶ月の範囲でフェッチされる
    const cacheHalfMonths = 2
    const startOffset = new Date(now.getFullYear(), now.getMonth() - cacheHalfMonths, 1)
    const endOffset = new Date(now.getFullYear(), now.getMonth() + cacheHalfMonths, 1)
    const expectedFrom = expectedMonthRange(startOffset.getFullYear(), startOffset.getMonth() + 1)
    const expectedTo = expectedMonthRange(endOffset.getFullYear(), endOffset.getMonth() + 1)
    const callArgs = scheduleApiMock.listSchedules.mock.calls[0]!
    expect(callArgs[0]).toBe('team')
    expect(callArgs[1]).toBe(42)
    // from/to は YYYY-MM-DDTHH:mm:ss 形式 (19 文字) で渡されること (F01.2 規約)
    expect(callArgs[2].from).toBe(expectedFrom.from)
    expect(callArgs[2].to).toBe(expectedTo.to)
    expect(callArgs[2].from).toHaveLength(19)
    expect(callArgs[2].to).toHaveLength(19)

    const stub = wrapper.find('[data-testid="calendar-grid-stub"]')
    expect(stub.exists()).toBe(true)
    // events は scopeRes の2件のみ（personalRes は空）
    expect(stub.attributes('data-event-count')).toBe('2')
    expect(stub.attributes('data-year')).toBe(String(now.getFullYear()))
    expect(stub.attributes('data-month')).toBe(String(now.getMonth() + 1))
  })

  it('次月ボタンクリックで currentMonth が進み再フェッチされる', async () => {
    scheduleApiMock.listSchedules.mockResolvedValue({
      data: [],
      meta: { page: 0, size: 50, totalElements: 0, totalPages: 0 },
    })
    scheduleApiMock.getMySchedules.mockResolvedValue(emptyResponse)

    const wrapper = await mountSuspended(WidgetScheduleCalendar, {
      props: { scopeType: 'organization', scopeId: 7 },
      global: { stubs: { CalendarGrid: CalendarGridStub, Skeleton: true } },
    })

    await new Promise((resolve) => setTimeout(resolve, 0))
    await wrapper.vm.$nextTick()
    expect(scheduleApiMock.listSchedules).toHaveBeenCalledTimes(1)

    const now = new Date()
    const initialYear = now.getFullYear()
    const initialMonth = now.getMonth() + 1

    // 次月ボタンを叩く
    await wrapper.find('[data-testid="stub-next"]').trigger('click')
    await new Promise((resolve) => setTimeout(resolve, 0))
    await wrapper.vm.$nextTick()

    // cacheHalfMonths: 2 のため次月はキャッシュ内 → 再フェッチは発生しない
    expect(scheduleApiMock.listSchedules).toHaveBeenCalledTimes(1)

    // 期待される次月 (12 月 → 翌年 1 月の繰り上げを含む)
    const nextMonth = initialMonth === 12 ? 1 : initialMonth + 1
    const nextYear = initialMonth === 12 ? initialYear + 1 : initialYear

    const stub = wrapper.find('[data-testid="calendar-grid-stub"]')
    expect(stub.attributes('data-year')).toBe(String(nextYear))
    expect(stub.attributes('data-month')).toBe(String(nextMonth))
  })

  it('API エラー時に events が空配列になり captureQuiet が呼ばれる', async () => {
    const apiError = new Error('boom')
    scheduleApiMock.listSchedules.mockRejectedValueOnce(apiError)
    scheduleApiMock.getMySchedules.mockResolvedValueOnce(emptyResponse)

    const wrapper = await mountSuspended(WidgetScheduleCalendar, {
      props: { scopeType: 'team', scopeId: 99 },
      global: { stubs: { CalendarGrid: CalendarGridStub, Skeleton: true } },
    })

    await new Promise((resolve) => setTimeout(resolve, 0))
    await wrapper.vm.$nextTick()

    expect(captureQuietMock).toHaveBeenCalledTimes(1)
    expect(captureQuietMock).toHaveBeenCalledWith(
      apiError,
      expect.objectContaining({ context: expect.stringContaining('WidgetScheduleCalendar') }),
    )

    const stub = wrapper.find('[data-testid="calendar-grid-stub"]')
    expect(stub.exists()).toBe(true)
    expect(stub.attributes('data-event-count')).toBe('0')
  })
})
