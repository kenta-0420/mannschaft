import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { ref } from 'vue'
import WidgetMyCalendar from './WidgetMyCalendar.vue'
import type { CalendarEventItem } from '~/composables/useCalendarEvents'

const state = {
  currentYear: ref(2026), currentMonth: ref(8), loading: ref(false), calendarLoading: ref(false), loadError: ref(false),
  filteredEvents: ref<CalendarEventItem[]>([]), extendedEvents: ref([]), allScopeOptions: ref([]),
  selectedScopes: ref([]), multiSelectScopes: ref([]),
  loadEvents: vi.fn(), refresh: vi.fn(), initStorage: vi.fn(),
  onPrevMonth: vi.fn(), onNextMonth: vi.fn(), toggleScope: vi.fn(),
}
vi.mock('~/composables/useMyCalendarData', () => ({ useMyCalendarData: () => state, FILTER_OVERFLOW: 5 }))
const api = { getMyScheduleDetail: vi.fn(), deletePersonalSchedule: vi.fn() }
vi.mock('~/composables/useScheduleApi', () => ({ useScheduleApi: () => api }))
const { navigateMock } = vi.hoisted(() => ({ navigateMock: vi.fn() }))
mockNuxtImport('navigateTo', () => navigateMock)

const stubs = { LazyCalendarGrid: true, ScheduleDayDialog: true, ScheduleEventForm: true, Dialog: true }
const event: CalendarEventItem = {
  id: 10, uniqueKey: 'personal:10', title: '当日予定', startAt: '2026-08-06T10:00:00',
  endAt: '2026-08-06T11:00:00', allDay: false, isPersonal: true, color: null,
}
const mountWidget = () => mountSuspended(WidgetMyCalendar, { global: { stubs } })

beforeEach(() => {
  vi.clearAllMocks()
  state.filteredEvents.value = []
  state.loading.value = false
  state.calendarLoading.value = false
  state.loadError.value = false
  state.initStorage.mockResolvedValue(undefined)
  state.loadEvents.mockResolvedValue(undefined)
  state.refresh.mockResolvedValue(undefined)
})

describe('個人ダッシュボードの日付操作', () => {
  it('一覧のTODO・reflectionと既存チップをそれぞれの既存導線へ渡す', async () => {
    const wrapper = await mountWidget()
    const dialog = wrapper.findComponent({ name: 'ScheduleDayDialog' })

    dialog.vm.$emit('rowOpen', { ...event, id: -12, isTodo: true })
    await flushPromises()
    expect(navigateMock).toHaveBeenLastCalledWith('/todos/11')
    dialog.vm.$emit('rowOpen', { ...event, id: -1, isReflection: true, referenceUuid: 'entry-uuid', referenceKind: 'REFLECTION_RECALL' })
    await flushPromises()
    expect(navigateMock).toHaveBeenLastCalledWith('/reflections/recall?entry=entry-uuid')
    dialog.vm.$emit('rowOpen', { ...event, isReflection: true, referenceUuid: 'theme-uuid', referenceKind: 'REFLECTION_PRE_EXAM' })
    await flushPromises()
    expect(navigateMock).toHaveBeenLastCalledWith('/reflections/themes/theme-uuid')
    wrapper.findComponent({ name: 'LazyCalendarGrid' }).vm.$emit('eventClick', -12, false)
    await flushPromises()
    expect(navigateMock).toHaveBeenLastCalledWith('/todos/11')
  })

  it('0件の日付は個人新規フォームを開き、保存後更新して別日にも新規として開く', async () => {
    const wrapper = await mountWidget()
    const grid = wrapper.findComponent({ name: 'LazyCalendarGrid' })

    grid.vm.$emit('dateClick', '2026-08-06')
    await flushPromises()
    let form = wrapper.findComponent({ name: 'ScheduleEventForm' })
    expect(form.props('visible')).toBe(true)
    expect(form.props('initialDate')).toBe('2026-08-06')
    expect(form.props('isPersonal')).toBe(true)
    expect(form.props('scheduleId')).toBeUndefined()
    form.vm.$emit('saved')
    await flushPromises()
    expect(state.refresh).toHaveBeenCalledOnce()
    state.filteredEvents.value = [event]
    grid.vm.$emit('dateClick', '2026-08-06')
    await flushPromises()
    expect(wrapper.findComponent({ name: 'ScheduleDayDialog' }).props('visible')).toBe(true)
    wrapper.findComponent({ name: 'ScheduleDayDialog' }).vm.$emit('create')
    await flushPromises()
    form = wrapper.findComponent({ name: 'ScheduleEventForm' })
    expect(form.props('initialDate')).toBe('2026-08-06')
    form.vm.$emit('update:visible', false)
    grid.vm.$emit('dateClick', '2026-08-07')
    await flushPromises()
    expect(form.props('initialDate')).toBe('2026-08-07')
  })

  it('レイヤー初期化完了後に予定を取得し、月取得中のクリックを無視する', async () => {
    let resolveStorage!: () => void
    state.loading.value = true
    state.initStorage.mockImplementationOnce(() => new Promise<void>(resolve => { resolveStorage = resolve }))
    const wrapper = await mountWidget()
    expect(state.loadEvents).not.toHaveBeenCalled()
    resolveStorage()
    await flushPromises()
    expect(state.loadEvents).toHaveBeenCalledOnce()
    state.loading.value = false
    state.calendarLoading.value = true
    await wrapper.vm.$nextTick()
    const grid = wrapper.findComponent({ name: 'LazyCalendarGrid' })
    grid.vm.$emit('dateClick', '2026-08-06')
    grid.vm.$emit('nextMonth')
    grid.vm.$emit('prevMonth')
    await flushPromises()
    expect(wrapper.findComponent({ name: 'ScheduleEventForm' }).props('visible')).toBe(false)
    expect(state.onNextMonth).not.toHaveBeenCalled()
    expect(state.onPrevMonth).not.toHaveBeenCalled()
  })

  it('取得失敗はエラーを表示し、再試行で予定を再取得する', async () => {
    state.loadError.value = true
    const wrapper = await mountWidget()
    const errorState = wrapper.findComponent({ name: 'DashboardErrorState' })
    expect(errorState.exists()).toBe(true)
    expect(wrapper.findComponent({ name: 'LazyCalendarGrid' }).exists()).toBe(false)
    state.loadEvents.mockClear()
    errorState.vm.$emit('retry')
    await flushPromises()
    expect(state.loadEvents).toHaveBeenCalledOnce()
  })
})
