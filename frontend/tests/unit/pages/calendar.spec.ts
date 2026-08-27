import { defineComponent, h } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
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
  getMyCalendarLayers: vi.fn(),
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

/**
 * F03.19 W2-a: loadLayers() は作成スコープ選択（availableScopes）の slug 解決に
 * team/organization ストア（useTeamStore/useOrganizationStore）を使う。
 *
 * `mountSuspended` は実 Nuxt アプリ（@pinia/nuxt 経由）の Pinia インスタンスを使う。
 * テスト側で `setActivePinia(createPinia())` して作ったストアはそれとは別物になり、
 * `useTeamStore().myTeams = [...]` で事前投入しても mount 後のコンポーネント側からは
 * 見えない（空のまま）。既存の `useAuthStore` 差し替え例（feature-gate.global.spec.ts）に
 * 倣い、auto-import 自体を `mockNuxtImport` で差し替える。
 */
const teamStoreStub = { myTeams: [] as Array<{ id: number, slug: string, name: string, nickname1: string | null, iconUrl: string | null, role: string, template: string, memberCount: number }>, fetchMyTeams: vi.fn() }
const orgStoreStub = { myOrganizations: [] as Array<{ id: number, slug: string, name: string }>, fetchMyOrganizations: vi.fn() }
mockNuxtImport('useTeamStore', () => () => teamStoreStub)
mockNuxtImport('useOrganizationStore', () => () => orgStoreStub)

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

// F03.19 W2-a: 作成スコープ・レイヤーチップは GET /me/calendar-layers 由来。
// scopeId は数値、slug は別経路（team ストア）から補われる（useMyCalendarData.ts 参照）。
const layersFixture = [
  { scopeType: 'PERSONAL', scopeId: 0, scopeName: '個人', scopeNameKey: 'schedule.calendar.layer.personal', scopeIconUrl: null, color: '#059669', colorSource: 'LAYER_AUTO', hidden: false },
  { scopeType: 'TEAM', scopeId: 1, scopeName: 'チームA', scopeNameKey: null, scopeIconUrl: null, color: '#2563eb', colorSource: 'LAYER_AUTO', hidden: false },
]

function presetTeamStore() {
  teamStoreStub.myTeams = [
    { id: 1, slug: 't1', name: 'チームA', nickname1: null, iconUrl: null, role: 'MEMBER', template: 'default', memberCount: 1 },
  ]
  teamStoreStub.fetchMyTeams.mockReset().mockResolvedValue(undefined)
  orgStoreStub.myOrganizations = []
  orgStoreStub.fetchMyOrganizations.mockReset().mockResolvedValue(undefined)
}

function teamCalendarEntry() {
  return {
    id: 5,
    scheduleId: 5,
    content: { title: 'チーム予定', eventType: 'SCHEDULE', status: 'PUBLISHED' },
    time: { startAt: '2026-07-10T10:00:00+09:00', endAt: '2026-07-10T11:00:00+09:00', allDay: false },
    // F03.19 W2-a: scope.scopeId はレイヤーAPIと同じ数値スコープID（文字列化）。
    // slug（画面URL・詳細API用）は別フィールド scopeSlug で渡す（useMyCalendarData.ts 参照）。
    // これを合わせないと、このイベントの eventLayerKey（'TEAM:1'）がレイヤー一覧のキーと
    // 一致せず「フォールバックチップ」として別枠に複製表示されてしまう。
    scope: { scopeType: 'TEAM', scopeId: '1', scopeSlug: 't1', scopeName: 'チームA', scopeIconUrl: null },
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
        // F03.19 §6.8（Wave 3-c）: モバイル用リストは常に DOM 上に存在する（CSS の md:hidden で
        // 出し分けるだけで jsdom 上は非表示にならない）。既存 AC-11 系テストのボタン探索
        // （wrapper.findAll('button')）に無関係な要素が混ざらないようスタブ化する。
        ScheduleMobileListView: true,
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
    scheduleApiMock.getMyCalendarLayers.mockReset().mockResolvedValue({ data: layersFixture })
    ganttApiMock.getMyCalendarTodos.mockReset().mockResolvedValue(emptyTodos)
    presetTeamStore()
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

    // 個人チップも変化しない（選択されたまま）。
    // F03.19 W2-a との統合修繕: 個人レイヤーの表示名は本戦役から `schedule.calendar.layer.personal`
    // 経由の翻訳になった（旧実装は '個人' 直書き）。テスト環境の既定ロケールは en のため 'Personal'。
    const personalChip = chipFor('Personal')!
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
    scheduleApiMock.getMyCalendarLayers.mockReset().mockResolvedValue({ data: layersFixture })
    ganttApiMock.getMyCalendarTodos.mockReset().mockResolvedValue(emptyTodos)
    presetTeamStore()
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

  // [P2回帰・検分四巡目] 案内が消えた後（＝対象キーが破棄された後）、同じレイヤーを再び
  // 非表示にしても、何も保存していないのに古い案内が「ゾンビ」として復活してはならない。
  // 前回の修正は computed で表示可否を導出するだけで、対象キー自体（hiddenLayerNoticeScopeKey）
  // を破棄していなかったため、この経路で再現していた。
  it('[P2回帰・ゾンビ案内] 表示に戻した後に再び非表示にしても、保存操作なしで案内は復活しない', async () => {
    const wrapper = await mountCalendarPage()
    const chipFor = (label: string) =>
      wrapper.findAll('button').find(b => b.text() === label)

    // チームAを非表示にしてから作成 → 案内が出る
    await chipFor('チームA')!.trigger('click')
    await wrapper.vm.$nextTick()

    const createForm = wrapper.findComponent({ name: 'ScheduleEventForm' })
    createForm.vm.$emit('saved', { isPersonal: false, scopeType: 'team', scopeId: 't1' })
    await flushPromises()
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-testid="hidden-layer-notice"]').exists()).toBe(true)

    // 手で表示に戻す（案内は消える）
    await chipFor('チームA')!.trigger('click')
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-testid="hidden-layer-notice"]').exists()).toBe(false)

    // 再びチームAを非表示にする（保存操作は一切していない）
    await chipFor('チームA')!.trigger('click')
    await wrapper.vm.$nextTick()

    // 案内が復活してはならない（対象キー自体が破棄されているはず）
    expect(wrapper.find('[data-testid="hidden-layer-notice"]').exists()).toBe(false)
  })

  /**
   * [P2是正・Codex検分] `savedScopeFilterKey()` が「表示名（label）」でスコープを
   * 突き合わせていたため、`TeamEntity` にチーム名の一意制約が無いこと（一意制約なし。
   * `TeamEntity.java` 参照）と噛み合わさり、同名の別チームに複数所属していると
   * 常に**先頭のチーム**の状態を見てしまっていた（誤対応）。
   *
   * このテストは「チームB」という同名チームが2つ（id=10・id=20）ある状態を再現し、
   * **2つ目（id=20）** を作成先に選んだ場合でも、1つ目（id=10）ではなく正しく
   * 2つ目のレイヤーが判定・表示切り替えされることを検証する。名前が一意という
   * 前提のテストデータでは検出できないため、意図的に同名の2チームを用意している。
   */
  it('[P2回帰・同名チーム] 同名の別チームが複数所属にあっても、実際に保存された方（2つ目）のレイヤーで判定される', async () => {
    const sameNameLayers = [
      { scopeType: 'PERSONAL', scopeId: 0, scopeName: '個人', scopeNameKey: 'schedule.calendar.layer.personal', scopeIconUrl: null, color: '#059669', colorSource: 'LAYER_AUTO', hidden: false },
      { scopeType: 'TEAM', scopeId: 10, scopeName: 'チームB', scopeNameKey: null, scopeIconUrl: null, color: '#2563eb', colorSource: 'LAYER_AUTO', hidden: false },
      { scopeType: 'TEAM', scopeId: 20, scopeName: 'チームB', scopeNameKey: null, scopeIconUrl: null, color: '#dc2626', colorSource: 'LAYER_AUTO', hidden: false },
    ]
    scheduleApiMock.getMyCalendarLayers.mockReset().mockResolvedValue({ data: sameNameLayers })
    scheduleApiMock.getCalendarRange.mockReset().mockResolvedValue({ data: [] })
    teamStoreStub.myTeams = [
      { id: 10, slug: 'team-b-first', name: 'チームB', nickname1: null, iconUrl: null, role: 'MEMBER', template: 'default', memberCount: 1 },
      { id: 20, slug: 'team-b-second', name: 'チームB', nickname1: null, iconUrl: null, role: 'MEMBER', template: 'default', memberCount: 1 },
    ]

    const wrapper = await mountCalendarPage()
    // 「チームB」ラベルのチップは2枚存在する（1枚目=id10, 2枚目=id20。allScopeOptions は
    // layers.value の並び順＝レイヤーAPI応答順をそのまま反映するため、この順序で決め打ちできる）。
    const chipsFor = (label: string) => wrapper.findAll('button').filter(b => b.text() === label)
    const chips = chipsFor('チームB')
    expect(chips).toHaveLength(2)
    const [firstTeamChip, secondTeamChip] = chips

    // 2つ目（id20）のチップだけを非表示にする。1つ目（id10）は表示したままにしておく
    // （もし誤って1つ目を判定してしまうバグが復活すれば、1つ目は既に表示済みのため
    // 「案内が出ない」という形で検出できる）。
    await secondTeamChip!.trigger('click')
    await wrapper.vm.$nextTick()
    expect(firstTeamChip!.classes()).toContain('border-primary')
    expect(secondTeamChip!.classes()).not.toContain('border-primary')

    // 作成スコープ Select で2つ目のチーム（slug=team-b-second）を選ぶ
    const select = wrapper.get('[data-testid="create-scope-select"]')
    await select.setValue('TEAM:team-b-second')
    await wrapper.vm.$nextTick()

    // 実際に2つ目のスコープへ保存された、という結果を saved の引数で再現する
    const createForm = wrapper.findComponent({ name: 'ScheduleEventForm' })
    createForm.vm.$emit('saved', { isPersonal: false, scopeType: 'team', scopeId: 'team-b-second' })
    await flushPromises()
    await wrapper.vm.$nextTick()

    // 案内が出ること（＝2つ目が非表示だと正しく判定できている。表示名だけで
    // 突き合わせる旧実装は常に1つ目＝表示済みを見てしまい、ここで案内が出ない誤りになる）
    expect(wrapper.find('[data-testid="hidden-layer-notice"]').exists()).toBe(true)

    // 「表示する」を押すと、2つ目のチップだけが表示状態になる（1つ目は最初から表示のまま不変）
    await wrapper.get('[data-testid="hidden-layer-show-button"]').trigger('click')
    await wrapper.vm.$nextTick()

    const chipsAfter = chipsFor('チームB')
    expect(chipsAfter[0]!.classes()).toContain('border-primary')
    expect(chipsAfter[1]!.classes()).toContain('border-primary')
    expect(wrapper.find('[data-testid="hidden-layer-notice"]').exists()).toBe(false)
  })
})
