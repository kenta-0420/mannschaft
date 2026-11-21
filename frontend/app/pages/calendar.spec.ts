// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { computed, ref } from 'vue'
import Page from './calendar.vue'
import DashboardErrorState from '~/components/DashboardErrorState.vue'

// ページ・取得失敗表示は実物。カレンダー取得 composable と通信境界だけ差し替える。
const state = vi.hoisted(() => ({
  refresh: vi.fn(),
  getMyGantt: vi.fn(),
  getPersonalGantt: vi.fn(),
  captureQuiet: vi.fn(),
}))
const loadError = ref(false)
const loading = ref(false)
const calendarLoading = ref(false)

vi.mock('~/composables/useMyCalendarData', () => ({
  PERSONAL_KEY: 'personal',
  layerKey: (type: string, id: number) => `${type}:${id}`,
  useMyCalendarData: () => ({
    currentYear: ref(2026),
    currentMonth: ref(11),
    loading,
    calendarLoading,
    loadError,
    refresh: state.refresh,
    onPrevMonth: vi.fn(),
    onNextMonth: vi.fn(),
    goToToday: vi.fn(),
    navigateTo: vi.fn(),
    extendedEvents: ref([]),
    todosFailed: ref(false),
    layersFailed: ref(false),
    layers: ref([]),
    availableScopes: computed(() => []),
    allScopeOptions: computed(() => []),
    selectedScopes: ref(new Set<string>()),
    filteredEvents: computed(() => []),
    toggleScope: vi.fn(),
    initialize: vi.fn().mockResolvedValue(true),
    view: ref('month'),
    setLayerColor: vi.fn(),
    resetLayerColor: vi.fn(),
  }),
}))
// dayjs の timezone プラグインは Nuxt プラグインで登録されるため、単体では今日の日付だけ固定する。
vi.mock('~/utils/calendarWeek', async (importOriginal) => ({
  ...(await importOriginal<typeof import('~/utils/calendarWeek')>()),
  todayInTimezone: () => '2026-11-21',
}))
mockNuxtImport('definePageMeta', () => () => undefined)
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))
mockNuxtImport('useDatetime', () => () => ({ userTimezone: ref('Asia/Tokyo') }))
mockNuxtImport('useRouter', () => () => ({ push: vi.fn(), replace: vi.fn() }))
mockNuxtImport('useRoute', () => () => ({ query: { tab: 'calendar' }, params: {} }))
mockNuxtImport('useScheduleApi', () => () => ({}))
mockNuxtImport('useTodoGantt', () => () => ({
  getMyGanttTodos: state.getMyGantt,
  getPersonalGanttTodos: state.getPersonalGantt,
  getGanttTodos: state.getMyGantt,
}))
mockNuxtImport('useNotification', () => () => ({ error: vi.fn(), success: vi.fn() }))
mockNuxtImport('useErrorReport', () => () => ({ captureQuiet: state.captureQuiet }))

let wrapper: VueWrapper | undefined
function renderPage(): VueWrapper {
  wrapper = mount(Page, {
    global: {
      components: { DashboardErrorState },
      stubs: {
        PageLoading: true,
        PageHeader: { template: '<header><slot name="actions" /></header>' },
        DashboardWidgetCard: { template: '<div><slot /></div>' },
        Button: {
          props: ['label'],
          emits: ['click'],
          template: '<button v-bind="$attrs" @click="$emit(\'click\')">{{ label }}</button>',
        },
        CalendarGrid: true,
        CalendarWeekGrid: true,
        ScheduleMobileListView: true,
        ScheduleEventForm: true,
        CalendarGuideModal: true,
        EventDetailPanel: true,
        HiddenLayerNotice: true,
        CalendarLayerChips: true,
        TodoGanttView: true,
        Message: true,
        Dialog: true,
        Select: true,
        Skeleton: true,
      },
    },
  })
  return wrapper
}

beforeEach(() => {
  vi.clearAllMocks()
  loadError.value = false
  loading.value = false
  calendarLoading.value = false
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
})

describe('マイカレンダー: 予定の取得失敗', () => {
  it('取得失敗のとき、予定の無いカレンダーに見せず取得失敗の表示を出す', async () => {
    loadError.value = true
    renderPage()
    await flushPromises()

    expect(wrapper!.find('[data-testid="calendar-load-error-state"]').exists()).toBe(true)
    // カレンダー本体（月グリッドを含む領域）は隠れている
    const body = wrapper!.get('[data-testid="calendar-body"]')
    expect(body.attributes('style')).toContain('display: none')
  })

  it('取得に成功しているときはカレンダー本体が隠れない', async () => {
    renderPage()
    await flushPromises()

    expect(wrapper!.get('[data-testid="calendar-body"]').attributes('style') ?? '').not.toContain('display: none')
  })

  it('再試行中は取得失敗の表示を出し続けず、読み込み中表示にする', async () => {
    loadError.value = true
    calendarLoading.value = true
    renderPage()
    await flushPromises()

    expect(wrapper!.find('[data-testid="calendar-load-error-state"]').exists()).toBe(false)
    expect(wrapper!.find('[data-testid="calendar-retrying"]').exists()).toBe(true)
    expect(wrapper!.get('[data-testid="calendar-body"]').attributes('style')).toContain('display: none')
  })

  it('再試行を押すと予定を再取得する', async () => {
    loadError.value = true
    renderPage()
    await flushPromises()

    await wrapper!.get('[data-testid="calendar-load-error-state-retry"]').trigger('click')
    expect(state.refresh).toHaveBeenCalledTimes(1)
  })

  it('取得に成功しているときは取得失敗の表示を出さない', async () => {
    renderPage()
    await flushPromises()

    expect(wrapper!.find('[data-testid="calendar-load-error-state"]').exists()).toBe(false)
  })
})
