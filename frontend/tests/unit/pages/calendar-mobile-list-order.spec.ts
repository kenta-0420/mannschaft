import { defineComponent, h } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import CalendarPage from '~/pages/calendar.vue'

/**
 * F03.19 §6.8（Wave 3-c）Codex 検分指摘 [1] の回帰テスト。
 *
 * 背景: モバイルのリストビュー（ScheduleMobileListView 経由）の並べ替えが
 * `startAt`（ISO 文字列）の `localeCompare` になっていた。時差の異なる予定を混ぜると、
 * 文字列としての大小関係と実際の時系列が食い違う。
 *
 * 例: `2026-08-04T09:00:00+09:00`（Asia/Tokyo 朝9時 = 実時刻 2026-08-04T00:00:00Z）と
 * `2026-08-04T01:00:00Z`（実時刻 2026-08-04T01:00:00Z）を比較すると、前者の方が実際には
 * 1時間早い。しかし ISO 文字列のまま `localeCompare` すると、時刻部分の先頭桁
 * （'0' の次が '9' か '1' か）だけで大小が決まり、後者（実際には遅い方）が先に並んでしまう。
 *
 * 同じ時差の予定だけを使ったテストではこの欠陥は捕まらないため、意図的に時差の異なる
 * 2件（+09:00 と Z）を混在させて検証する。
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

// ScheduleListRow の代わりに、順序検証だけができる最小スタブを使う
// （ScheduleMobileListView 自体は実物のまま使い、並べ替えロジックを実際に通す）。
const ScheduleListRowStub = defineComponent({
  name: 'ScheduleListRow',
  props: { event: Object },
  setup(props) {
    return () => h('div', { 'data-testid': 'row-title' }, (props.event as { title: string }).title)
  },
})

const emptyPersonal = { data: [] }
const emptyTodos = { data: [] }

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

/**
 * 「実際には早い」方（Aイベント）と「実際には遅い」方（Bイベント）を、意図的に
 * ISO 文字列としての大小関係が実時刻と逆になるよう組み立てる。
 */
function mixedOffsetEntries() {
  // カレンダーの初期表示月に合わせる。固定日にすると実行月が変わった
  // 瞬間にイベントが表示範囲外になり、並び順ではなく空配列を検査してしまう。
  const now = new Date()
  const date = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-04`
  return [
    {
      id: 1,
      scheduleId: 1,
      content: { title: 'Bイベント（実際には遅い・文字列としては早い）', eventType: 'SCHEDULE', status: 'PUBLISHED' },
      // 実時刻: 2026-08-04T01:00:00Z
      time: { startAt: `${date}T01:00:00Z`, endAt: `${date}T02:00:00Z`, allDay: false },
      scope: { scopeType: 'TEAM', scopeId: '1', scopeSlug: 't1', scopeName: 'チームA', scopeIconUrl: null },
      myAttendanceStatus: 'UNDECIDED',
    },
    {
      id: 2,
      scheduleId: 2,
      content: { title: 'Aイベント（実際には早い・文字列としては遅い）', eventType: 'SCHEDULE', status: 'PUBLISHED' },
      // 実時刻: 2026-08-04T00:00:00Z（+09:00 表記のため文字列上の時刻部分は '09' で '01' より大きい）
      time: { startAt: `${date}T09:00:00+09:00`, endAt: `${date}T10:00:00+09:00`, allDay: false },
      scope: { scopeType: 'TEAM', scopeId: '1', scopeSlug: 't1', scopeName: 'チームA', scopeIconUrl: null },
      myAttendanceStatus: 'UNDECIDED',
    },
  ]
}

async function mountCalendarPage() {
  const wrapper = await mountSuspended(CalendarPage, {
    global: {
      stubs: {
        CalendarGrid: CalendarGridStub,
        ScheduleListRow: ScheduleListRowStub,
        Select: true,
        Button: true,
        DashboardWidgetCard: true,
        SectionCard: false,
        DashboardEmptyState: false,
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

describe('pages/calendar.vue: モバイルリストの並べ替え（時差混在の回帰）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    scheduleApiMock.listPersonalSchedules.mockReset().mockResolvedValue(emptyPersonal)
    scheduleApiMock.getCalendarRange.mockReset().mockResolvedValue({ data: mixedOffsetEntries() })
    scheduleApiMock.getMyCalendarLayers.mockReset().mockResolvedValue({ data: layersFixture })
    ganttApiMock.getMyCalendarTodos.mockReset().mockResolvedValue(emptyTodos)
    presetTeamStore()
  })

  it('時差の異なる予定が混在しても、モバイルの一覧は実際の時系列順（ISO 文字列の辞書順ではない）に並ぶ', async () => {
    const wrapper = await mountCalendarPage()

    const titles = wrapper.findAll('[data-testid="row-title"]').map(el => el.text())

    // 実際の時系列: Aイベント（00:00Z）が先、Bイベント（01:00Z）が後。
    // ISO 文字列の localeCompare のままだと Bイベントが先に来てしまう（旧実装の欠陥）。
    expect(titles).toEqual([
      'Aイベント（実際には早い・文字列としては遅い）',
      'Bイベント（実際には遅い・文字列としては早い）',
    ])
  })
})
