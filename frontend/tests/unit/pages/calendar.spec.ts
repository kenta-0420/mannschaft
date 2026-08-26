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
    // useMyCalendarData の scopeFilter は localStorage に永続化される。jsdom の localStorage は
    // テストファイル内で使い回されるため、前のテストの選択状態が漏れて初期状態が狂わないよう
    // 各テストの開始時に必ずクリアする。
    localStorage.clear()
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

/**
 * AC-11b（§5.4）回帰テスト。
 *
 * 背景（マスター裁定により本 task に追加）: AC-11 の結合切りにより作成スコープを変えても
 * 表示フィルタは変わらなくなった。その結果、作成先のレイヤーが表示フィルタで非表示のまま
 * 予定を作成すると、作った予定が何の説明も無く現れないという新たな不具合が起こりうる。
 * この案内（「作成先のレイヤーが非表示です」＋「表示する」ボタン）が正しく機能することを検証する。
 */
describe('pages/calendar.vue: AC-11b 作成先レイヤー非表示時の案内', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    scheduleApiMock.listPersonalSchedules.mockReset().mockResolvedValue(emptyPersonal)
    scheduleApiMock.getCalendarRange.mockReset().mockResolvedValue({ data: [teamCalendarEntry()] })
    ganttApiMock.getMyCalendarTodos.mockReset().mockResolvedValue(emptyTodos)
  })

  it('AC-11b: 作成先レイヤーが非表示のまま予定を作成すると案内が出て、押すとそのレイヤーだけ選択状態になる', async () => {
    const wrapper = await mountCalendarPage()
    const chipFor = (label: string) =>
      wrapper.findAll('button').find(b => b.text() === label)

    // チームAのレイヤーチップを非表示にする
    await chipFor('チームA')!.trigger('click')
    await wrapper.vm.$nextTick()
    expect(chipFor('チームA')!.classes()).not.toContain('border-primary')

    // 作成スコープをチームAへ変更してから保存する
    const select = wrapper.get('[data-testid="create-scope-select"]')
    await select.setValue('TEAM:t1')
    await wrapper.vm.$nextTick()

    // 案内はまだ出ていない（保存前）
    expect(wrapper.find('[data-testid="hidden-layer-notice"]').exists()).toBe(false)

    // 作成フォームの保存完了を模擬する（実際に保存されたスコープを saved の引数で渡す）
    const createForm = wrapper.findComponent({ name: 'ScheduleEventForm' })
    expect(createForm.exists()).toBe(true)
    createForm.vm.$emit('saved', { isPersonal: false, scopeType: 'team', scopeId: 't1' })
    await flushPromises()
    await wrapper.vm.$nextTick()

    // 案内が現れる。表示フィルタはまだ書き換わっていない（P2: 押すまで変えない）
    const notice = wrapper.get('[data-testid="hidden-layer-notice"]')
    expect(notice.text()).toContain('チームA')
    expect(chipFor('チームA')!.classes()).not.toContain('border-primary')

    // 「表示する」ボタンを押すと、そのレイヤーだけが選択状態になる
    await wrapper.get('[data-testid="hidden-layer-show-button"]').trigger('click')
    await wrapper.vm.$nextTick()

    expect(chipFor('チームA')!.classes()).toContain('border-primary')
    expect(wrapper.find('[data-testid="hidden-layer-notice"]').exists()).toBe(false)
  })

  it('AC-11b: 作成先レイヤーが既に表示されている場合は案内を出さない', async () => {
    const wrapper = await mountCalendarPage()

    // 初期状態は個人・チームAとも表示済み。個人スコープで保存する
    const createForm = wrapper.findComponent({ name: 'ScheduleEventForm' })
    createForm.vm.$emit('saved', { isPersonal: true, scopeType: 'team', scopeId: '' })
    await flushPromises()
    await wrapper.vm.$nextTick()

    expect(wrapper.find('[data-testid="hidden-layer-notice"]').exists()).toBe(false)
  })

  // [P2是正・検分三巡目] ページ上部の作成スコープ Select（selectedCreateScope）と、
  // ScheduleEventForm 内で実際に選ばれ保存されたスコープが食い違う状態を再現する。
  // 以前は上部の値だけを見ていたため、この経路では案内が一切出なかった（無言で消える）。
  it('[P2回帰] 上部は「個人」のまま、フォーム内で非表示のチームを選んで保存すると、そのチームの案内が出る', async () => {
    const wrapper = await mountCalendarPage()
    const chipFor = (label: string) =>
      wrapper.findAll('button').find(b => b.text() === label)

    // チームAのレイヤーチップを非表示にする
    await chipFor('チームA')!.trigger('click')
    await wrapper.vm.$nextTick()
    expect(chipFor('チームA')!.classes()).not.toContain('border-primary')

    // 作成スコープ Select は「個人」のまま変更しない（上部とフォーム内の食い違いを作る）
    expect(wrapper.get<HTMLSelectElement>('[data-testid="create-scope-select"]').element.value).toBe('personal')

    // フォーム内でチームAへ変更して保存した、という結果を saved の引数で再現する
    const createForm = wrapper.findComponent({ name: 'ScheduleEventForm' })
    createForm.vm.$emit('saved', { isPersonal: false, scopeType: 'team', scopeId: 't1' })
    await flushPromises()
    await wrapper.vm.$nextTick()

    // 上部が「個人」のままでも、実際に保存されたチームAの案内が出る（無言で消えない）
    const notice = wrapper.get('[data-testid="hidden-layer-notice"]')
    expect(notice.text()).toContain('チームA')
  })

  // [P3是正・検分三巡目] 案内が出た後、ユーザーが自分でレイヤーチップから表示に戻した場合、
  // 「表示する」ボタンを押していなくても案内は消える（既に見えているのに「見えません」と
  // 言い続けない）。
  it('[P3回帰] 案内が出た後にレイヤーチップで自分で表示に戻すと、案内は自動的に消える', async () => {
    const wrapper = await mountCalendarPage()
    const chipFor = (label: string) =>
      wrapper.findAll('button').find(b => b.text() === label)

    await chipFor('チームA')!.trigger('click')
    await wrapper.vm.$nextTick()

    const createForm = wrapper.findComponent({ name: 'ScheduleEventForm' })
    createForm.vm.$emit('saved', { isPersonal: false, scopeType: 'team', scopeId: 't1' })
    await flushPromises()
    await wrapper.vm.$nextTick()

    expect(wrapper.find('[data-testid="hidden-layer-notice"]').exists()).toBe(true)

    // 「表示する」ボタンではなく、レイヤーチップを自分でクリックして表示に戻す
    await chipFor('チームA')!.trigger('click')
    await wrapper.vm.$nextTick()

    expect(chipFor('チームA')!.classes()).toContain('border-primary')
    expect(wrapper.find('[data-testid="hidden-layer-notice"]').exists()).toBe(false)
  })
})
