import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { ref } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import WidgetScheduleCalendar from './WidgetScheduleCalendar.vue'

const api = { listSchedules: vi.fn(), getMySchedules: vi.fn() }
const access = {
  isAdminOrDeputy: ref(true), roleName: ref('ADMIN'), can: vi.fn(), loading: ref(false),
  loadPermissions: vi.fn(),
}
const captureQuiet = vi.fn()
const { navigateMock } = vi.hoisted(() => ({ navigateMock: vi.fn() }))
mockNuxtImport('navigateTo', () => navigateMock)
vi.mock('~/composables/useScheduleApi', () => ({ useScheduleApi: () => api }))
vi.mock('~/composables/useRoleAccess', () => ({ useRoleAccess: () => access }))
vi.mock('~/composables/useErrorReport', () => ({
  useErrorReport: () => ({ captureQuiet, capture: vi.fn(), state: ref({}) }),
}))
const stubs = { CalendarGrid: true, ScheduleDayDialog: true, ScheduleEventForm: true }
const mountWidget = (scopeType: 'team' | 'organization' = 'team') => mountSuspended(WidgetScheduleCalendar, {
  props: { scopeType, scopeId: 'our-scope' }, global: { stubs },
})
const activeForm = (wrapper: Awaited<ReturnType<typeof mountWidget>>) => wrapper
  .findAllComponents({ name: 'ScheduleEventForm' }).find(form => form.props('visible'))!

beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date('2026-08-15T03:00:00Z'))
  api.listSchedules.mockResolvedValue({ data: [] })
  api.getMySchedules.mockResolvedValue({ data: [] })
  access.isAdminOrDeputy.value = true
  access.roleName.value = 'ADMIN'
  access.can.mockReturnValue(false)
  access.loadPermissions.mockResolvedValue({ ok: true })
})
afterEach(() => vi.useRealTimers())

describe('共有ダッシュボードの日付操作', () => {
  it('月取得中の連打と日付選択を無視し、別月の並行取得を作らない', async () => {
    let resolveFetch!: (value: { data: [] }) => void
    api.listSchedules.mockResolvedValueOnce({ data: [] })
      .mockImplementationOnce(() => new Promise(resolve => { resolveFetch = resolve }))
    const wrapper = await mountWidget()
    await flushPromises()
    const grid = wrapper.findComponent({ name: 'CalendarGrid' })

    grid.vm.$emit('nextMonth')
    grid.vm.$emit('nextMonth')
    grid.vm.$emit('nextMonth')
    grid.vm.$emit('nextMonth')
    grid.vm.$emit('prevMonth')
    grid.vm.$emit('dateClick', '2026-11-06')
    await flushPromises()

    expect(grid.props('month')).toBe(11)
    expect(api.listSchedules).toHaveBeenCalledTimes(2)
    expect(activeForm(wrapper)).toBeUndefined()
    resolveFetch({ data: [] })
    await flushPromises()
    expect(grid.props('month')).toBe(11)
  })

  it.each(['team', 'organization'] as const)('%sの0件日は当該スコープの新規フォームを開いて保存後に再取得する', async (scopeType) => {
    const wrapper = await mountWidget(scopeType)
    await flushPromises()

    wrapper.findComponent({ name: 'CalendarGrid' }).vm.$emit('dateClick', '2026-08-06')
    await flushPromises()
    const form = activeForm(wrapper)
    expect(form.props()).toMatchObject({ initialDate: '2026-08-06', scopeType, scopeId: 'our-scope', isPersonal: false })
    expect(form.props('scheduleId')).toBeUndefined()
    form.vm.$emit('saved')
    await flushPromises()
    expect(api.listSchedules).toHaveBeenCalledTimes(2)
  })

  it.each(['GUEST', 'SUPPORTER', 'MEMBER'])('%sの権限なし0件日は空の一覧を開き、作成フォームを開かない', async (roleName) => {
    access.isAdminOrDeputy.value = false
    access.roleName.value = roleName
    const wrapper = await mountWidget()
    await flushPromises()

    wrapper.findComponent({ name: 'CalendarGrid' }).vm.$emit('dateClick', '2026-08-06')
    await flushPromises()

    expect(wrapper.findComponent({ name: 'ScheduleDayDialog' }).props()).toMatchObject({ visible: true, canCreate: false })
    expect(activeForm(wrapper)).toBeUndefined()
  })

  it('MANAGE_SCHEDULESを持つMEMBERは新規作成できる', async () => {
    access.isAdminOrDeputy.value = false
    access.roleName.value = 'MEMBER'
    access.can.mockImplementation(permission => permission === 'MANAGE_SCHEDULES')
    const wrapper = await mountWidget()
    await flushPromises()
    wrapper.findComponent({ name: 'CalendarGrid' }).vm.$emit('dateClick', '2026-08-06')
    await flushPromises()

    expect(activeForm(wrapper)).toBeDefined()
  })

  it('ネストAPIの共有・個人予定を一覧へ渡し、個人行は個人編集として再表示する', async () => {
    api.listSchedules.mockResolvedValue({ data: [{
      id: 10, content: { title: '共有' }, time: { startAt: '2026-08-06T10:00:00', endAt: '2026-08-06T11:00:00' },
    }] })
    api.getMySchedules.mockResolvedValue({ data: [{
      id: 20, content: { title: '個人', color: '#22c55e' }, time: { startAt: '2026-08-06T12:00:00', endAt: '2026-08-06T13:00:00' },
    }] })
    const wrapper = await mountWidget()
    await flushPromises()
    wrapper.findComponent({ name: 'CalendarGrid' }).vm.$emit('dateClick', '2026-08-06')
    await flushPromises()
    const dialog = wrapper.findComponent({ name: 'ScheduleDayDialog' })
    expect(dialog.props('visible')).toBe(true)
    expect(dialog.props('events').map((event: { title: string }) => event.title)).toEqual(['共有', '個人'])

    dialog.vm.$emit('rowOpen', dialog.props('events')[0])
    await flushPromises()
    expect(navigateMock).toHaveBeenLastCalledWith('/teams/our-scope/schedule?eventId=10')

    dialog.vm.$emit('rowOpen', dialog.props('events')[1])
    await flushPromises()
    expect(activeForm(wrapper).props()).toMatchObject({ isPersonal: true, scheduleId: 20 })
    activeForm(wrapper).vm.$emit('update:visible', false)
    wrapper.findComponent({ name: 'CalendarGrid' }).vm.$emit('dateClick', '2026-08-07')
    await flushPromises()
    expect(activeForm(wrapper).props('scheduleId')).toBeUndefined()
  })

  it('権限取得失敗はエラーとして再試行し、初回と同じ権限・予定の両方を取得する', async () => {
    access.loadPermissions.mockResolvedValueOnce({ ok: false, error: new Error('offline') })
    const wrapper = await mountWidget()
    await flushPromises()
    expect(wrapper.findComponent({ name: 'CalendarGrid' }).exists()).toBe(false)
    expect(captureQuiet).toHaveBeenCalledOnce()

    wrapper.findComponent({ name: 'DashboardErrorState' }).vm.$emit('retry')
    await flushPromises()
    expect(access.loadPermissions).toHaveBeenCalledTimes(2)
    expect(api.listSchedules).toHaveBeenCalledTimes(2)
    expect(wrapper.findComponent({ name: 'CalendarGrid' }).exists()).toBe(true)
  })
})
