import { defineComponent, h } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import CalendarPage from '~/pages/calendar.vue'

/**
 * F03.19 統合カレンダービュー Wave 2-b の AC-11 回帰テスト。
 *
 * 背景（検分指摘 [1]）: `pages/calendar.vue` の `watch(createScopeKey, ...)` が
 * 依然として `selectedScopes`（表示中のレイヤーチップの選択状態）を上書きしていた。
 * AC-11: 「作成スコープ Select を『個人』から任意のチームへ変更しても、表示中のレイヤー
 * チップの選択状態が一切変化しない」（§5.4/§9）。
 *
 * `selectedScopes` は script setup のクロージャ内にあり外部から直接参照できないため、
 * DOM 上のレイヤーチップの選択スタイル（`border-primary` 系クラスの有無）を通じて
 * 間接的に検証する。
 */

const scheduleApiMock = {
  listPersonalSchedules: vi.fn(),
  getCalendarRange: vi.fn(),
  getMyScheduleDetail: vi.fn(),
  getSchedule: vi.fn(),
  deleteSchedule: vi.fn(),
  deletePersonalSchedule: vi.fn(),
}
const ganttApiMock = {
  getMyCalendarTodos: vi.fn(),
  getPersonalGanttTodos: vi.fn(),
  getGanttTodos: vi.fn(),
}
const notificationMock = { success: vi.fn(), error: vi.fn(), warn: vi.fn() }

vi.mock('~/composables/useScheduleApi', () => ({ useScheduleApi: () => scheduleApiMock }))
vi.mock('~/composables/useTodoGantt', () => ({ useTodoGantt: () => ganttApiMock }))
vi.mock('~/composables/useNotification', () => ({ useNotification: () => notificationMock }))

const CalendarGridStub = defineComponent({
  name: 'CalendarGrid',
  props: { year: Number, month: Number, events: Array },
  emits: ['prevMonth', 'nextMonth', 'dateClick', 'eventClick', 'reflectionClick', 'today'],
  setup() {
    return () => h('div', { 'data-testid': 'calendar-grid-stub' })
  },
})

// PrimeVue Select の軽量スタブ。作成スコープ Select の値変更を素の <select> で再現する。
const SelectStub = defineComponent({
  name: 'Select',
  props: {
    modelValue: String,
    options: { type: Array, default: () => [] },
    optionLabel: String,
    optionValue: String,
  },
  emits: ['update:modelValue'],
  setup(props, { emit }) {
    return () =>
      h(
        'select',
        {
          'data-testid': 'create-scope-select',
          value: props.modelValue,
          onChange: (e: Event) => emit('update:modelValue', (e.target as HTMLSelectElement).value),
        },
        (props.options as Array<Record<string, unknown>>).map(opt =>
          h('option', { value: opt[props.optionValue as string] as string }, String(opt[props.optionLabel as string])),
        ),
      )
  },
})

const emptyPersonal = { data: [] }
const emptyTodos = { data: [] }

function teamCalendarEntry() {
  return {
    id: 5,
    scheduleId: 5,
    content: { title: 'チーム予定', eventType: 'SCHEDULE', status: 'PUBLISHED' },
    time: { startAt: '2026-07-10T10:00:00+09:00', endAt: '2026-07-10T11:00:00+09:00', allDay: false },
    scope: { scopeType: 'TEAM', scopeId: 't1', scopeName: 'チームA', scopeIconUrl: null },
    myAttendanceStatus: 'UNDECIDED',
  }
}

async function mountCalendarPage() {
  const wrapper = await mountSuspended(CalendarPage, {
    global: {
      stubs: {
        CalendarGrid: CalendarGridStub,
        Select: SelectStub,
        Button: true,
        DashboardWidgetCard: true,
        SectionCard: true,
        DashboardEmptyState: true,
        Message: true,
        Skeleton: true,
        ProgressSpinner: true,
        MultiSelect: true,
        ScheduleEventForm: true,
        EventDetailPanel: true,
        CalendarGuideModal: true,
        TodoGanttView: true,
      },
    },
  })
  await flushPromises()
  await wrapper.vm.$nextTick()
  return wrapper
}

describe('pages/calendar.vue: AC-11 作成スコープと表示フィルタの分離', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    scheduleApiMock.listPersonalSchedules.mockReset().mockResolvedValue(emptyPersonal)
    scheduleApiMock.getCalendarRange.mockReset().mockResolvedValue({ data: [teamCalendarEntry()] })
    ganttApiMock.getMyCalendarTodos.mockReset().mockResolvedValue(emptyTodos)
  })

  it('AC-11: 作成スコープ Select を「個人」からチームへ変更しても、表示中のレイヤーチップの選択状態が変化しない', async () => {
    const wrapper = await mountCalendarPage()

    // 初期状態: 個人・チームA の両チップが選択済み（allScopeOptions 初期化ロジックにより全選択）
    const chipFor = (label: string) =>
      wrapper.findAll('button').find(b => b.text() === label)

    const teamChipBefore = chipFor('チームA')
    expect(teamChipBefore).toBeTruthy()
    expect(teamChipBefore!.classes()).toContain('border-primary')

    // ユーザーがチームAのレイヤーチップを非表示にする（表示フィルタから外す）
    await teamChipBefore!.trigger('click')
    await wrapper.vm.$nextTick()

    const teamChipAfterToggle = chipFor('チームA')!
    expect(teamChipAfterToggle.classes()).not.toContain('border-primary')
    expect(teamChipAfterToggle.classes()).toContain('border-surface-300')

    // 作成スコープ Select を「個人」からチームAへ変更する
    const select = wrapper.get('[data-testid="create-scope-select"]')
    await select.setValue('TEAM:t1')
    await wrapper.vm.$nextTick()

    // AC-11: 表示中のレイヤーチップの選択状態（チームAが非選択のまま）は一切変化しない
    const teamChipAfterScopeChange = chipFor('チームA')!
    expect(teamChipAfterScopeChange.classes()).not.toContain('border-primary')
    expect(teamChipAfterScopeChange.classes()).toContain('border-surface-300')

    // 個人チップも変化しない（選択されたまま）
    const personalChip = chipFor('個人')!
    expect(personalChip.classes()).toContain('border-primary')
  })
})
